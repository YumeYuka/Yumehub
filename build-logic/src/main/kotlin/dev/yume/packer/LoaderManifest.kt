package dev.yume.packer

import com.android.aapt.Resources
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

internal const val APP = "moe.yumeyuka.yumehub.App"
internal const val FACTORY = "androidx.core.app.CoreComponentFactory"
private const val LOADER = "com.github.yumeyucca.yumebox.loader"
private const val ANDROID = "http://schemas.android.com/apk/res/android"
private const val MANIFEST = "AndroidManifest.xml"

internal fun runTool(vararg args: String) {
    check(ProcessBuilder(*args).inheritIO().start().waitFor() == 0) { "${args.first()} failed" }
}

// 和 YumeBox 一样用 AAPT2 的 XML proto 编辑 APK；业务清单、资源表与 AAB 不变。
internal fun prepareLoaderApk(raw: File, work: File, tools: File, bootstrap: ByteArray): File {
    val aapt = tools.resolve(if (File.separatorChar == '\\') "aapt2.exe" else "aapt2").absolutePath
    val proto = work.resolve("proto.apk")
    val binary = work.resolve("binary.apk")
    runTool(aapt, "convert", "--output-format", "proto", "-o", proto.path, raw.path)
    FileSystems.newFileSystem(proto.toPath(), emptyMap<String, Any>()).use { zip ->
        val path = zip.getPath("/$MANIFEST")
        val root = Resources.XmlNode.parseFrom(Files.readAllBytes(path)).toBuilder()
        val app = root.elementBuilder.childBuilderList.single { it.element.name == "application" }.elementBuilder
        fun set(name: String, id: Int, value: String, flag: Boolean = false) {
            val attr = app.attributeBuilderList.singleOrNull { it.namespaceUri == ANDROID && it.name == name }
                ?: app.addAttributeBuilder().setNamespaceUri(ANDROID).setName(name).setResourceId(id)
            attr.value = value
            val item = attr.compiledItemBuilder
            if (flag) item.primBuilder.booleanValue = true else item.strBuilder.value = value
        }
        set("name", 0x01010003, "$LOADER.LoaderApplication")
        set("appComponentFactory", 0x0101057a, "$LOADER.LoaderComponentFactory")
        set("extractNativeLibs", 0x010104ea, "true", flag = true)
        Files.write(path, root.build().toByteArray())
    }
    runTool(aapt, "convert", "--output-format", "binary", "-o", binary.path, proto.path)
    val prepared = raw.copyTo(work.resolve("prepared.apk"), overwrite = true)
    FileSystems.newFileSystem(prepared.toPath(), emptyMap<String, Any>()).use { zip ->
        ZipFile(binary).use { compiled ->
            compiled.getInputStream(compiled.getEntry(MANIFEST)).use {
                Files.write(zip.getPath("/$MANIFEST"), it.readBytes())
            }
        }
        val library = zip.getPath("/lib/arm64-v8a/libloader.so")
        check(Files.notExists(library)) { "Application already contains libloader.so" }
        Files.createDirectories(library.parent)
        Files.write(library, bootstrap)
    }
    return prepared
}
