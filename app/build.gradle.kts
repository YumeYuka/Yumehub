import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("github.android.packed-apk")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("signing.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

val hasReleaseSigning =
    listOf("keystore.password", "key.alias", "key.password").all {
        !keystoreProperties.getProperty(it).isNullOrBlank()
    } &&
        rootProject.file(keystoreProperties.getProperty("keystore.path", "release.keystore")).isFile

android {
    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile =
                    rootProject.file(
                        keystoreProperties.getProperty("keystore.path", "release.keystore"),
                    )
                storePassword = keystoreProperties.getProperty("keystore.password")
                keyAlias = keystoreProperties.getProperty("key.alias")
                keyPassword = keystoreProperties.getProperty("key.password")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    namespace = "moe.yumeyuka.yumehub"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "moe.yumeyuka.yumehub"
        // Glasmio requires API 33.
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        // Release package is arm64-v8a only: no 32-bit ABIs, no emulator ABIs.
        ndk {
            ndkVersion = "27.1.12297006"
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }

        // AppAuth redirect scheme (GitHub Mobile callback: github://com.github.android/oauth).
        manifestPlaceholders["appAuthRedirectScheme"] = "github"
    }

    buildTypes {
        debug {
            // Keep the signing identity when switching build variants on a development device.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8: shrink/obfuscate code and drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    androidResources { localeFilters += listOf("en", "zh") }

    packaging {
        jniLibs { useLegacyPackaging = true }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Glasmio 各模块打包的是同一组文件；只保留 core 的副本及独立的 Capture 声明。
            val duplicateModules =
                listOf("glasmio-glass", "glasmio-icons", "glasmio-scaffold", "glasmio-ui")
            for (module in duplicateModules) {
                excludes += "/META-INF/glasmio/$module/LICENSE"
                excludes += "/META-INF/glasmio/$module/THIRD_PARTY_NOTICES.md"
                excludes += "/META-INF/glasmio/$module/licenses/Apache-2.0.txt"
            }

            // 这些 AndroidX Apache 2.0 文本与 annotation/annotation/LICENSE.txt 逐字节相同。
            excludes +=
                setOf(
                    "/META-INF/androidx/collection/{collection,collection-ktx}/LICENSE.txt",
                    "/META-INF/androidx/lifecycle/lifecycle-common/LICENSE.txt",
                    "/META-INF/androidx/lifecycle/lifecycle-common-java8/LICENSE.txt",
                    "/META-INF/androidx/paging/paging-common-ktx/LICENSE.txt",
                    "/META-INF/org/jetbrains/androidx/lifecycle/lifecycle-common/LICENSE.txt",
                )

            // JDT 注解 JAR 附带的源码不参与运行；保留含版权声明的 bundle.properties。
            excludes += "/src/org/eclipse/jdt/annotation/*.java"

            // language-textmate 的 Joni 回退引擎依赖 jcodings，其 jar 自带 42 个
            // Transcoder_*.bin 编码转换表（解压 2.68 MB，压缩后仍占 1.14 MB）。
            // 正则只处理 UTF-8 的 TextMate 语法文本，不涉及 Big5/GBK/SJIS 等旧编码，
            // 这些表不会被读取；保留 CR_*/Case* 等 Unicode 属性表以备 Joni 回退。
            excludes += "/tables/Transcoder_*.bin"
        }
    }

    dependenciesInfo {
        // Play Store dependency metadata is not needed inside the APK/AAB.
        includeInApk = false
        includeInBundle = false
    }

    // miuix is published with JVM 21 bytecode; keep the app target aligned.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Sources live under app/src and resources under app/res (YumeBox layout).
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/AndroidManifest.xml")
            java.srcDirs("src")
            kotlin.srcDirs("src")
            res.srcDirs("res")
        }
    }

    buildFeatures { compose = true }
}

// 使用 ktfmt 官方 Kotlinlang 风格，格式化配置与共享模块保持一致。
// Applied after android { } so the plugin sees the custom app/src source dirs.
apply(plugin = "com.ncorti.ktfmt.gradle")

configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
    kotlinLangStyle()
    maxWidth.set(100)
    removeUnusedImports.set(true)
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    coreLibraryDesugaring(libs.android.desugar.jdk.nio)

    // 模块化拆分后的共享层；feature 模块在拆分过程中逐个接入。
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":data"))
    implementation(project(":locale"))
    implementation(project(":ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:markdown"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:explore"))
    implementation(project(":feature:discovery"))
    implementation(project(":feature:search"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:profilecollections"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:repositories"))
    implementation(project(":feature:repository"))
    implementation(project(":feature:releases"))
    implementation(project(":feature:workspaces"))
    implementation(project(":feature:starred"))
    implementation(project(":feature:issuesearch"))
    implementation(project(":feature:worklist"))
    implementation(project(":feature:workdetail"))
    implementation(project(":feature:pullrequestdiff"))
    implementation(project(":feature:pullrequestcommits"))
    implementation(project(":feature:discussiondetail"))
    implementation(project(":feature:commitdetail"))
    implementation(project(":feature:repositoryactivity"))
    implementation(project(":feature:actions"))
    implementation(project(":feature:editor"))

    // AndroidX core and lifecycle.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)

    // Local storage and paging.
    implementation(libs.mmkv)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Coroutines, serialization and dependency injection.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Glasmio UI and Miuix text primitive.
    implementation(libs.miuix.ui)
    implementation(libs.miuix.glass)
    implementation(libs.glasmio.core)
    implementation(libs.glasmio.glass)
    implementation(libs.glasmio.ui)
    implementation(libs.glasmio.scaffold)
    implementation(libs.glasmio.icons)
    implementation(libs.lucide.compose)

    // Decompose 导航栈（与 YumeBox 同款）：子组件生命周期、返回处理与栈转场。
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)

    // OAuth and networking.
    implementation(libs.appauth)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
