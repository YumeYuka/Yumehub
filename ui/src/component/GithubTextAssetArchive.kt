package moe.yumeyuka.yumehub.ui.component

import android.content.res.AssetManager
import java.io.InputStream
import java.util.WeakHashMap
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

/** 只在首次使用时解压整包。各调用方取得独立的流，不重复扫描或解析其他语法。 */
public class GithubTextAssetArchive(private val name: String) {
    private val cache = WeakHashMap<AssetManager, Map<String, ByteArray>>()

    @Synchronized
    public fun open(assets: AssetManager, path: String): InputStream {
        val entries =
            cache.getOrPut(assets) {
                assets.open(name).use { input ->
                    ZipInputStream(XZInputStream(input).buffered()).use { zip ->
                        buildMap {
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (!entry.isDirectory) put(entry.name, zip.readBytes())
                            }
                        }
                    }
                }
            }
        return checkNotNull(entries[path]) { "Missing $path in $name" }.inputStream()
    }
}
