package moe.yumeyuka.yumehub.ui.component

internal enum class DiffLineKind {
    Context,
    Addition,
    Deletion,
    Metadata,
}

internal data class DiffLine(
    val text: String,
    val kind: DiffLineKind,
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val noNewlineAtEndOfFile: Boolean = false,
)

internal data class DiffHunk(
    val header: String?,
    val lines: List<DiffLine>,
    val oldStart: Int? = null,
    val oldCount: Int? = null,
    val newStart: Int? = null,
    val newCount: Int? = null,
    val isComplete: Boolean = false,
    val isValid: Boolean = false,
)

internal data class DiffDocument(
    val hunks: List<DiffHunk>,
    val rawPatch: String,
    // 只能识别 hunk 内数量不足；整段 hunk 或最后一行被截掉无法仅凭 patch 判断。
    val shortened: Boolean,
    val isValid: Boolean = false,
)

private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
private const val noNewlineMarker = "\\ No newline at end of file"

private class DiffHunkBuilder(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    var isValid: Boolean,
) {
    val lines = mutableListOf<DiffLine>()
    var oldRemaining = oldCount
    var newRemaining = newCount
    var oldLine = oldStart.toLong()
    var newLine = newStart.toLong()

    fun build(): DiffHunk =
        DiffHunk(
            header = header,
            lines = lines.toList(),
            oldStart = oldStart,
            oldCount = oldCount,
            newStart = newStart,
            newCount = newCount,
            isComplete = oldRemaining == 0 && newRemaining == 0,
            isValid = isValid && oldRemaining == 0 && newRemaining == 0,
        )
}

