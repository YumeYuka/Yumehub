package moe.yumeyuka.yumehub.feature.profile

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** GitHub 完整表情集中每个分组的条目，数据来自官方 gemoji 数据库。 */
internal data class StatusEmojiCategory(val category: String, val emojis: List<String>)

/** 别名资源来自 github/gemoji 的 db/emoji.json，运行时只读取本地文件。 */
private object StatusEmojiAliases {
    @Volatile private var cached: Map<String, String>? = null

    @Synchronized
    fun load(context: Context): Map<String, String> =
        cached
            ?: context.resources.openRawResource(R.raw.status_emoji_aliases).use { input ->
                Json.parseToJsonElement(input.bufferedReader(Charsets.UTF_8).readText())
                    .jsonObject
                    .mapValues { (_, value) -> value.jsonPrimitive.content }
                    .also { cached = it }
            }
}

/** GitHub 状态返回的 shortcode 只用于协议，不直接作为可见文字。 */
internal fun resolveStatusEmoji(value: String, aliases: Map<String, String>): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith(':') && trimmed.endsWith(':')) {
        aliases[trimmed.removeSurrounding(":")].orEmpty()
    } else trimmed
}

@Composable
internal fun rememberStatusEmoji(value: String): State<String> {
    val context = LocalContext.current.applicationContext
    val initial = resolveStatusEmoji(value, emptyMap())
    // 切换表情时重新建立展示状态，避免短暂沿用上一个状态的表情。
    return key(value, context) {
        produceState(initial) {
            if (value.trim().startsWith(':') && value.trim().endsWith(':')) {
                this.value =
                    try {
                        withContext(Dispatchers.IO) {
                            resolveStatusEmoji(value, StatusEmojiAliases.load(context))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("StatusEmoji", "Unable to load bundled emoji aliases", error)
                        ""
                    }
            }
        }
    }
}

internal fun loadStatusEmojiCategories(context: Context): List<StatusEmojiCategory> =
    context.resources.openRawResource(R.raw.status_emojis).use { input ->
        parseStatusEmojiCategories(input.bufferedReader(Charsets.UTF_8).readText())
    }

/** 使用 JSON 树解析打包资源，不依赖 feature 模块未启用的序列化编译器插件。 */
internal fun parseStatusEmojiCategories(source: String): List<StatusEmojiCategory> {
    val categories =
        Json.parseToJsonElement(source).jsonArray.map { element ->
            val entry = element.jsonObject
            val category = entry.getValue("category").jsonPrimitive
            require(category.isString && category.content.isNotBlank()) {
                "Invalid status emoji category"
            }
            val emojis =
                entry.getValue("emojis").jsonArray.map { value ->
                    val emoji = value.jsonPrimitive
                    require(emoji.isString && emoji.content.isNotBlank()) {
                        "Invalid status emoji value"
                    }
                    emoji.content
                }
            require(emojis.isNotEmpty()) { "Empty status emoji category" }
            StatusEmojiCategory(category.content, emojis)
        }
    require(categories.isNotEmpty()) { "Empty status emoji catalog" }
    return categories
}
