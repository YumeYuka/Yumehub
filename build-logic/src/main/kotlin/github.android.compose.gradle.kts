import com.android.build.api.dsl.LibraryExtension

plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

configure<LibraryExtension> { buildFeatures.compose = true }

// 预编译脚本拿不到 libs 类型安全访问器，只能按别名查版本目录。
val catalog =
    extensions
        .getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")

fun library(alias: String) = catalog.findLibrary(alias).orElseThrow().get()

dependencies {
    add("implementation", platform(library("androidx-compose-bom")))
    add("implementation", library("androidx-compose-ui"))
    add("implementation", library("androidx-compose-ui-graphics"))
    add("implementation", library("miuix-ui"))
    add("implementation", library("miuix-glass"))
    add("implementation", library("glasmio-core"))
    add("implementation", library("glasmio-glass"))
    add("implementation", library("glasmio-ui"))
    add("implementation", library("glasmio-scaffold"))
    add("implementation", library("glasmio-icons"))
    add("implementation", library("lucide-compose"))
}
