package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import moe.yumeyuka.yumehub.ui.component.TextMateSpan

/** Worker 线程生成的完整语法快照；仅 source 匹配当前正文时才能查询全局 UTF-16 偏移。 */
public data class GlasmioCodeSyntax(
    val source: String,
    val spans: List<TextMateSpan>,
)

/** 主线程接入时可使用的短名称。 */
public typealias EditorSyntax = GlasmioCodeSyntax

/**
 * 只绘制可见代码（包括尚未着色的纯文本），不参与 BasicTextField 的测量、输入、选区或 IME 映射。
 *
 * layout 必须是未着色的当前正文布局。viewport 和 textOrigin 使用 DrawScope 坐标； syntax
 * 可能暂时落后于正文，此时只复用文本完全相同的已绘制行样式，不使用旧全局偏移。 滚动只改变 viewport，不会使行布局缓存失效。
 */
public class GlasmioCodeSyntaxPainter(
    private val textMeasurer: TextMeasurer,
    private val baseTextStyle: TextStyle,
    maxCacheEntries: Int = 256,
) {
    private val cacheLimit = maxCacheEntries.coerceAtLeast(1)

    private data class LineKey(
        val start: Int,
        val end: Int,
        val density: Float,
        val fontScale: Float,
        val direction: TextDirection,
        val underline: IntRange?,
    )

    private val lineCache =
        object : LinkedHashMap<LineKey, TextLayoutResult>(cacheLimit, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<LineKey, TextLayoutResult>?,
            ): Boolean = size > cacheLimit
        }

    private val sliceableLines =
        object : LinkedHashMap<Int, Boolean>(cacheLimit, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, Boolean>?,
            ): Boolean = size > cacheLimit
        }

    private var cachedSource: String? = null
    private var cachedSyntax: GlasmioCodeSyntax? = null
    private var cachedLayout: TextLayoutResult? = null
    private val retainedStyles = CodeLineStyleCache(cacheLimit)

    /** 替代 nativeInner 的文字绘制；不得再 drawContent() 覆盖颜色并遍历全文。 */
    public fun draw(
        scope: DrawScope,
        layout: TextLayoutResult,
        syntax: GlasmioCodeSyntax?,
        source: String,
        viewportTop: Float,
        viewportBottom: Float,
        viewportLeft: Float,
        viewportRight: Float,
        textOrigin: Offset = Offset.Zero,
        composition: TextRange? = null,
        searchHighlights: CodeSearchHighlights? = null,
        searchColor: Color = Color.Unspecified,
        searchBorderColor: Color = Color.Unspecified,
    ) {
        if (
            source.isEmpty() ||
                layout.lineCount == 0 ||
                viewportBottom <= viewportTop ||
                viewportRight <= viewportLeft
        )
            return
        if (cachedSource !== source || cachedSyntax !== syntax || cachedLayout !== layout) {
            lineCache.clear()
            sliceableLines.clear()
            cachedSource = source
            cachedSyntax = syntax
            cachedLayout = layout
        }

        val validSyntax = syntax?.takeIf { it.source == source }
        val layoutTop = viewportTop - textOrigin.y
        val layoutBottom = viewportBottom - textOrigin.y
        val firstLine = layout.getLineForVerticalPosition(layoutTop.coerceAtLeast(0f))
        val lastLine =
            layout
                .getLineForVerticalPosition((layoutBottom - 0.5f).coerceAtLeast(layoutTop))
                .coerceAtMost(layout.lineCount - 1)

        for (line in firstLine..lastLine) {
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            if (lineStart >= lineEnd) continue

            val range =
                visibleRange(
                    source,
                    layout,
                    line,
                    lineStart,
                    lineEnd,
                    viewportLeft - textOrigin.x,
                    viewportRight - textOrigin.x,
                )
            val start = range.first
            val end = range.second
            if (start >= end) continue

            val direction =
                when (layout.getParagraphDirection(start)) {
                    ResolvedTextDirection.Rtl -> TextDirection.Rtl
                    ResolvedTextDirection.Ltr -> TextDirection.Ltr
                }
            val underline = composition?.let { range ->
                val compositionStart = minOf(range.start, range.end).coerceAtLeast(start)
                val compositionEnd = maxOf(range.start, range.end).coerceAtMost(end)
                if (compositionStart < compositionEnd) {
                    (compositionStart - start)..(compositionEnd - start)
                } else null
            }
            val key =
                LineKey(
                    start = start,
                    end = end,
                    density = scope.density,
                    fontScale = scope.fontScale,
                    direction = direction,
                    underline = underline,
                )
            val localLayout =
                lineCache[key]
                    ?: run {
                        val lineText = source.substring(start, end)
                        val localSpans =
                            retainedStyles.resolve(
                                lineText,
                                validSyntax?.let { current ->
                                    visibleCodeSpans(current.spans, start, end).map {
                                        it.copy(start = it.start - start, end = it.end - start)
                                    }
                                },
                            )
                        measureLine(lineText, localSpans, direction, underline).also {
                            lineCache[key] = it
                        }
                    }

            val baseline = layout.getLineBaseline(line) + textOrigin.y - localLayout.firstBaseline
            val x =
                if (start == lineStart) {
                    layout.getLineLeft(line) + textOrigin.x - localLayout.getLineLeft(0)
                } else {
                    layout.getCursorRect(start).left + textOrigin.x - localLayout.getLineLeft(0)
                }
            // 使用原生文字布局的范围路径，横滚、软换行、字号与 Unicode 共用同一套坐标。
            val search = searchHighlights?.takeIf { it.result.source == source }
            if (search != null) {
                val matches = search.result.matches
                for (index in visibleCodeSearchMatches(matches, start, end)) {
                    val match = matches[index]
                    val path =
                        layout.getPathForRange(maxOf(start, match.start), minOf(end, match.end))
                    val current = index == search.currentIndex
                    with(scope) {
                        translate(textOrigin.x, textOrigin.y) {
                            val bounds = path.getBounds()
                            val radius = CornerRadius(3.dp.toPx())
                            drawRoundRect(
                                searchColor.copy(alpha = if (current) 0.32f else 0.16f),
                                topLeft = bounds.topLeft,
                                size = bounds.size,
                                cornerRadius = radius,
                            )
                            drawRoundRect(
                                (if (current) searchBorderColor else searchColor).copy(
                                    alpha = if (current) 0.85f else 0.55f,
                                ),
                                topLeft = bounds.topLeft,
                                size = bounds.size,
                                cornerRadius = radius,
                                style = Stroke(width = density),
                            )
                        }
                    }
                }
            }
            scope.drawText(localLayout, topLeft = Offset(x, baseline))
        }
    }

    private fun measureLine(
        text: String,
        spans: List<TextMateSpan>,
        direction: TextDirection,
        underline: IntRange?,
    ): TextLayoutResult {
        val annotated =
            AnnotatedString.Builder(text)
                .apply {
                    for (span in spans) {
                        addStyle(
                            SpanStyle(color = span.color),
                            span.start.coerceAtLeast(0),
                            span.end.coerceAtMost(text.length),
                        )
                    }
                    underline?.let {
                        addStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline),
                            it.first,
                            it.last,
                        )
                    }
                }
                .toAnnotatedString()
        return textMeasurer.measure(
            text = annotated,
            style = baseTextStyle.copy(textDirection = direction),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
    }

    private fun visibleRange(
        source: String,
        layout: TextLayoutResult,
        line: Int,
        lineStart: Int,
        lineEnd: Int,
        left: Float,
        right: Float,
    ): Pair<Int, Int> {
        if (lineEnd - lineStart <= LONG_LINE_LIMIT || right <= left) return lineStart to lineEnd
        val sliceable =
            sliceableLines[lineStart]
                ?: canWindowCodeLine(source, lineStart, lineEnd).also {
                    sliceableLines[lineStart] = it
                }
        // Tab 停靠点、双向文本及字形簇依赖完整行上下文，不能对子串重新排版。
        if (!sliceable) return lineStart to lineEnd
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        fun offsetAt(x: Float): Int =
            layout.getOffsetForPosition(Offset(x, y)).coerceIn(lineStart, lineEnd)
        val a = offsetAt(left - HORIZONTAL_PADDING)
        val b = offsetAt(right + HORIZONTAL_PADDING)
        return (minOf(a, b) - UTF16_PADDING).coerceAtLeast(lineStart) to
            (maxOf(a, b) + UTF16_PADDING).coerceAtMost(lineEnd)
    }

    private companion object {
        const val LONG_LINE_LIMIT = 4096
        const val HORIZONTAL_PADDING = 16f
        const val UTF16_PADDING = 16
    }
}

