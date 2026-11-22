import dev.yume.packer.PackTextAssets

plugins { id("github.android.compose") }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.ui"
    sourceSets.getByName("main") { resources.srcDir("resources") }
    sourceSets.getByName("test") { kotlin.srcDirs("test") }
}

configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar(Char::uppercaseChar)
        val assets = layout.projectDirectory.dir("assets")
        val pack =
            tasks.register<PackTextAssets>("pack${suffix}TextAssets") {
                source.set(assets)
                archiveIncludes.set(listOf("textmate/*/syntaxes/*", "textmate/cursor-*.json"))
                passthrough.set(
                    listOf("fonts/inter-0b58fb37/*.ttf.xz", "fonts/*/OFL.txt", "licenses/**"),
                )
                archiveName.set("textmate.xz")
            }
        variant.sources.assets?.addGeneratedSourceDirectory(pack, PackTextAssets::output)
    }
}

// convention 应用时已捕获默认测试目录，显式接入模块根的测试源码。
tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtFormatTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

tasks.named<org.gradle.api.tasks.SourceTask>("ktfmtCheckTest") {
    setSource(fileTree("test") { include("**/*.kt") })
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    // :ui 允许依赖 :data（组件渲染 data 的 DTO、主题读取默认强调色）。
    api(project(":data"))
    implementation(libs.koin.androidx.compose)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.retrofit)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.sora.language.textmate)
    implementation(libs.sora.oniguruma.native)

    // 图片加载：内存与磁盘缓存、按显示尺寸解码，网络复用应用的 OkHttp。
    implementation(libs.sketch.compose)
    implementation(libs.sketch.http.okhttp)
    // README 徽标多为 SVG；原生正文与列表头像共用同一条解码链。
    implementation(libs.sketch.svg)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.xz)
    implementation(libs.kotlinx.serialization.json)
    api(project(":locale"))

    // 页面级返回统一走 NavigationEvent；共享 wrapper 的 API 同时供 app 与 feature 使用。
    api(libs.androidx.navigationevent.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
