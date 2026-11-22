package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class TextLineIndex(val source: String, val starts: IntArray) {
    val lineNumberDigits: Int = starts.size.toString().length

    fun lineAt(offset: Int): Int =
        starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
}

/** 仅需要行号时在后台生成；滚动时只做二分查找。 */
internal suspend fun buildTextLineIndex(source: String): TextLineIndex {
    val context = currentCoroutineContext()
    var starts = IntArray(128)
    var count = 1
    for (offset in source.indices) {
        if ((offset and 4095) == 0) context.ensureActive()
        if (source[offset] == '\n') {
            if (count == starts.size) starts = starts.copyOf(starts.size * 2)
            starts[count++] = offset + 1
        }
    }
    return TextLineIndex(source, starts.copyOf(count))
}

/** 原文、变换映射与布局属于同一次 onTextLayout 回调，禁止跨文档复用。 */
internal class VisibleTextLayout(
    val source: String,
    val layout: TextLayoutResult,
    val mapping: OffsetMapping,
)

/** 行号与布局成套保留，等待新索引时只重画旧快照，不能混用新正文的偏移。 [index] 为 null 表示不换行且无变换，视觉行就是逻辑行，行号直接读布局，不建表。 */
internal data class TextGutterSnapshot(
    val layout: TextLayoutResult,
    val index: TextLineIndex?,
    val mapping: OffsetMapping,
    val currentLine: Int,
)

internal data class TextViewportWindow(val visible: TextRange, val expanded: TextRange) {
    fun coveredBy(previous: TextRange?): Boolean =
        previous != null && visible.start >= previous.start && visible.end <= previous.end
}

/** 常数次布局查询；不逐行遍历。上下各扩展至少 16 个视觉行，并按 32 行分组。 */
internal fun visibleTextWindow(
    measured: VisibleTextLayout,
    top: Float,
    bottom: Float,
    left: Float,
    right: Float,
    wrap: Boolean,
): TextViewportWindow? {
    val layout = measured.layout
    if (layout.lineCount == 0) return null
    val first = layout.getLineForVerticalPosition(top.coerceAtLeast(0f))
    val last = layout.getLineForVerticalPosition((bottom - 0.5f).coerceAtLeast(top))
    val expandedFirst = ((first - 16).coerceAtLeast(0) / 32) * 32
    val expandedLast =
        (((last.toLong() + 16) / 32 + 1) * 32 - 1)
            .coerceAtMost(layout.lineCount.toLong() - 1)
            .toInt()

    fun original(offset: Int): Int =
        measured.mapping.transformedToOriginal(offset).coerceIn(0, measured.source.length)

    fun range(from: Int, to: Int, padding: Float): TextRange {
        if (wrap) {
            // 视觉行已经对应实际屏幕排版；不能扩成整逻辑行，否则压缩 JSON 会退回全文着色。
            return TextRange(original(layout.getLineStart(from)), original(layout.getLineEnd(to)))
        }
        // 不换行的超长行只取水平视口两端。跨多行的连续 range 仍会包含中间整行。
        fun hit(line: Int, x: Float): Int =
            original(
                layout
                    .getOffsetForPosition(
                        Offset(
                            x.coerceAtLeast(0f),
                            (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f,
                        ),
                    )
                    .coerceIn(layout.getLineStart(line), layout.getLineEnd(line)),
            )
        val a = hit(from, left - padding)
        val b = hit(from, right + padding)
        val c = hit(to, left - padding)
        val d = hit(to, right + padding)
        return TextRange(minOf(a, b, c, d), maxOf(a, b, c, d))
    }

    val visible = range(first, last, 0f)
    val padded = range(expandedFirst, expandedLast, if (wrap) 0f else 256f)
    val expanded =
        if (wrap) padded
        else
            TextRange(
                (padded.start / 256) * 256,
                ((padded.end.toLong() / 256 + 1) * 256)
                    .coerceAtMost(measured.source.length.toLong())
                    .toInt(),
            )
    return TextViewportWindow(visible, expanded)
}
