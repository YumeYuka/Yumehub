plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.data"
    sourceSets.getByName("test") { kotlin.directories += "test" }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":core:common"))
    implementation(project(":core:model"))

    implementation(libs.mmkv)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.appauth)
    implementation(libs.androidx.paging.runtime)

    // GitHub API: Retrofit + OkHttp + kotlinx.serialization converter.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.jsoup)
}

val ktfmtSources = extensions.getByName<NamedDomainObjectContainer<SourceDirectorySet>>("ktfmtSources")
ktfmtSources.create("tests") {
    srcDir("test")
    include("**/*.kt")
}
