import dev.yume.packer.PackTextAssets

plugins { id("github.android.feature") }

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "moe.yumeyuka.yumehub.feature.markdown"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    compileOptions.isCoreLibraryDesugaringEnabled = true
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
    }
}

dependencies {
    // 原生正文库只在这里适配后经 :ui 契约暴露，其他 feature 不直接依赖。
    // core 负责 HTML→不可变语义树，compose 负责排版。
    implementation(project(":markdown:core"))
    implementation(project(":markdown:compose"))
    implementation(libs.sketch.compose)
    implementation(libs.sketch.svg)
    coreLibraryDesugaring(libs.android.desugar.jdk.nio)
}
