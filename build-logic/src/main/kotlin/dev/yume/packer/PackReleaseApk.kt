package dev.yume.packer

import com.android.build.api.artifact.ArtifactTransformationRequest
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Uses the local Android toolchain and release signing identity")
abstract class PackReleaseApk : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputApks: DirectoryProperty
    @get:OutputDirectory abstract val outputApks: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val loader: ConfigurableFileCollection
    @get:Classpath abstract val runtime: ConfigurableFileCollection
    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Input abstract val buildToolsVersion: Property<String>
    @get:Input abstract val minimumSdk: Property<Int>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val keyStore: RegularFileProperty
    @get:Input abstract val keyAlias: Property<String>
    @get:Internal abstract val storePassword: Property<String>
    @get:Internal abstract val keyPassword: Property<String>
    @get:Internal abstract val transformation: Property<ArtifactTransformationRequest<PackReleaseApk>>

    @TaskAction
    fun pack() {
        val sdk = sdkDirectory.get().asFile
        val tools = sdk.resolve("build-tools/${buildToolsVersion.get()}")
        val signing = ApkSigning(keyStore.get().asFile, keyAlias.get(), storePassword.get(), keyPassword.get())
        val dex = temporaryDir.resolve("loader-dex")
        val loaderAar = loader.singleFile
        LoaderDexBuilder(sdk, temporaryDir.resolve("loader-work"))
            .build(loaderAar, runtime.files, minimumSdk.get(), dex)
        check(dex.listFiles()?.map { it.name } == listOf("classes.dex")) { "Loader must fit in one DEX" }
        val bootstrap = ZipFile(loaderAar).use { aar ->
            val library = requireNotNull(aar.getEntry("jni/arm64-v8a/libloader.so")) { "No arm64 loader in AAR" }
            aar.getInputStream(library).use { it.readBytes() }
        }
        val destination = outputApks.get().asFile.apply { mkdirs() }
        transformation.get().submit(this) { artifact ->
            val input = File(artifact.outputFile)
            val work = temporaryDir.resolve(input.nameWithoutExtension).apply { mkdirs() }
            val prepared = prepareLoaderApk(input, work, tools, bootstrap)
            val ready = work.resolve("verified.apk")
            ApkPacker(sdk, work.resolve("packer"), dex.resolve("classes.dex"), APP, FACTORY, signing)
                .pack(prepared, ready)
            verifyPackedPayload(ready, input)
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            runTool(java, "-jar", tools.resolve("lib/apksigner.jar").absolutePath, "verify", ready.absolutePath)
            val notices = work.resolve("${input.nameWithoutExtension}-notices.zip")
            writeReleaseNotices(input, notices)
            notices.copyTo(destination.resolve(notices.name), overwrite = true)
            ready.copyTo(destination.resolve(input.name), overwrite = true)
        }
    }
}
