import com.android.build.api.dsl.LibraryExtension

plugins { id("com.android.library") }

configure<LibraryExtension> {
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 与 :app 一致：源码与资源直接放在模块根，不嵌套包路径。
    sourceSets.getByName("main") {
        java.srcDirs("src")
        kotlin.srcDirs("src")
        res.srcDirs("res")
    }
}

// ktfmt 必须在自定义源码目录注册之后应用，否则只扫描默认目录。
apply(plugin = "com.ncorti.ktfmt.gradle")

configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
    kotlinLangStyle()
    maxWidth.set(100)
    removeUnusedImports.set(true)
}
