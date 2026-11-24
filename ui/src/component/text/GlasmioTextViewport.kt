package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Offsets for the exact text Foundation laid out. Most editors need no mapping table. */
internal class EditorTextPresentation(
    val source: String,
    password: Boolean,
    singleLine: Boolean,
    formatted: String = source,
    private val edits: IntArray? = null,
) {
    private val boundaries: IntArray?
    val displayText: String

    init {
        if (password) {
            // The secure field masks Unicode code points, not UTF-16 code units.
            val count = source.codePointCount(0, source.length)
            boundaries = if (count == source.length) null else IntArray(count + 1).also { offsets ->
                for (i in 0 until count) {
                    offsets[i + 1] = offsets[i] + Character.charCount(source.codePointAt(offsets[i]))
                }
            }
            displayText = "\u2022".repeat(count)
        } else {
            boundaries = null
            displayText = if (singleLine) formatted.replace('\n', ' ').replace('\r', '\uFEFF') else formatted
        }
    }

    fun originalToTransformed(offset: Int): Int {
        val offsets = boundaries ?: return mapEdit(offset, 0)
        val index = offsets.binarySearch(offset)
        return if (index >= 0) index else (-index - 2).coerceIn(0, offsets.lastIndex)
    }

    fun transformedToOriginal(offset: Int): Int =
        boundaries?.let { it[offset.coerceIn(it.indices)] } ?: mapEdit(offset, 2)

    // Each change is an ordered pair of half-open ranges: original then formatted.
    // Inside an insertion/replacement use its leading edge; its trailing edge maps exactly.
    private fun mapEdit(offset: Int, from: Int): Int {
        val ranges = edits ?: return offset
        val to = 2 - from
        var delta = 0
        for (i in ranges.indices step 4) {
            val start = ranges[i + from]
            val end = ranges[i + from + 1]
            if (offset <= start) break
            if (offset < end) return ranges[i + to]
            delta = ranges[i + to + 1] - end
        }
        return offset + delta
    }
}

/** Capture Foundation's own change list during its output pass, without formatting text twice. */
internal class EditorOutputFormatter(
    private val transform: OutputTransformation,
    private val singleLine: Boolean,
) : OutputTransformation {
    var presentation: EditorTextPresentation? = null
        private set

    override fun TextFieldBuffer.transformOutput() {
        with(transform) { transformOutput() }
        val count = changes.changeCount
        val ranges = if (count == 0) null else IntArray(count * 4).also { ranges ->
            for (i in 0 until count) {
                val before = changes.getOriginalRange(i)
                val after = changes.getRange(i)
                ranges[4 * i] = before.min
                ranges[4 * i + 1] = before.max
                ranges[4 * i + 2] = after.min
                ranges[4 * i + 3] = after.max
            }
        }
        val source = originalText.toString()
        presentation = EditorTextPresentation(
            source, false, singleLine, if (count == 0) source else toString(), ranges,
        )
    }
}

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
    val mapping: EditorTextPresentation,
)

/** 行号与布局成套保留，等待新索引时只重画旧快照，不能混用新正文的偏移。 [index] 为 null 表示不换行且无变换，视觉行就是逻辑行，行号直接读布局，不建表。 */
internal data class TextGutterSnapshot(
    val layout: TextLayoutResult,
    val index: TextLineIndex?,
    val mapping: EditorTextPresentation,
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
