plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("github.android.ktfmt") apply false
}

android {
    namespace = "moe.yumeyuka.markdown.sample"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        applicationId = "moe.yumeyuka.markdown.sample"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true }
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories += "src"
            kotlin.directories += "src"
            res.directories += "res"
            assets.directories += rootProject.file("feature/markdown/assets").path
            assets.directories += rootProject.file("markdown/fixtures").path
            providers.gradleProperty("markdown.referenceAssetsDir").orNull?.let { path ->
                assets.directories += file(path).path
            }
        }
        getByName("androidTest") {
            java.directories += "androidTest"
            kotlin.directories += "androidTest"
        }
    }
}

// 独立验收宿主不用应用主题，两个渲染模式使用与生产 WebView 相同的 Ubuntu 源文件。
@CacheableTask
abstract class PrepareSampleFontAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty

    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun prepare() {
        val target = output.dir("fonts").get().asFile.apply { mkdirs() }
        val name = "Ubuntu-Regular.ttf"
        fonts.file(name).get().asFile.copyTo(target.resolve(name), overwrite = true)
        fonts.file("UFL.txt").get().asFile.copyTo(target.resolve("UFL.txt"), overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val sourceFonts =
        rootProject.layout.projectDirectory.dir("feature/markdown/assets/github-markdown/fonts")
    val prepare =
        tasks.register<PrepareSampleFontAssets>(
            "prepare${variant.name.replaceFirstChar(Char::uppercaseChar)}SampleFontAssets",
        ) {
            fonts.set(sourceFonts)
        }
    variant.sources.assets?.addGeneratedSourceDirectory(prepare, PrepareSampleFontAssets::output)
}

apply(plugin = "github.android.ktfmt")

dependencies {
    implementation(project(":markdown:core"))
    implementation(project(":markdown:compose"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.android.desugar.jdk.nio)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
