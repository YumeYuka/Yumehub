plugins { id("github.android.compose") }

val catalog =
    extensions
        .getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")

fun library(alias: String) = catalog.findLibrary(alias).orElseThrow().get()

// feature 只向下依赖共享层，禁止依赖其他 feature。
dependencies {
    add("implementation", project(":core:common"))
    add("implementation", project(":core:model"))
    add("implementation", project(":data"))
    add("implementation", project(":ui"))

    add("implementation", library("koin-android"))
    add("implementation", library("koin-androidx-compose"))
    add("implementation", library("androidx-lifecycle-viewmodel-compose"))
    add("implementation", library("androidx-lifecycle-runtime-compose"))
    add("implementation", library("androidx-paging-runtime"))
    add("implementation", library("androidx-paging-compose"))
    add("implementation", library("kotlinx-coroutines-android"))
    add("implementation", library("kotlinx-serialization-json"))
    add("implementation", library("retrofit"))
}
