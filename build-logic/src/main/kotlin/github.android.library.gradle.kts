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
        java.directories += "src"
        kotlin.directories += "src"
        res.directories += "res"
    }
}

apply(plugin = "github.android.ktfmt")
