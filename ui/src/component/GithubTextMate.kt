package moe.yumeyuka.yumehub.ui.component

import android.content.Context
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.Color
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes
import org.eclipse.tm4e.core.internal.oniguruma.impl.onig.NativeOnigConfig
import org.eclipse.tm4e.core.internal.theme.raw.IRawTheme
import org.eclipse.tm4e.core.internal.theme.raw.RawThemeReader
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/** UTF-16 范围 [start, end)。样式位：斜体 1、粗体 2、下划线 4、删除线 8。 */
public data class GithubTextMateSpan(
    val start: Int,
    val end: Int,
    val color: Color,
    val fontStyleFlags: Int = 0,
)

/** 使用 Cursor 主题生成语法高亮。不支持的语言返回空列表；缓存预算不截断全文。 */
@WorkerThread
public fun highlightGithubTextMate(
    context: Context,
    source: String,
    language: String?,
    isDark: Boolean,
    isActive: () -> Boolean = { true },
): List<GithubTextMateSpan> {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "TextMate highlighting requires a worker thread"
    }
    fun checkActive() {
        if (!isActive() || Thread.currentThread().isInterrupted) throw CancellationException()
    }
    checkActive()
    if (source.isEmpty()) return emptyList()
    val key = GithubTextMateCacheKey(language, isDark, source)
    GithubTextMateCache.get(key)?.let {
        checkActive()
        return it
    }
    val spans = GithubTextMateHighlighter(context).highlight(source, language, isDark, isActive)
    checkActive()
    if (spans.isNotEmpty()) GithubTextMateCache.put(key, spans)
    return spans
}

/**
 * 每个编辑 session 持有一个实例，构造不读取资源。highlight 只能在 worker 串行调用；可在不同 worker 间交接，但重叠调用会失败。
 * 取消后保留上次完整快照，不发布半成品。
 *
 * isActive 通常传 { coroutineContext.isActive }。单次原生 tokenizeLine2 调用本身无法中断，返回后立即检查。
 */
internal class GithubTextMateHighlighter(context: Context) {
    private val applicationContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var engine: TextMateEngine? = null
    private var snapshot: TextMateSnapshot? = null

