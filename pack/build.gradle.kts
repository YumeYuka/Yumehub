import com.android.build.api.dsl.LibraryExtension

plugins { id("com.android.library") }

configure<LibraryExtension> {
  namespace = "com.github.yumeyucca.yumebox.loader"
  compileSdk = 37
  compileSdkMinor = 2
  ndkVersion = "27.1.12297006"

  defaultConfig {
    minSdk = 33
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild.cmake {
      arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
      targets += "loader"
    }
  }
  sourceSets.getByName("main") {
    manifest.srcFile("src/AndroidManifest.xml")
    java.setSrcDirs(listOf("src"))
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  externalNativeBuild.cmake {
    path = file("native/CMakeLists.txt")
    version = "3.22.1"
  }
}

dependencies {
  compileOnly(libs.loader.annotation)
  implementation(libs.hiddenapibypass)
}

// 只交给 APK 打包任务，不把 loader 放进应用业务 DEX 或 AAB。
val bootstrapAar = configurations.consumable("bootstrapAar")

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
  artifacts.add(
      bootstrapAar.name,
      variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.AAR),
  )
}
