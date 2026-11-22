package moe.yumeyuka.yumehub.feature.markdown

import android.content.Context
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.cache.CacheKeyMapper
import com.github.panpf.sketch.request.ImageRequest

/** 三层缓存均绑定当前正文资源作用域，同时保留 Sketch 默认的尺寸、变换等键。 */
internal fun nativeMarkdownImageRequest(
    context: Context,
    url: String,
    resourceNamespace: String,
    displaySize: IntSize = IntSize.Zero,
): ImageRequest {
    val mapper = NativeMarkdownImageCacheKeyMapper(resourceNamespace)
    return ImageRequest.Builder(context, url)
        .downloadCacheKeyMapper(mapper)
        .resultCacheKeyMapper(mapper)
        .memoryCacheKeyMapper(mapper)
        .apply {
            // AsyncImage 默认只解析首次占位尺寸；正文取得固有尺寸后必须重新按物理像素解码。
            if (displaySize.width > 0 && displaySize.height > 0) {
                size(displaySize.width, displaySize.height)
            }
        }
        .build()
}

private data class NativeMarkdownImageCacheKeyMapper(private val namespace: String) :
    CacheKeyMapper {
    override fun map(key: String): String = "markdown-resource:$namespace:$key"
}