internal fun parseUnifiedDiff(patch: String): DiffDocument {
    val hunks = mutableListOf<DiffHunk>()
    val preamble = mutableListOf<DiffLine>()
    var currentHunk: DiffHunkBuilder? = null
    var previousHunk: DiffHunkBuilder? = null
    var invalid = false
    var shortened = false
    var oldFileEnded = false
    var newFileEnded = false
    var oldFileHeaderSeen = false
    var newFileHeaderSeen = false
    var gitHeaderSeen = false

    fun flushHunk() {
        val builder = currentHunk ?: return
        val hunk = builder.build()
        if (!hunk.isComplete) shortened = true
        if (!hunk.isValid) invalid = true
        hunks += hunk
        previousHunk = builder
        currentHunk = null
    }

    val input = patch.lineSequence().toList()
    input.forEachIndexed { index, text ->
        if (index == input.lastIndex && text.isEmpty() && patch.endsWith('\n'))
            return@forEachIndexed
        val match = hunkHeader.matchEntire(text)
        val oldStart = match?.groupValues?.get(1)?.toIntOrNull()
        val newStart = match?.groupValues?.get(3)?.toIntOrNull()
        val oldCount = match?.groupValues?.get(2)?.let { if (it.isEmpty()) 1 else it.toIntOrNull() }
        val newCount = match?.groupValues?.get(4)?.let { if (it.isEmpty()) 1 else it.toIntOrNull() }

        if (text.startsWith("@@")) {
            flushHunk()
            if (preamble.isNotEmpty()) {
                hunks += DiffHunk(null, preamble.toList())
                preamble.clear()
            }
            val rangesValid =
                oldStart != null &&
                    newStart != null &&
                    oldCount != null &&
                    newCount != null &&
                    (oldStart > 0 || oldCount == 0) &&
                    (newStart > 0 || newCount == 0) &&
                    (oldCount > 0 || newCount > 0) &&
                    oldStart.toLong() + oldCount - (if (oldCount > 0) 1 else 0) <= Int.MAX_VALUE &&
                    newStart.toLong() + newCount - (if (newCount > 0) 1 else 0) <= Int.MAX_VALUE
            if (!rangesValid) {
                invalid = true
                hunks +=
                    DiffHunk(null, listOf(DiffLine(text, DiffLineKind.Metadata)))
                return@forEachIndexed
            }
            val oldBoundary = oldStart.toLong() - if (oldCount > 0) 1 else 0
            val newBoundary = newStart.toLong() - if (newCount > 0) 1 else 0
            val previous = previousHunk
            val ordered =
                if (previous == null) oldBoundary == newBoundary
                else {
                    val previousOldEnd =
                        previous.oldStart.toLong() - (if (previous.oldCount > 0) 1 else 0) +
                            previous.oldCount
                    val previousNewEnd =
                        previous.newStart.toLong() - (if (previous.newCount > 0) 1 else 0) +
                            previous.newCount
                    val oldGap = oldBoundary - previousOldEnd
                    val newGap = newBoundary - previousNewEnd
                    oldGap >= 0 && oldGap == newGap
                }
            currentHunk =
                DiffHunkBuilder(text, oldStart, oldCount, newStart, newCount, ordered)
            return@forEachIndexed
        }

        val builder = currentHunk
        if (builder == null) {
            val validPreamble =
                if (previousHunk != null) false
                else
                    when {
                        text.startsWith("diff --git ") && !gitHeaderSeen -> {
                            gitHeaderSeen = true
                            true
                        }
                        text.startsWith("--- ") && !oldFileHeaderSeen && !newFileHeaderSeen -> {
                            oldFileHeaderSeen = true
                            true
                        }
                        text.startsWith("+++ ") && oldFileHeaderSeen && !newFileHeaderSeen -> {
                            newFileHeaderSeen = true
                            true
                        }
                        listOf(
                                "index ",
                                "old mode ",
                                "new mode ",
                                "new file mode ",
                                "deleted file mode ",
                                "similarity index ",
                                "dissimilarity index ",
                                "rename from ",
                                "rename to ",
                                "copy from ",
                                "copy to ",
                            )
                            .any(text::startsWith) -> true
                        else -> false
                    }
            if (!validPreamble) invalid = true
            preamble += DiffLine(text, DiffLineKind.Metadata)
            return@forEachIndexed
        }

        if (text == noNewlineMarker) {
            val previousLine = builder.lines.lastOrNull()
            if (previousLine == null || previousLine.kind == DiffLineKind.Metadata) {
                builder.isValid = false
            } else {
                val oldSide = previousLine.oldLine != null
                val newSide = previousLine.newLine != null
                if ((oldSide && oldFileEnded) || (newSide && newFileEnded)) builder.isValid = false
                if (oldSide) oldFileEnded = true
                if (newSide) newFileEnded = true
                builder.lines[builder.lines.lastIndex] =
                    previousLine.copy(noNewlineAtEndOfFile = true)
            }
            builder.lines += DiffLine(text, DiffLineKind.Metadata)
            return@forEachIndexed
        }

        val kind =
            when {
                text.startsWith(' ') && builder.oldRemaining > 0 && builder.newRemaining > 0 ->
                    DiffLineKind.Context
                text.startsWith('-') && builder.oldRemaining > 0 -> DiffLineKind.Deletion
                text.startsWith('+') && builder.newRemaining > 0 -> DiffLineKind.Addition
                else -> DiffLineKind.Metadata
            }
        val hasOld = kind == DiffLineKind.Context || kind == DiffLineKind.Deletion
        val hasNew = kind == DiffLineKind.Context || kind == DiffLineKind.Addition
        if (
            kind == DiffLineKind.Metadata ||
                (hasOld && oldFileEnded) ||
                (hasNew && newFileEnded)
        ) {
            builder.isValid = false
        }
        builder.lines +=
            DiffLine(
                text,
                kind,
                builder.oldLine.toInt().takeIf { hasOld },
                builder.newLine.toInt().takeIf { hasNew },
            )
        if (hasOld) {
            builder.oldLine++
            builder.oldRemaining--
        }
        if (hasNew) {
            builder.newLine++
            builder.newRemaining--
        }
    }
    flushHunk()
    if (preamble.isNotEmpty()) hunks += DiffHunk(null, preamble.toList())
    if (oldFileHeaderSeen != newFileHeaderSeen) invalid = true
    return DiffDocument(
        hunks = hunks,
        rawPatch = patch,
        shortened = shortened,
        isValid = !invalid && !shortened && previousHunk != null,
    )
}

