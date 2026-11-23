import dev.yume.packer.PackTextAssets

plugins { id("github.android.feature") }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.feature.markdown"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions.isCoreLibraryDesugaringEnabled = true
    sourceSets.getByName("test").kotlin.setSrcDirs(listOf("test"))
    sourceSets.getByName("test").java.setSrcDirs(listOf("test"))
    sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("androidTest"))
    sourceSets.getByName("androidTest").java.setSrcDirs(listOf("androidTest"))
    sourceSets.getByName("androidTest").assets.srcDir("androidTestAssets")
}

// convention 应用时 ktfmt 已捕获目录，显式接入本模块的自定义测试源码。
tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtFormatTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtCheckTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtFormatAndroidTest") {
    setSource(fileTree("androidTest") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtCheckAndroidTest") {
    setSource(fileTree("androidTest") { include("**/*.kt") })
}

configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar(Char::uppercaseChar)
        val pack =
            tasks.register<PackTextAssets>("pack${suffix}TextAssets") {
                val web = "github-markdown/webview"
                source.set(layout.projectDirectory.dir("assets"))
                archiveIncludes.set(listOf("$web/*.html", "$web/*.css", "$web/*.js"))
                passthrough.set(listOf("github-markdown/fonts/*"))
                archiveName.set("github-markdown.xz")
            }
        variant.sources.assets?.addGeneratedSourceDirectory(pack, PackTextAssets::output)
        // 仪器 APK 使用隔离回调 scheme；不改变应用已有的 OAuth 配置。
        variant.androidTest
            ?.manifestPlaceholders
            ?.put(
                "appAuthRedirectScheme",
                "yumehub-markdown-acceptance",
            )
    }
}

dependencies {
    testImplementation(libs.junit)
    // 原生正文库只在这里适配后经 :ui 契约暴露，其他 feature 不直接依赖。
    // core 负责 HTML→不可变语义树，compose 负责排版。
    implementation(project(":markdown:core"))
    implementation(project(":markdown:compose"))
    implementation(libs.sketch.compose)
    implementation(libs.sketch.svg)
    coreLibraryDesugaring(libs.android.desugar.jdk.nio)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.mmkv)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.sketch.http.okhttp)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
