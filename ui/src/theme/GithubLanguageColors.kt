package moe.yumeyuka.yumehub.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.R
import org.json.JSONObject

/** GitHub Linguist 的语义色；没有定义颜色的语言沿用主题次要颜色。 */
@Composable
fun githubLanguageColor(language: String): Color {
    val context = LocalContext.current.applicationContext
    val colors = remember(context) { GithubLanguagePalette.get(context) }
    return colors[language] ?: GlasmioTheme.colors.secondaryContent
}

/** 语言颜色表是 APK 内不可变资源；每个列表行解析一次会重复分配整张表。 */
private object GithubLanguagePalette {
    @Volatile private var cached: Map<String, Color>? = null

    fun get(context: Context): Map<String, Color> =
        cached
            ?: synchronized(this) {
                cached
                    ?: context.resources
                        .openRawResource(R.raw.github_language_colors)
                        .bufferedReader()
                        .use { reader ->
                            val json = JSONObject(reader.readText())
                            HashMap<String, Color>(json.length()).apply {
                                json.keys().forEach { name ->
                                    val argb =
                                        android.graphics.Color.parseColor(json.getString(name))
                                    put(name, Color(argb))
                                }
                            }
                        }
                        .also { cached = it }
            }
}
