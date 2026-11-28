package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.ui.text.TextRange

/** 搜索结果绑定原文快照，匹配范围统一使用与 Compose 布局相同的 UTF-16 偏移。 */
data class CodeSearchResult(
    val source: String,
    val query: String,
    val matchCase: Boolean,
    val matches: List<TextRange>,
) {
    fun indexAtOrAfter(offset: Int): Int {
        if (matches.isEmpty()) return -1
        var low = 0
        var high = matches.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (matches[middle].start < offset) low = middle + 1 else high = middle
        }
        return if (low < matches.size) low else 0
    }

    fun nextIndex(current: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        if (current !in matches.indices) return if (forward) 0 else matches.lastIndex
        return (current + (if (forward) 1 else matches.size - 1)) % matches.size
    }
}

data class CodeSearchHighlights(
    val result: CodeSearchResult,
    val currentIndex: Int,
    val revealRequest: Int,
) {
    val current: TextRange?
        get() = result.matches.getOrNull(currentIndex)
}

/** 字面量查找，不把关键字当作正则；长文查找在调用方的后台任务中执行。 */
fun findCodeMatches(
    source: String,
    query: String,
    matchCase: Boolean,
    ensureActive: () -> Unit = {},
): CodeSearchResult {
    val matches = ArrayList<TextRange>()
    if (query.isNotEmpty()) {
        var offset = 0
        while (offset <= source.length - query.length) {
            ensureActive()
            val start = source.indexOf(query, offset, ignoreCase = !matchCase)
            if (start < 0) break
            val end = start + query.length
            matches += TextRange(start, end)
            offset = end
        }
    }
    return CodeSearchResult(source, query, matchCase, matches)
}

/** 二分定位到可见行的首个匹配，避免每帧遍历全文命中项。 */
internal fun visibleCodeSearchMatches(matches: List<TextRange>, start: Int, end: Int): IntRange {
    if (start >= end || matches.isEmpty()) return IntRange.EMPTY
    var low = 0
    var high = matches.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (matches[middle].end <= start) low = middle + 1 else high = middle
    }
    val first = low
    while (low < matches.size && matches[low].start < end) low++
    return first until low
}