/** 只接受可显示 hunk 中的实际行号，side 严格使用 GitHub 的 LEFT / RIGHT。 */
public fun diffContainsLine(patch: String, line: Int, side: String): Boolean {
    if (line <= 0 || (side != "LEFT" && side != "RIGHT")) return false
    val document = parseUnifiedDiff(patch)
    if (!document.isValid) return false
    return document.hunks.any { hunk ->
        hunk.lines.any { parsedLine ->
            if (side == "LEFT") parsedLine.oldLine == line else parsedLine.newLine == line
        }
    }
}

private data class MarkdownDiffSourceLine(val text: String, val terminated: Boolean)

private data class MarkdownDiffOperation(val prefix: Char, val line: MarkdownDiffSourceLine)

/** 有界 LCS；超过计算预算时将变化区间整体替换，保留完整差异内容。 */
fun createMarkdownUnifiedDiff(original: String, updated: String): String {
    if (original == updated) return ""
    fun sourceLines(source: String): List<MarkdownDiffSourceLine> {
        if (source.isEmpty()) return emptyList()
        val parts = source.split('\n')
        return parts.mapIndexedNotNull { index, text ->
            if (index == parts.lastIndex && text.isEmpty()) null
            else MarkdownDiffSourceLine(text, index < parts.lastIndex)
        }
    }
    val before = sourceLines(original)
    val after = sourceLines(updated)

    var prefix = 0
    while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (
        suffix < minOf(before.size, after.size) - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) suffix++
    val oldMiddle = before.subList(prefix, before.size - suffix)
    val newMiddle = after.subList(prefix, after.size - suffix)
    val operations = mutableListOf<MarkdownDiffOperation>()
    fun emit(prefix: Char, line: MarkdownDiffSourceLine) {
        operations += MarkdownDiffOperation(prefix, line)
    }
    before.take(prefix).forEach { emit(' ', it) }
    val rows = oldMiddle.size
    val columns = newMiddle.size
    if ((rows.toLong() + 1) * (columns.toLong() + 1) <= 250_000) {
        val width = columns + 1
        val lengths = IntArray((rows + 1) * width)
        for (i in rows - 1 downTo 0) {
            for (j in columns - 1 downTo 0) {
                lengths[i * width + j] =
                    if (oldMiddle[i] == newMiddle[j]) {
                        1 + lengths[(i + 1) * width + j + 1]
                    } else maxOf(lengths[(i + 1) * width + j], lengths[i * width + j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < rows || j < columns) {
            when {
                i < rows && j < columns && oldMiddle[i] == newMiddle[j] -> {
                    emit(' ', oldMiddle[i++])
                    j++
                }
                i < rows &&
                    (j == columns || lengths[(i + 1) * width + j] >= lengths[i * width + j + 1]) ->
                    emit('-', oldMiddle[i++])
                else -> emit('+', newMiddle[j++])
            }
        }
    } else {
        oldMiddle.forEach { emit('-', it) }
        newMiddle.forEach { emit('+', it) }
    }
    before.takeLast(suffix).forEach { emit(' ', it) }
    val ranges = mutableListOf<IntRange>()
    operations.forEachIndexed { index, operation ->
        if (operation.prefix != ' ') {
            val start = maxOf(0, index - 3)
            val end = minOf(operations.lastIndex, index + 3)
            if (ranges.isNotEmpty() && start <= ranges.last().last + 1) {
                ranges[ranges.lastIndex] = ranges.last().first..end
            } else ranges += start..end
        }
    }
    val patch = buildString {
        append("--- a/markdown\n+++ b/markdown\n")
        var cursor = 0
        var oldLine = 1
        var newLine = 1
        ranges.forEach { range ->
            while (cursor < range.first) {
                if (operations[cursor].prefix != '+') oldLine++
                if (operations[cursor].prefix != '-') newLine++
                cursor++
            }
            val oldCount = range.count { operations[it].prefix != '+' }
            val newCount = range.count { operations[it].prefix != '-' }
            append(
                "@@ -${if (oldCount == 0) oldLine - 1 else oldLine},$oldCount +${if (newCount == 0) newLine - 1 else newLine},$newCount @@\n",
            )
            range.forEach { index ->
                val operation = operations[index]
                append(operation.prefix).append(operation.line.text).append('\n')
                if (!operation.line.terminated) append("\\ No newline at end of file\n")
                if (operation.prefix != '+') oldLine++
                if (operation.prefix != '-') newLine++
                cursor++
            }
        }
    }
    return patch
}
