package dev.yume.packer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val META_INF = "META-INF/"
private const val SERVICES = "${META_INF}services/"

private fun isLicenseAsset(name: String): Boolean =
    name.startsWith("assets/licenses/") ||
        (name.startsWith("assets/fonts/") && name.endsWith("/OFL.txt"))

// 同 YumeBox，保留运行时 ServiceLoader 描述；其余 META-INF 在重新签名前清理。
internal fun shouldStripReleaseMetadata(name: String): Boolean =
    isLicenseAsset(name) || (name.startsWith(META_INF) && !name.startsWith(SERVICES))

private val licensePath =
    Regex("licen[cs]es?|notices?|copying|copyright", RegexOption.IGNORE_CASE)
private val v1Signature = Regex("""META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))""")

internal fun writeReleaseNotices(apk: File, output: File) {
    ZipFile(apk).use { zip ->
        ZipOutputStream(output.outputStream().buffered()).use { notices ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (entry.isDirectory || !shouldStripReleaseMetadata(name)) continue
                if (!isLicenseAsset(name) && !licensePath.containsMatchIn(name)) continue
                notices.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.getInputStream(entry).use { it.copyTo(notices) }
                notices.closeEntry()
            }
        }
    }
}

internal fun verifyReleaseMetadata(packed: ZipFile, original: File) {
    val unexpected =
        packed.entries().asSequence().firstOrNull {
            !it.isDirectory && shouldStripReleaseMetadata(it.name) && !v1Signature.matches(it.name)
        }
    check(unexpected == null) { "Unexpected metadata in packed APK: ${unexpected?.name}" }

    ZipFile(original).use { raw ->
        for (entry in raw.entries()) {
            if (entry.isDirectory || !entry.name.startsWith(SERVICES)) continue
            val kept =
                requireNotNull(packed.getEntry(entry.name)) { "Missing service: ${entry.name}" }
            val expected = raw.getInputStream(entry).use { it.readBytes() }
            val actual = packed.getInputStream(kept).use { it.readBytes() }
            check(expected.contentEquals(actual)) { "Changed service: ${entry.name}" }
        }
    }
}