    @WorkerThread
    public fun highlight(
        source: String,
        language: String?,
        isDark: Boolean,
        isActive: () -> Boolean = { true },
    ): List<GithubTextMateSpan> {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "TextMate highlighting requires a worker thread"
        }
        check(running.compareAndSet(false, true)) { "Serialize highlighting within each session" }
        fun checkActive() {
            if (!isActive() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        try {
            checkActive()
            if (source.isEmpty()) {
                snapshot = null
                return emptyList()
            }
            val definition = GithubGrammarIndex.forHint(language)
            checkActive()
            if (definition == null) {
                engine = null
                snapshot = null
                return emptyList()
            }
            val existing = engine
            val current =
                existing?.takeIf { it.definition == definition && it.isDark == isDark }
                    ?: TextMateEngine(applicationContext, definition, isDark).also { checkActive() }
            val previous = if (current === existing) snapshot else null
            if (previous?.source == source) {
                checkActive()
                return previous.spans
            }
            val inputs = textMateLines(source, ::checkActive)
            val oldLines = previous?.lines.orEmpty()
            var prefix = 0
            while (prefix < minOf(inputs.size, oldLines.size)) {
                checkActive()
                if (inputs[prefix] != oldLines[prefix].input) break
                prefix++
            }
            var suffix = 0
            while (suffix < minOf(inputs.size, oldLines.size) - prefix) {
                checkActive()
                if (
                    inputs[inputs.lastIndex - suffix] != oldLines[oldLines.lastIndex - suffix].input
                )
                    break
                suffix++
            }
            val lines = ArrayList<TextMateLine>(inputs.size)
            val spans = ArrayList<GithubTextMateSpan>()
            var state: IStateStack? = null
            var offset = 0
            for (index in inputs.indices) {
                checkActive()
                val input = inputs[index]
                val oldIndex = index - (inputs.size - oldLines.size)
                val cached =
                    when {
                        index < prefix -> oldLines[index]
                        index >= inputs.size - suffix -> oldLines[oldIndex]
                        else -> null
                    }
                // 必须比较完整状态栈，不能仅比较深度；状态尚未收敛时继续逐行分词。
                val line =
                    if (cached != null && cached.before == state) cached
                    else {
                        current.tokenize(input, state, ::checkActive)
                    }
                lines += line
                state = line.after
                for (span in line.spans) {
                    checkActive()
                    appendTextMateSpan(
                        spans,
                        span.copy(start = offset + span.start, end = offset + span.end),
                    )
                }
                offset += input.text.length
            }
            val result = Collections.unmodifiableList(spans)
            checkActive()
            engine = current
            snapshot = TextMateSnapshot(source, lines, result)
            return result
        } finally {
            running.set(false)
        }
    }
}

private data class TextMateInput(val text: String, val contentLength: Int)

private data class TextMateLine(
    val input: TextMateInput,
    val before: IStateStack?,
    val after: IStateStack,
    val spans: List<GithubTextMateSpan>,
)

private data class TextMateSnapshot(
    val source: String,
    val lines: List<TextMateLine>,
    val spans: List<GithubTextMateSpan>,
)

/** 行文本包括原始换行符；换行不生成 span，也不传给 grammar，保持旧接口语义。 */
private fun textMateLines(source: String, checkActive: () -> Unit): List<TextMateInput> {
    val lines = ArrayList<TextMateInput>()
    var offset = 0
    while (offset < source.length) {
        checkActive()
        var end = offset
        while (end < source.length && source[end] != '\n' && source[end] != '\r') {
            if ((end - offset) % 4096 == 0) checkActive()
            end++
        }
        val contentLength = end - offset
        val next =
            if (end == source.length) end
            else {
                end + if (source[end] == '\r' && source.getOrNull(end + 1) == '\n') 2 else 1
            }
        lines += TextMateInput(source.substring(offset, next), contentLength)
        offset = next
    }
    return lines
}

private fun appendTextMateSpan(spans: ArrayList<GithubTextMateSpan>, span: GithubTextMateSpan) {
    val last = spans.lastOrNull()
    if (
        last != null &&
            last.end == span.start &&
            last.color == span.color &&
            last.fontStyleFlags == span.fontStyleFlags
    ) {
        spans[spans.lastIndex] = last.copy(end = span.end)
    } else {
        spans += span
    }
}

/** Registry、grammar 和已解析颜色只归当前 session 使用，不跨 worker 并发共享。 */
private class TextMateEngine(
    context: Context,
    val definition: GithubGrammar,
    val isDark: Boolean,
) {
    private val assets = GithubTextMateAssets.get(context)
    private val theme = assets.theme(isDark)
    private val registry =
        Registry(
            object : IRegistryOptions {
                override fun getTheme(): IRawTheme = this@TextMateEngine.theme

                override fun getGrammarSource(scopeName: String): IGrammarSource? =
                    GithubGrammarIndex.forScope(scopeName)?.let { assets.grammar(it.path) }
            },
        )
    private val grammar: IGrammar = requireNotNull(registry.loadGrammar(definition.scope))
    private val foregroundFallback = themeForeground(theme)
    private val colors = registry.colorMap.map { parseTextMateColor(it, foregroundFallback) }

    fun tokenize(
        input: TextMateInput,
        state: IStateStack?,
        checkActive: () -> Unit,
    ): TextMateLine {
        val line = input.text.substring(0, input.contentLength)
        // tm4e 的 token 偏移以 Unicode code point 为单位，转换回 Compose 的 UTF-16。
        val utf16Offsets =
            if (line.any { Character.isSurrogate(it) }) {
                val count = line.codePointCount(0, line.length)
                IntArray(count + 1).also { offsets ->
                    var utf16 = 0
                    for (index in 0 until count) {
                        if (index % 4096 == 0) checkActive()
                        offsets[index] = utf16
                        utf16 += Character.charCount(line.codePointAt(utf16))
                    }
                    offsets[count] = line.length
                }
            } else null
        fun utf16(index: Int): Int =
            utf16Offsets?.get(index.coerceIn(0, utf16Offsets.lastIndex))
                ?: index.coerceIn(0, line.length)
        checkActive()
        val result = grammar.tokenizeLine2(line, state, null)
        checkActive()
        val spans = ArrayList<GithubTextMateSpan>()
        val tokens = result.tokens
        for (index in tokens.indices step 2) {
            if (index % 512 == 0) checkActive()
            val start = utf16(tokens[index])
            val stop =
                if (index + 2 < tokens.size) {
                    utf16(tokens[index + 2]).coerceAtLeast(start)
                } else line.length
            if (stop <= start) continue
            val metadata = tokens[index + 1]
            appendTextMateSpan(
                spans,
                GithubTextMateSpan(
                    start,
                    stop,
                    colors.getOrElse(EncodedTokenAttributes.getForeground(metadata)) {
                        foregroundFallback
                    },
                    EncodedTokenAttributes.getFontStyle(metadata),
                ),
            )
        }
        return TextMateLine(input, state, result.ruleStack, spans)
    }
}

private data class GithubTextMateCacheKey(
    val language: String?,
    val isDark: Boolean,
    val text: String,
)

/** 只保留完整结果；超预算的大文件仍完整解析，但不进入缓存。 */
private object GithubTextMateCache {
    private const val MaxEntries = 8
    private const val MaxEstimatedBytes = 8L * 1024 * 1024
    private val entries =
        LinkedHashMap<GithubTextMateCacheKey, List<GithubTextMateSpan>>(8, 0.75f, true)
    private var estimatedBytes = 0L

