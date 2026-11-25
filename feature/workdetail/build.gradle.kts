plugins {
    id("github.android.feature")
    id("org.jetbrains.kotlin.plugin.serialization")
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.feature.workdetail"
}
