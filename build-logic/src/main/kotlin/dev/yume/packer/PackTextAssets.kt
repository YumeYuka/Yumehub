package dev.yume.packer

import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/** 把一组互相引用的文本资源先收进一个 zip，再整体用 XZ 压缩。 */
@CacheableTask
abstract class PackTextAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:Input abstract val archiveIncludes: ListProperty<String>
    @get:Input abstract val passthrough: ListProperty<String>
    @get:Input abstract val archiveName: Property<String>

    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun pack() {
        val root = source.get().asFile
        val files = source.asFileTree.matching { include(archiveIncludes.get()) }
        require(!files.isEmpty) { "No assets found for ${archiveName.get()}" }
        val destination = temporaryDir.resolve(archiveName.get())
        XZOutputStream(destination.outputStream().buffered(), LZMA2Options(3)).use { xz ->
            ZipOutputStream(xz).use { zip ->
                for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    val bytes = file.readBytes()
                    // ZIP 只保存路径；让 XZ 跨条目压缩重复的语法和主题文本。
                    val entry =
                        ZipEntry(path).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        // 同一变体可能还留有旧版逐文件 .xz，输出必须与本次输入完全一致。
        val looseAssets = passthrough.get()
        fs.sync {
            from(destination)
            if (looseAssets.isNotEmpty()) from(root) { include(looseAssets) }
            into(output)
            includeEmptyDirs = false
        }
    }
}