    // 包括文本、span、颜色和集合引用的保守估算，不等同于精确 JVM retained size。
    private fun bytes(key: GithubTextMateCacheKey, spans: List<GithubTextMateSpan>): Long =
        128L + key.text.length * 2L + (key.language?.length ?: 0) * 2L + spans.size * 96L

    @Synchronized fun get(key: GithubTextMateCacheKey): List<GithubTextMateSpan>? = entries[key]

    @Synchronized
    fun put(key: GithubTextMateCacheKey, spans: List<GithubTextMateSpan>) {
        val size = bytes(key, spans)
        if (size > MaxEstimatedBytes) return
        entries.remove(key)?.let { estimatedBytes -= bytes(key, it) }
        // 缓存拥有自己的只读快照，不受外部调用方修改返回列表影响。
        entries[key] = Collections.unmodifiableList(ArrayList(spans))
        estimatedBytes += size
        val iterator = entries.iterator()
        while (entries.size > MaxEntries || estimatedBytes > MaxEstimatedBytes) {
            val entry = iterator.next()
            estimatedBytes -= bytes(entry.key, entry.value)
            iterator.remove()
        }
    }
}

private class GithubTextMateAssets(context: Context) {
    private val assets = context.applicationContext.assets
    private val archive = GithubTextAssetArchive("textmate.xz")

    init {
        NativeOnigConfig.setSearchInBatch(false)
    }

    fun grammar(path: String): IGrammarSource =
        IGrammarSource.fromInputStream(archive.open(assets, path), path, null)

    fun theme(isDark: Boolean): IRawTheme {
        val name = if (isDark) "cursor-dark" else "cursor-light-colorblind"
        val path = "textmate/$name.json"
        return RawThemeReader.readTheme(
            IThemeSource.fromInputStream(archive.open(assets, path), path, null),
        )
    }

    companion object {
        @Volatile private var instance: GithubTextMateAssets? = null

        fun get(context: Context): GithubTextMateAssets =
            instance
                ?: synchronized(this) {
                    instance
                        ?: GithubTextMateAssets(context.applicationContext).also { instance = it }
                }
    }
}

/** TextMate 用 #RRGGBBAA；Android 的八位色格式为 #AARRGGBB。 */
private fun parseTextMateColor(value: String?, fallback: Color): Color {
    val hex = value?.removePrefix("#") ?: return fallback
    if (hex.length != 6 && hex.length != 8) return fallback
    val argb = if (hex.length == 8) hex.takeLast(2) + hex.dropLast(2) else hex
    return try {
        Color(android.graphics.Color.parseColor("#$argb"))
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

private fun themeForeground(theme: IRawTheme): Color {
    val colors = (theme as? Map<*, *>)?.get("colors") as? Map<*, *>
    return parseTextMateColor(colors?.get("editor.foreground") as? String, Color.Unspecified)
}
