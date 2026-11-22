plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.locale"
    buildFeatures.compose = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    // Locale-aware Compose strings, the same runtime YumeBox uses.
    implementation(libs.lyricist)
}
