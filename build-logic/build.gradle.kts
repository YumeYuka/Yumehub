plugins { `kotlin-dsl` }

group = "moe.yumeyuka.yumehub.buildlogic"

dependencies {
    compileOnly(libs.aapt.proto)
    compileOnly(libs.protobuf.java)
    implementation(libs.xz)
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.compose.gradlePlugin)
}

apply(from = "../gradle/ktfmt.gradle.kts")
