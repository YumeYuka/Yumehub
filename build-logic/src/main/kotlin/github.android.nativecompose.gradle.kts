import com.android.build.api.dsl.LibraryExtension

plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

configure<LibraryExtension> {
    buildFeatures.compose = true
    compileOptions.isCoreLibraryDesugaringEnabled = true
    sourceSets.getByName("test") {
        kotlin.directories.apply {
            clear()
            add("test")
        }
        java.directories.apply {
            clear()
            add("test")
        }
    }
}

// 基础插件应用时 ktfmt 已捕获目录，显式登记窄渲染库的测试源码。
tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtFormatTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtCheckTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

val catalog =
    extensions
        .getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")

fun library(alias: String) = catalog.findLibrary(alias).orElseThrow().get()

dependencies {
    add("implementation", platform(library("androidx-compose-bom")))
    add("implementation", library("androidx-compose-ui"))
    add("implementation", library("androidx-compose-ui-graphics"))
    add("implementation", library("androidx-compose-foundation"))
    add("coreLibraryDesugaring", library("android-desugar-jdk-nio"))
}
