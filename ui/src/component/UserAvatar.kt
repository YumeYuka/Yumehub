package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.asPainterOrNull
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.util.Size
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.LocalAppStrings

@Composable
fun UserAvatar(
    url: String?,
    login: String?,
    type: String? = null,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val shape = avatarShape(type, size)
    val context = LocalContext.current.applicationContext
    val sketch = remember(context) { SingletonSketch.get(context) }
    val request =
        remember(context, sketch, url) {
            url?.takeIf { it.isNotBlank() }
                ?.let {
                    ImageRequest.Builder(context, it)
                        .size(Size.Origin)
                        .defaultOptions(sketch.globalImageOptions)
                        .build()
                }
        }
    // 复用 Sketch 的原有缓存键；已解码头像直接进入首帧，不再先画兜底图标。
    val cachedPainter =
        remember(sketch, request) {
            request?.let {
                val key = RequestContext(sketch, it, Size.Origin).memoryCacheKey
                sketch.memoryCache[key]?.image?.asPainterOrNull(FilterQuality.High)
            }
        }
    val description = login?.let { LocalAppStrings.current.Common.UserAvatarDescription(it) }
    Box(
        modifier.size(size).clip(shape).background(GlasmioTheme.colors.groupedBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (cachedPainter != null) {
            Image(
                painter = cachedPainter,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            if (placeholder != null) placeholder() else AvatarFallback(type, size)
            if (request != null) {
                AsyncImage(
                    request = request,
                    contentDescription = description,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

/** 头像与骨架共用轮廓，组织圆角随头像尺寸缩放。 */
fun avatarShape(type: String?, size: Dp): Shape =
    if (type == "Organization") RoundedCornerShape(size * 0.22f) else CircleShape

@Composable
private fun AvatarFallback(type: String?, size: Dp) {
    AppIcon(
        if (type == "Organization") Lucide.Building2 else Lucide.User,
        contentDescription = null,
        size = size * 0.55f,
    )
}
