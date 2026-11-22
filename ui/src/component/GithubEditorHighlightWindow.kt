package moe.yumeyuka.yumehub.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange

/** 只把可见字符的颜色交给 Foundation；全文原文、IME 和选区偏移保持原样 */
internal fun githubEditorHighlightedText(
    text: String,
    spans: List<GithubTextMateSpan>,
    visible: TextRange,
    defaultColor: Color? = null,
    checkActive: () -> Unit = {},
): AnnotatedString {
    checkActive()
    val start = visible.min.coerceIn(0, text.length)
    val end = visible.max.coerceIn(start, text.length)
    if (start == end || spans.isEmpty()) return AnnotatedString(text)
    var low = 0
    var high = spans.size
    // TextMate 输出有序且不重叠；二分跳过视口之前的 token，滚动不再从头扫描。
    while (low < high) {
        val middle = (low + high) ushr 1
        if (spans[middle].end <= start) low = middle + 1 else high = middle
    }
    val styles = ArrayList<AnnotatedString.Range<SpanStyle>>()
    var index = low
    while (index < spans.size) {
        if ((index and 255) == 0) checkActive()
        val span = spans[index++]
        if (span.start >= end) break
        val from = maxOf(start, span.start)
        val to = minOf(end, span.end)
        if (from < to && span.color != defaultColor) {
            val previous = styles.lastOrNull()
            if (previous != null && previous.end == from && previous.item.color == span.color) {
                styles[styles.lastIndex] = AnnotatedString.Range(previous.item, previous.start, to)
            } else {
                styles += AnnotatedString.Range(SpanStyle(color = span.color), from, to)
            }
        }
    }
    // 直接复用 String，不把全文 append 到 Builder 再复制一次。
    return AnnotatedString(text = text, spanStyles = styles)
}
