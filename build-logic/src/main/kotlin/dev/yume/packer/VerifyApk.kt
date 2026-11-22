package dev.yume.packer

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.tukaani.xz.XZInputStream

internal fun verifyPackedPayload(apk: File, original: File) {
    val rawEntries = ZipFile(original).use { raw ->
        raw.entries().asSequence().map { it.name }.filter {
            it.matches(Regex("""classes[0-9]*\.dex""")) || it.matches(Regex("""lib/[^/]+/[^/]+\.so"""))
        }.toMutableSet()
    }
    ZipFile(apk).use { zip ->
        verifyReleaseMetadata(zip, original)
        val bootstrap = zip.entries().asSequence().map { it.name }.filter {
            it.matches(Regex("""classes[0-9]*\.dex""")) || it.matches(Regex("""lib/[^/]+/[^/]+\.so"""))
        }.toList()
        check(bootstrap.sorted() == listOf("classes.dex", "lib/arm64-v8a/libloader.so")) {
            "Invalid bootstrap layout: $bootstrap"
        }
        val metadata = requireNotNull(zip.getEntry(PayloadFormat.METADATA)) { "Missing loader metadata" }
        DataInputStream(zip.getInputStream(metadata)).use { input ->
            check(input.readInt() == PayloadFormat.MAGIC && input.readInt() == PayloadFormat.VERSION) {
                "Unsupported payload format"
            }
            check(input.readUTF().length == 64)
            check(input.readUTF() == APP)
            check(input.readUTF() == FACTORY)
            fun verifyEntry(native: Boolean) {
                if (native) check(input.readUTF() == "arm64-v8a") { "Unexpected native ABI" }
                val path = input.readUTF()
                val name = input.readUTF()
                check(!name.contains('/') && !name.contains('\\') && path.startsWith("${PayloadFormat.ROOT}/"))
                val rawName = if (native) "lib/arm64-v8a/$name" else name
                check(rawEntries.remove(rawName) ||
                    (native && rawEntries.remove(rawName.removeSuffix(".so") + ".xz.so"))) {
                    "Unexpected or duplicate payload: $rawName"
                }
                val size = input.readLong()
                val expected = input.readNBytes(32)
                val entry = requireNotNull(zip.getEntry(path)) { "Missing payload $path" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                XZInputStream(zip.getInputStream(entry)).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        count += read
                        check(count <= size) { "Expanded payload exceeds declared length: $path" }
                        digest.update(buffer, 0, read)
                    }
                }
                check(count == size && digest.digest().contentEquals(expected)) { "Corrupt payload $path" }
            }
            val dexCount = input.readInt()
            check(dexCount in 1..zip.size())
            repeat(dexCount) { verifyEntry(false) }
            val nativeCount = input.readInt()
            check(nativeCount in 1..zip.size())
            repeat(nativeCount) { verifyEntry(true) }
            check(input.read() == -1) { "Trailing loader metadata" }
            check(rawEntries.isEmpty()) { "Missing application payload: $rawEntries" }
        }
    }
}
