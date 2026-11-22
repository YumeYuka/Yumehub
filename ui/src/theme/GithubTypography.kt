package moe.yumeyuka.yumehub.ui.theme

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.neoruaa.glasmio.core.glasmioTypography
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.tukaani.xz.XZInputStream

private const val InterDirectory = "fonts/inter-0b58fb37"
private var processTypography: StateFlow<Typography?>? = null

/** 登录页和主界面共享一次加载；非空值即本进程最终使用的字体，不再替换。 */
@Synchronized
public fun githubTypography(context: Context): StateFlow<Typography?> {
    processTypography?.let {
        return it
    }
    val app = context.applicationContext
    return flow {
            val regular = interFile(app, italic = false)
            val italic = interFile(app, italic = true)
            emit(interTypography(regular, italic))
        }
        .catch { failure ->
            if (failure !is IOException && failure !is RuntimeException) throw failure
            Log.w("GithubTypography", "Cannot load bundled Inter fonts", failure)
            emit(glasmioTypography())
        }
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly, null)
        .also { processTypography = it }
}

/** 供 WebView 后台资源请求读取与应用主题相同的 TTF，不暴露缓存路径。 */
public fun openGithubInterFont(context: Context, italic: Boolean): InputStream =
    interFile(context, italic).inputStream()

@Synchronized
private fun interFile(context: Context, italic: Boolean): File {
    val (name, length) = if (italic) "Inter-Italic.ttf" to 906596L else "Inter.ttf" to 876576L
    val path = "$InterDirectory/$name"
    val file = context.cacheDir.resolve(path)
    if (file.length() == length) return file
    val atomic = AtomicFile(file)
    context.assets.open("$path.xz").use { asset ->
        XZInputStream(asset.buffered()).use { input ->
            val output = atomic.startWrite()
            try {
                if (input.copyTo(output) != length) throw IOException("Incomplete font: $name")
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }
    return file
}

private fun interTypography(regular: File, italic: File): Typography =
    glasmioTypography(
        FontFamily(
            (100..900 step 100).flatMap { weight ->
                val axis = FontVariation.Settings(FontVariation.weight(weight))
                listOf(
                    Font(regular, FontWeight(weight), variationSettings = axis),
                    Font(
                        italic,
                        FontWeight(weight),
                        FontStyle.Italic,
                        variationSettings = axis,
                    ),
                )
            },
        ),
    )
