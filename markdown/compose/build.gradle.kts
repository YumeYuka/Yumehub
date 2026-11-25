plugins { id("github.android.nativecompose") }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.markdown.compose"
}

dependencies {
    api(project(":markdown:core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
}
