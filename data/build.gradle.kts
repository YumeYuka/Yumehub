import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
    id("github.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.data"
    sourceSets.getByName("test") { kotlin.srcDirs("test") }
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

// AGP 自定义 test/ 目录不在插件初次扫描的目录中，显式纳入同一 Kotlinlang 规则。
val formatTests =
    tasks.register<KtfmtFormatTask>("ktfmtFormatTests") {
        source = project.fileTree("test")
        include("**/*.kt")
    }
val checkTests =
    tasks.register<KtfmtCheckTask>("ktfmtCheckTests") {
        source = project.fileTree("test")
        include("**/*.kt")
    }

tasks.named("ktfmtFormat") { dependsOn(formatTests) }

tasks.named("ktfmtCheck") { dependsOn(checkTests) }
