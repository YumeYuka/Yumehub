package moe.yumeyuka.markdown.sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Picture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.net.URI
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.NativeHtmlImageRequest
import moe.yumeyuka.markdown.compose.NativeHtmlPaintMetrics
import moe.yumeyuka.markdown.compose.NativeHtmlPaintMetricsKey
import moe.yumeyuka.markdown.compose.NativeHtmlSlots
import org.json.JSONObject

/** 验收媒体只从夹具 assets 读取，HTML 中的外部地址不会产生网络请求。 */
object AcceptanceMedia {
    fun slots(config: AcceptanceConfig, probe: AcceptanceProbe): NativeHtmlSlots =
        NativeHtmlSlots(
            image = { request, modifier -> FixtureImage(request, modifier, config, probe) },
        )
}

private data class FixtureImageData(
    val width: Int,
    val height: Int,
    val bitmap: Bitmap? = null,
    val picture: Picture? = null,
)

@Composable
private fun FixtureImage(
    request: NativeHtmlImageRequest,
    modifier: Modifier,
    config: AcceptanceConfig,
    probe: AcceptanceProbe,
) {
    val context = LocalContext.current
    val identity = config.identity
    val image by
        produceState<FixtureImageData?>(null, identity, request.url, request.svg) {
            probe.asyncStarted(identity)
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        // SVG 引擎已随依赖移除：验收宿主只加载位图，遇到 SVG 直接报错。
                        require(request.svg == null) { "SVG 渲染已移除" }
                        val uri = URI(request.url)
                        require(uri.scheme == "https" && uri.host == "fixture.invalid")
                        require(uri.userInfo == null && uri.port == -1)
                        val path = uri.path.orEmpty().removePrefix("/")
                        require(path.startsWith("media/") && ".." !in path.split('/'))
                        require(!path.endsWith(".svg")) { "SVG 渲染已移除" }
                        context.assets.open(path).use { stream ->
                            val bitmap = checkNotNull(BitmapFactory.decodeStream(stream))
                            FixtureImageData(bitmap.width, bitmap.height, bitmap = bitmap)
                        }
                    }
                require(loaded.width > 0 && loaded.height > 0)
                value = loaded
                request.onIntrinsicSize?.invoke(loaded.width, loaded.height)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val problem = "Fixture image: ${error.javaClass.simpleName}: ${error.message}"
                request.onError?.invoke(problem)
                probe.fail(identity, problem)
            } finally {
                probe.asyncFinished(identity)
            }
        }
    val loaded = image ?: return
    SideEffect {
        probe.recordImage(
            identity,
            request.nodeKey,
            JSONObject()
                .put("url", request.url)
                .put("alt", request.alt ?: JSONObject.NULL)
                .put("decodedWidthPx", loaded.width)
                .put("decodedHeightPx", loaded.height)
                .put("loaded", true)
                .put("fallbackUsed", false)
                .put("fallbackUrl", request.fallbackUrl ?: JSONObject.NULL),
        )
    }
    var drawnSize by
        remember(identity, request.nodeKey, request.url) { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier) {
        val requestedWidth = request.width ?: loaded.width.toFloat()
        val width = min(requestedWidth, maxWidth.value)
        val height =
            (request.height ?: (requestedWidth * loaded.height / loaded.width)) *
                (width / requestedWidth)
        val fill =
            request.width?.let { it.isFinite() && it > 0f } == true &&
                request.height?.let { it.isFinite() && it > 0f } == true
        val size =
            Modifier.size(width.dp, height.dp)
                .onSizeChanged { drawnSize = it }
                .semantics {
                    if (
                        request.nodeKey.isNotBlank() && drawnSize.width > 0 && drawnSize.height > 0
                    ) {
                        val sx = drawnSize.width.toFloat() / loaded.width
                        val sy = drawnSize.height.toFloat() / loaded.height
                        val paintedWidth =
                            if (fill || loaded.picture != null) drawnSize.width.toFloat()
                            else loaded.width * min(sx, sy)
                        val paintedHeight =
                            if (fill || loaded.picture != null) drawnSize.height.toFloat()
                            else loaded.height * min(sx, sy)
                        val left = (drawnSize.width - paintedWidth) / 2f
                        val top = (drawnSize.height - paintedHeight) / 2f
                        this[NativeHtmlPaintMetricsKey] =
                            NativeHtmlPaintMetrics(
                                request.nodeKey,
                                Rect(left, top, left + paintedWidth, top + paintedHeight),
                                engineWidthPx = loaded.width.toFloat(),
                                engineHeightPx = loaded.height.toFloat(),
                            )
                    }
                }
        loaded.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = request.alt,
                modifier = size,
                filterQuality = FilterQuality.High,
                contentScale = if (fill) ContentScale.FillBounds else ContentScale.Fit,
            )
        }
        loaded.picture?.let { picture ->
            Canvas(size) {
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                try {
                    canvas.scale(this.size.width / loaded.width, this.size.height / loaded.height)
                    canvas.drawPicture(picture)
                } finally {
                    canvas.restoreToCount(saved)
                }
            }
        }
    }
}
