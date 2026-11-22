plugins { id("github.android.feature") }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.feature.auth"
}
