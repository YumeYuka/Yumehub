import javax.inject.Inject
import org.gradle.api.tasks.options.Option
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Formatting edits its inputs in place")
abstract class KtfmtCliTask : SourceTask() {
    @get:Classpath abstract val cli: ConfigurableFileCollection

    @get:Optional @get:OutputFile abstract val checkReceipt: RegularFileProperty

    @get:Inject abstract val execOperations: ExecOperations

    @Option(option = "include-only", description = "Only process these project-relative files")
    fun includeOnly(paths: String) {
        if (paths.isBlank()) return
        val selected =
            paths.split(',', ':').mapTo(mutableSetOf()) {
                project.file(it.replace('\\', '/').removePrefix("/")).canonicalFile
            }
        exclude { !it.isDirectory && it.file.canonicalFile !in selected }
    }

    @TaskAction
    fun runFormatter() {
        // The CLI accepts one UTF-8 argument per line, including paths containing spaces.
        val argsFile = temporaryDir.resolve("arguments.txt")
        argsFile.bufferedWriter().use { writer ->
            writer.appendLine("--kotlinlang-style")
            if (checkReceipt.isPresent) {
                writer.appendLine("--dry-run")
                writer.appendLine("--set-exit-if-changed")
            }
            source.forEach { writer.appendLine(it.absolutePath) }
        }
        execOperations.javaexec {
            classpath(cli)
            mainClass.set("org.jetbrains.kotlinx.ktfmt.cli.Main")
            args("@${argsFile.absolutePath}")
        }
        checkReceipt.orNull?.asFile?.writeText("OK\n")
    }
}

val ktfmtDependencies = configurations.dependencyScope("ktfmtDependencies")
val ktfmtClasspath =
    configurations.resolvable("ktfmtClasspath") {
        extendsFrom(ktfmtDependencies.get())
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies.addProvider(ktfmtDependencies.name, catalog.findLibrary("ktfmt").get())

val checkAll = tasks.register("ktfmtCheck") { group = "verification" }
val formatAll = tasks.register("ktfmtFormat") { group = "formatting" }
val scopes =
    objects.domainObjectContainer(SourceDirectorySet::class.java) { name ->
        objects.sourceDirectorySet(name, "ktfmt $name")
    }
extensions.add("ktfmtSources", scopes)
scopes.all {
    val files = this
    val suffix = name.replaceFirstChar(Char::uppercaseChar)
    fun register(action: String, check: Boolean) =
        tasks.register<KtfmtCliTask>("ktfmt$action$suffix") {
            group = if (check) "verification" else "formatting"
            cli.from(ktfmtClasspath)
            setSource(files)
            include("**/*.kt", "**/*.kts")
            if (check) checkReceipt.set(layout.buildDirectory.file("ktfmt/$name/checked"))
        }
    val check = register("Check", true)
    val format = register("Format", false)
    checkAll.configure { dependsOn(check) }
    formatAll.configure { dependsOn(format) }
}
scopes.create("scripts") {
    srcDir(projectDir)
    include("*.kts")
}

plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.getByType<SourceSetContainer>().all {
        val kotlin = extensions.getByName("kotlin") as SourceDirectorySet
        scopes.create(name) {
            srcDirs(
                provider {
                    kotlin.srcDirs.filterNot { dir -> dir.toPath().any { it.toString() == "build" } }
                }
            )
        }
    }
}
plugins.withType<LifecycleBasePlugin> { tasks.named("check") { dependsOn(checkAll) } }
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    mustRunAfter(tasks.withType<KtfmtCliTask>())
}
