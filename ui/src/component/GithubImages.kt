package moe.yumeyuka.yumehub.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.decode.supportSvg
import com.github.panpf.sketch.fetch.supportOkHttpHttpUri
import com.github.panpf.sketch.http.OkHttpStack
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.util.Logger
import com.github.panpf.sketch.util.asComposeImageBitmap
import okhttp3.OkHttpClient

/** 全局图片加载器：复用应用 OkHttp，内存与磁盘缓存、尺寸解码交给 Sketch。 */
fun initializeGithubImageLoader(context: Context, client: OkHttpClient) {
    SingletonSketch.setSafe { appContext ->
        Sketch(appContext) {
            components {
                supportOkHttpHttpUri(OkHttpStack(client))
                // shields.io 等 README 徽标以 SVG 返回，交给 Sketch 原生解码。
                supportSvg()
            }
            logger(Logger.Level.Warn)
        }
    }
}

/** 远程位图走统一 Sketch 缓存；页面只需要图片地址和显示尺寸。 */
@Composable
fun GithubRemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        uri = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/** 供必须拿到 ImageBitmap 的旧接口使用；加载过程同样走 Sketch 缓存。 */
suspend fun loadGithubImageBitmap(context: Context, url: String): ImageBitmap? {
    val request = ImageRequest.Builder(context, url).build()
    val result = SingletonSketch.get(context).execute(request)
    val image = (result as? ImageResult.Success)?.image as? BitmapImage ?: return null
    return image.bitmap.asComposeImageBitmap()
}
