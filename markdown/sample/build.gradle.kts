import org.tukaani.xz.XZInputStream

buildscript { dependencies { classpath(libs.xz) } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
            java.srcDirs("src")
            kotlin.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs(
                rootProject.file("feature/markdown/assets"),
                rootProject.file("markdown/fixtures"),
            )
            providers.gradleProperty("markdown.referenceAssetsDir").orNull?.let { path ->
                assets.srcDir(file(path))
            }
        }
        getByName("androidTest") {
            java.srcDirs("androidTest")
            kotlin.srcDirs("androidTest")
        }
    }
}

// 独立验收宿主不用应用主题，两个渲染模式仍使用与生产相同的 Inter 源文件。
@CacheableTask
abstract class PrepareSampleInterAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty

    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun prepare() {
        val target = output.dir("fonts").get().asFile.apply { mkdirs() }
        for (name in listOf("Inter.ttf", "Inter-Italic.ttf")) {
            fonts.file("$name.xz").get().asFile.inputStream().use { asset ->
                XZInputStream(asset.buffered()).use { input ->
                    target.resolve(name).outputStream().use { input.copyTo(it) }
                }
            }
        }
        fonts.file("OFL.txt").get().asFile.copyTo(target.resolve("OFL.txt"), overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val sourceFonts = rootProject.layout.projectDirectory.dir("ui/assets/fonts/inter-0b58fb37")
    val prepare =
        tasks.register<PrepareSampleInterAssets>(
            "prepare${variant.name.replaceFirstChar(Char::uppercaseChar)}SampleInterAssets",
        ) {
            fonts.set(sourceFonts)
        }
    variant.sources.assets?.addGeneratedSourceDirectory(prepare, PrepareSampleInterAssets::output)
}

apply(plugin = "com.ncorti.ktfmt.gradle")

configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
    kotlinLangStyle()
    maxWidth.set(100)
    removeUnusedImports.set(true)
}

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
