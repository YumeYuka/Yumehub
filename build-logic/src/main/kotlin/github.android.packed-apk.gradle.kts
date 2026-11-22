import com.android.build.api.artifact.SingleArtifact
import dev.yume.packer.PackReleaseApk

plugins { id("com.android.application") }

val loaderAar = configurations.detachedConfiguration(
    dependencies.project(mapOf("path" to ":pack", "configuration" to "bootstrapAar")),
)
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val loaderRuntime = configurations.detachedConfiguration(
    catalog.findLibrary("hiddenapibypass").get().get(),
).apply { isTransitive = false }

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val releaseSigning = android.buildTypes.getByName("release").signingConfig
    val sdk = androidComponents.sdkComponents.sdkDirectory
    val toolsVersion = android.buildToolsVersion
    val pack = tasks.register<PackReleaseApk>("pack${variant.name.replaceFirstChar(Char::uppercaseChar)}Apk") {
        group = "build"
        loader.from(loaderAar)
        runtime.from(loaderRuntime)
        sdkDirectory.set(sdk)
        buildToolsVersion.set(toolsVersion)
        minimumSdk.set(variant.minSdk.apiLevel)
        keyStore.set(releaseSigning?.storeFile)
        keyAlias.set(releaseSigning?.keyAlias)
        storePassword.set(releaseSigning?.storePassword)
        keyPassword.set(releaseSigning?.keyPassword)
    }
    val request =
        variant.artifacts
            .use(pack)
            .wiredWithDirectories(PackReleaseApk::inputApks, PackReleaseApk::outputApks)
            .toTransformMany(SingleArtifact.APK)
    pack.configure { transformation.set(request) }
}
