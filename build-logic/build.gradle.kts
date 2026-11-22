plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktfmt)
}

group = "moe.yumeyuka.yumehub.buildlogic"

dependencies {
    compileOnly(libs.aapt.proto)
    compileOnly(libs.protobuf.java)
    implementation(libs.xz)
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.compose.gradlePlugin)
    implementation(libs.ktfmt.gradlePlugin)
}

ktfmt {
    kotlinLangStyle()
    maxWidth.set(100)
    removeUnusedImports.set(true)
}