/** 等待新语法快照时仅复用文本完全相同的行；新结果即使为空也必须覆盖旧样式。 */
internal class CodeLineStyleCache(private val limit: Int) {
    init {
        require(limit > 0)
    }

    private val entries =
        object : LinkedHashMap<String, List<TextMateSpan>>(limit, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<TextMateSpan>>?,
            ): Boolean = size > limit
        }

    fun resolve(
        text: String,
        fresh: List<TextMateSpan>?,
    ): List<TextMateSpan> {
        if (fresh != null) {
            entries[text] = fresh
            return fresh
        }
        return entries[text].orEmpty()
    }
}

internal fun canWindowCodeLine(source: String, start: Int, end: Int): Boolean {
    for (index in start until end) {
        if (source[index] !in ' '..'~') return false
    }
    return true
}

internal fun visibleCodeSpans(
    spans: List<TextMateSpan>?,
    start: Int,
    end: Int,
): List<TextMateSpan> {
    if (spans.isNullOrEmpty() || start >= end) return emptyList()
    var low = 0
    var high = spans.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (spans[middle].start < start) low = middle + 1 else high = middle
    }
    var index = (low - 1).coerceAtLeast(0)
    while (index > 0 && spans[index - 1].end > start) index--
    val result = ArrayList<TextMateSpan>()
    while (index < spans.size) {
        val span = spans[index]
        if (span.start >= end) break
        if (span.end > start && span.start < end) {
            result += span.copy(start = maxOf(span.start, start), end = minOf(span.end, end))
        }
        index++
    }
    return result
}

@Composable
public fun rememberGlasmioCodeSyntaxPainter(
    baseTextStyle: TextStyle,
    maxCacheEntries: Int = 256,
): GlasmioCodeSyntaxPainter {
    val textMeasurer = rememberTextMeasurer()
    return remember(textMeasurer, baseTextStyle, maxCacheEntries) {
        GlasmioCodeSyntaxPainter(textMeasurer, baseTextStyle, maxCacheEntries)
    }
}
