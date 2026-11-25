plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
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
