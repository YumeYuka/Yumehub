plugins { id("github.android.feature") }

dependencies { implementation(libs.androidx.activity.compose) }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.feature.settings"
}
