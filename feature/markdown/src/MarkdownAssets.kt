package moe.yumeyuka.yumehub.feature.markdown

import android.content.res.AssetManager
import java.io.InputStream
import moe.yumeyuka.yumehub.ui.component.TextAssetArchive

private val markdownArchive = TextAssetArchive("github-markdown.xz")

internal fun AssetManager.openMarkdownAsset(name: String): InputStream =
    markdownArchive.open(this, "github-markdown/webview/$name")

/** 只缓存 APK 中不变的文本；调用方在 IO dispatcher 预热，不保留 AssetManager/Context。 */
internal class MarkdownAssets private constructor(val installAdapterScript: String) {
    companion object {
        @Volatile private var cached: MarkdownAssets? = null

        fun cachedOrNull(): MarkdownAssets? = cached

        @Synchronized
        fun load(assets: AssetManager): MarkdownAssets {
            cached?.let {
                return it
            }
            val adapter =
                assets.openMarkdownAsset("android-adapter.js").bufferedReader().use {
                    it.readText()
                }
            return MarkdownAssets(
                    "(function(){try{\n$adapter\nreturn true;}catch(_){return false;}})();",
                )
                .also { cached = it }
        }
    }
}
