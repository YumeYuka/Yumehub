package dev.yume.packer

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.tukaani.xz.XZInputStream

internal fun verifyPackedPayload(apk: File, original: File) {
    val dexName = Regex("""classes[0-9]*\.dex""")
    val nativeName = Regex("""lib/[^/]+/[^/]+\.so""")
    ZipFile(original).use { raw ->
        ZipFile(apk).use { zip ->
            val dex = raw.entries().asSequence().filter { dexName.matches(it.name) }.toList()
            check(dex.isNotEmpty()) { "Missing application DEX" }
            val expectedDex = dex.map { it.name }.toSet() + "classes${dex.size + 1}.dex"
            val actualDex = zip.entries().asSequence().map { it.name }.filter(dexName::matches).toSet()
            check(actualDex == expectedDex) { "Invalid DEX layout: $actualDex" }
            val profileEntries = raw.entries().asSequence().filter { it.name.startsWith("assets/dexopt/") }
            for (entry in dex.asSequence() + profileEntries) {
                val kept = requireNotNull(zip.getEntry(entry.name)) { "Missing ${entry.name}" }
                check(entry.size == kept.size && entry.crc == kept.crc) { "Changed ${entry.name}" }
            }
            val rawEntries = raw.entries().asSequence().map { it.name }.filter(nativeName::matches).toMutableSet()
            val bootstrap = zip.entries().asSequence().map { it.name }.filter(nativeName::matches).toList()
            check(bootstrap == listOf("lib/arm64-v8a/libloader.so")) { "Invalid native bootstrap: $bootstrap" }
            verifyReleaseMetadata(zip, original)
            verifyNativePayload(zip, rawEntries)
        }
    }
}

private fun verifyNativePayload(zip: ZipFile, rawEntries: MutableSet<String>) {
    val metadata = requireNotNull(zip.getEntry(PayloadFormat.METADATA)) { "Missing loader metadata" }
    DataInputStream(zip.getInputStream(metadata)).use { input ->
        check(input.readInt() == PayloadFormat.MAGIC && input.readInt() == PayloadFormat.VERSION) {
            "Unsupported payload format"
        }
        check(input.readUTF().length == 64)
        check(input.readUTF() == APP)
        check(input.readUTF() == FACTORY)
        val nativeCount = input.readInt()
        check(nativeCount in 1..zip.size())
        repeat(nativeCount) {
            check(input.readUTF() == "arm64-v8a") { "Unexpected native ABI" }
            val path = input.readUTF()
            val name = input.readUTF()
            check(!name.contains('/') && !name.contains('\\') && path.startsWith("${PayloadFormat.ROOT}/"))
            val rawName = "lib/arm64-v8a/$name"
            check(rawEntries.remove(rawName) || rawEntries.remove(rawName.removeSuffix(".so") + ".xz.so")) {
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
        check(input.read() == -1) { "Trailing loader metadata" }
        check(rawEntries.isEmpty()) { "Missing application payload: $rawEntries" }
    }
}
