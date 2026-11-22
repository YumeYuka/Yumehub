package moe.yumeyuka.yumehub.ui.component

import android.content.res.AssetManager
import java.io.DataInputStream
import java.io.InputStream
import java.util.WeakHashMap
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

/** 只在首次使用时解压整包。各调用方取得独立的流，不重复扫描或解析其他语法。 */
public class GithubTextAssetArchive(private val name: String) {
    private val cache = WeakHashMap<AssetManager, Map<String, ByteArray>>()

    @Synchronized
    public fun open(assets: AssetManager, path: String): InputStream {
        val entries = cache.getOrPut(assets) { assets.open(name).use(::readPackedTextAssets) }
        return checkNotNull(entries[path]) { "Missing $path in $name" }.inputStream()
    }
}

internal fun readPackedTextAssets(input: InputStream): Map<String, ByteArray> =
    ZipInputStream(XZInputStream(input).buffered()).use { zip ->
        val data = DataInputStream(zip)
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // PackTextAssets 使用 STORED，条目长度在本地头中，不需要扩容或复制全文。
                require(entry.size in 0..Int.MAX_VALUE.toLong()) { entry.name }
                put(entry.name, ByteArray(entry.size.toInt()).also(data::readFully))
            }
        }
    }
