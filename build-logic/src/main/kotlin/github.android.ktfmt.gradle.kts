import com.android.build.api.dsl.CommonExtension

apply(from = rootProject.file("gradle/ktfmt.gradle.kts"))

val scopes = extensions.getByName<NamedDomainObjectContainer<SourceDirectorySet>>("ktfmtSources")
extensions.getByType<CommonExtension>().sourceSets.configureEach {
    val roots = (kotlin.directories + java.directories).map { file(it) }
    val checkedRoots = roots.filterNot { dir -> dir.toPath().any { it.toString() == "build" } }
    scopes.create(name) { setSrcDirs(checkedRoots) }
}
