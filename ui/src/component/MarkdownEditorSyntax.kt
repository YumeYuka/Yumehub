package moe.yumeyuka.yumehub.ui.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** start 包含起始围栏，end 位于结束围栏行末，不包含该行换行符；偏移均为 UTF-16。 */
data class MarkdownCodeFence(
    val start: Int,
    val end: Int,
    val language: String,
    val content: String,
)

private data class EditorLine(val start: Int, val end: Int, val next: Int, val text: String)

private val openingFence = Regex("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$")
private val listMarker =
    Regex(
        "^(?:[-+*][ \\t]+\\[[ xX]\\](?:[ \\t]+|$)|[-+*](?:[ \\t]+|$)|" + "[0-9]+[.)](?:[ \\t]+|$))",
    )
private val taskMarker = Regex("^[-+*][ \\t]+\\[[ xX]\\](?:[ \\t]+|$)")
private val bulletMarker = Regex("^[-+*](?:[ \\t]+|$)")
private val numberMarker = Regex("^[0-9]+[.)](?:[ \\t]+|$)")
private val headingMarker = Regex("^#{1,6}(?: +|$)")
private val quotePrefix = Regex("^(?:> ?)*")

fun findMarkdownCodeFence(source: String, selection: TextRange): MarkdownCodeFence? {
    val selected = markdownSelection(source, selection)
    val lines = editorLines(source)
    var index = 0
    while (index < lines.size) {
        val opening = lines[index]
        val match = openingFence.matchEntire(opening.text)
        if (match == null || (match.groupValues[1][0] == '`' && '`' in match.groupValues[2])) {
            index++
            continue
        }
        val marker = match.groupValues[1]
        var closingIndex = index + 1
        while (closingIndex < lines.size) {
            val closing = lines[closingIndex]
            val trimmed = closing.text.trimStart(' ')
            val indent = closing.text.length - trimmed.length
            val run = trimmed.takeWhile { it == marker[0] }.length
            if (
                indent <= 3 &&
                    run >= marker.length &&
                    trimmed.drop(run).all { it == ' ' || it == '\t' }
            ) {
                val contentEnd =
                    when {
                        closing.start == opening.next -> opening.next
                        source.getOrNull(closing.start - 2) == '\r' -> closing.start - 2
                        else -> closing.start - 1
                    }
                val fence =
                    MarkdownCodeFence(
                        start = opening.start,
                        end = closing.end,
                        language = match.groupValues[2].trim(),
                        content = source.substring(opening.next, contentEnd),
                    )
                if (
                    selected.min >= fence.start &&
                        selected.min <= fence.end &&
                        selected.max <= closing.next
                ) {
                    return fence
                }
                break
            }
            closingIndex++
        }
        // 未闭合围栏的后续围栏仍属于代码内容，不能当作新的独立块。
        if (closingIndex == lines.size) return null
        index = closingIndex + 1
    }
    return null
}

fun replaceMarkdownCodeFence(
    source: String,
    fence: MarkdownCodeFence,
    content: String,
    language: String,
): TextFieldValue {
    val selection = markdownSelection(source, TextRange(fence.start.coerceAtLeast(0)))
    // 按原始位置与完整身份校验，重复正文或过期的编辑弹层不会改到其他块。
    val current =
        findMarkdownCodeFence(source, selection) ?: return TextFieldValue(source, selection)
    if (current != fence) return TextFieldValue(source, selection)
    if (content == current.content && language == current.language) {
        return TextFieldValue(source, selection)
    }
    val original = source.substring(fence.start, fence.end)
    val newline = if ("\r\n" in original) "\r\n" else "\n"
    val character = original.trimStart(' ').first()
    val block = markdownFenceText(content, language, newline, character)
    val text = source.replaceRange(fence.start, fence.end, block)
    val bodyStart = fence.start + block.indexOf(newline) + newline.length
    return TextFieldValue(text, TextRange(bodyStart, bodyStart + content.length))
}

internal fun markdownSelection(source: String, range: TextRange): TextRange {
    fun boundary(offset: Int, forward: Boolean): Int {
        val clamped = offset.coerceIn(0, source.length)
        if (
            clamped > 0 &&
                clamped < source.length &&
                source[clamped - 1].isHighSurrogate() &&
                source[clamped].isLowSurrogate()
        ) {
            return if (forward) clamped + 1 else clamped - 1
        }
        return clamped
    }
    if (range.collapsed) return TextRange(boundary(range.start, false))
    val start = boundary(range.min, false)
    val end = boundary(range.max, true)
    return if (range.reversed) TextRange(end, start) else TextRange(start, end)
}

internal fun transformMarkdown(value: TextFieldValue, action: MarkdownEditAction): TextFieldValue {
    val selected = markdownSelection(value.text, value.selection)
    val safeValue = value.copy(selection = selected, composition = null)
    return when (action) {
        MarkdownEditAction.Bold -> inlineMarkdown(safeValue, "**")
        MarkdownEditAction.Italic -> inlineMarkdown(safeValue, "*")
        MarkdownEditAction.Strike -> inlineMarkdown(safeValue, "~~")
        MarkdownEditAction.InlineCode -> {
            val body = value.text.substring(selected.min, selected.max)
            val marker = "`".repeat(maxOf(1, longestRun(body, '`') + 1))
            inlineMarkdown(safeValue, marker, code = true)
        }
        MarkdownEditAction.Link -> linkMarkdown(safeValue)
        MarkdownEditAction.CodeBlock -> codeBlockMarkdown(safeValue)
        else -> lineMarkdown(safeValue, action)
    }
}

private fun inlineMarkdown(
    value: TextFieldValue,
    marker: String,
    code: Boolean = false,
): TextFieldValue {
    val source = value.text
    val start = value.selection.min
    val end = value.selection.max
    val body = source.substring(start, end)
    if (code) {
        val inside = Regex("^(`+)([\\s\\S]*?)\\1$").matchEntire(body)
        if (
            inside != null &&
                inside.groupValues[2].isNotEmpty() &&
                !inside.groupValues[2].startsWith('`') &&
                !inside.groupValues[2].endsWith('`')
        ) {
            val inner = inside.groupValues[2]
            val unpadded =
                if (inner.startsWith(' ') && inner.endsWith(' ') && inner.any { it != ' ' })
                    inner.substring(1, inner.length - 1)
                else inner
            return replaceSelection(value, start, end, unpadded)
        }
        val padded = source.getOrNull(start - 1) == ' ' && source.getOrNull(end) == ' '
        val left = if (padded) start - 1 else start
        val right = if (padded) end + 1 else end
        val before = source.substring(0, left).takeLastWhile { it == '`' }
        val after = source.substring(right).takeWhile { it == '`' }
        if (before.isNotEmpty() && before == after) {
            return replaceSelection(value, left - before.length, right + after.length, body)
        }
    }
    val width = marker.length
    val selectedWrapper =
        body.length >= width * 2 &&
            body.startsWith(marker) &&
            body.endsWith(marker) &&
            (marker != "*" || !body.startsWith("**"))
    if (selectedWrapper) {
        return replaceSelection(value, start, end, body.substring(width, body.length - width))
    }
    val surrounded =
        start >= width &&
            end + width <= source.length &&
            source.substring(start - width, start) == marker &&
            source.substring(end, end + width) == marker &&
            (marker != "*" ||
                (source.getOrNull(start - 2) != '*' && source.getOrNull(end + 1) != '*'))
    if (surrounded) return replaceSelection(value, start - width, end + width, body)
    val needsCodePadding =
        body.startsWith('`') ||
            body.endsWith('`') ||
            (body.startsWith(' ') && body.endsWith(' ') && body.any { it != ' ' })
    val padding = if (code && needsCodePadding) " " else ""
    val replacement = marker + padding + body + padding + marker
    val text = source.replaceRange(start, end, replacement)
    val anchor = start + width + padding.length
    return TextFieldValue(text, directedRange(value.selection, anchor, anchor + body.length))
}

private fun linkMarkdown(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val body = value.text.substring(start, end)
    if (body.startsWith('[')) {
        val link = markdownInlineLink(value.text, start) ?: return value
        if (link.end != end) return value
        return replaceSelection(
            value,
            start,
            end,
            value.text.substring(link.labelStart, link.labelEnd),
        )
    }
    if (start > 0 && value.text[start - 1] == '[' && value.text.startsWith("](", end)) {
        val link = markdownInlineLink(value.text, start - 1) ?: return value
        if (link.labelStart != start || link.labelEnd != end) return value
        return replaceSelection(value, start - 1, link.end, body)
    }
    val replacement = "[$body](https://)"
    val text = value.text.replaceRange(start, end, replacement)
    val urlStart = start + body.length + 3
    return TextFieldValue(text, TextRange(urlStart, urlStart + "https://".length))
}

private data class InlineLinkRange(val labelStart: Int, val labelEnd: Int, val end: Int)

/** 只解除可确定边界的内联链接；复杂标题、代码标签及不完整语法保留原文。 */
private fun markdownInlineLink(source: String, start: Int): InlineLinkRange? {
    if (source.getOrNull(start) != '[' || source.getOrNull(start - 1) == '!') return null
    var backslashes = 0
    var before = start - 1
    while (before >= 0 && source[before] == '\\') {
        backslashes++
        before--
    }
    if (backslashes % 2 != 0) return null
    var index = start + 1
    var labelEnd = -1
    while (index < source.length) {
        when (source[index]) {
            '\r',
            '\n',
            '`' -> return null
            '\\' -> {
                if (
                    source.getOrNull(index + 1) == null ||
                        source[index + 1] == '\r' ||
                        source[index + 1] == '\n'
                )
                    return null
                index += if (isMarkdownEscape(source[index + 1])) 2 else 1
                continue
            }
            '[' -> return null
            ']' -> {
                labelEnd = index
                break
            }
        }
        index++
    }
    if (labelEnd < 0 || source.getOrNull(labelEnd + 1) != '(') return null
    index = labelEnd + 2
    val angleDestination = source.getOrNull(index) == '<'
    if (angleDestination) index++
    var parentheses = 1
    while (index < source.length) {
        val character = source[index]
        when {
            character == '\r' || character == '\n' -> return null
            character == '\\' -> {
                val escaped = source.getOrNull(index + 1) ?: return null
                if (escaped == '\r' || escaped == '\n') return null
                index += if (isMarkdownEscape(escaped)) 2 else 1
                continue
            }
            angleDestination -> {
                if (character == '<') return null
                if (character == '>') {
                    return if (source.getOrNull(index + 1) == ')') {
                        InlineLinkRange(start + 1, labelEnd, index + 2)
                    } else null
                }
            }
            character == '(' -> parentheses++
            character == ')' -> {
                parentheses--
                if (parentheses == 0) return InlineLinkRange(start + 1, labelEnd, index + 1)
            }
            character.isWhitespace() ||
                character == '<' ||
                character == '>' ||
                character == '"' ||
                character == '\'' -> return null
        }
        index++
    }
    return null
}

private fun isMarkdownEscape(character: Char): Boolean =
    character in '!'..'/' || character in ':'..'@' || character in '['..'`' || character in '{'..'~'

private fun codeBlockMarkdown(value: TextFieldValue): TextFieldValue {
    val fence = findMarkdownCodeFence(value.text, value.selection)
    if (fence != null) return replaceSelection(value, fence.start, fence.end, fence.content)
    val (start, end) = selectedLines(value.text, value.selection)
    val content = value.text.substring(start, end)
    val newline = if ("\r\n" in value.text) "\r\n" else "\n"
    val block = markdownFenceText(content, "", newline, '`')
    val bodyStart = start + block.indexOf(newline) + newline.length
    return TextFieldValue(
        value.text.replaceRange(start, end, block),
        directedRange(value.selection, bodyStart, bodyStart + content.length),
    )
}

private data class PrefixEdit(val start: Int, val oldLength: Int, val prefix: String)

private fun lineMarkdown(value: TextFieldValue, action: MarkdownEditAction): TextFieldValue {
    val (start, end) = selectedLines(value.text, value.selection)
    val lines = editorLines(value.text.substring(start, end))
    data class Prefix(val offset: Int, val existing: String, val matches: Boolean)
    val prefixes = lines.map { line ->
        val indent = line.text.takeWhile { it == ' ' || it == '\t' }.length
        val rest = line.text.drop(indent)
        val quote = quotePrefix.find(rest)!!.value
        val offset = if (action == MarkdownEditAction.Quote) indent else indent + quote.length
        val tail = line.text.drop(offset)
        val existing =
            when (action) {
                MarkdownEditAction.Quote ->
                    when {
                        tail.startsWith("> ") -> "> "
                        tail.startsWith('>') -> ">"
                        else -> ""
                    }
                MarkdownEditAction.Heading -> headingMarker.find(tail)?.value.orEmpty()
                else -> listMarker.find(tail)?.value.orEmpty()
            }
        val matches =
            when (action) {
                MarkdownEditAction.Quote,
                MarkdownEditAction.Heading -> existing.isNotEmpty()
                MarkdownEditAction.BulletedList ->
                    bulletMarker.containsMatchIn(tail) && !taskMarker.containsMatchIn(tail)
                MarkdownEditAction.NumberedList -> numberMarker.containsMatchIn(tail)
                MarkdownEditAction.TaskList -> taskMarker.containsMatchIn(tail)
                else -> false
            }
        Prefix(offset, existing, matches)
    }
    val remove = prefixes.all { it.matches }
    val edits = lines.mapIndexed { index, line ->
        val prefix = prefixes[index]
        val replacement =
            if (remove) ""
            else
                when (action) {
                    MarkdownEditAction.Quote -> "> "
                    MarkdownEditAction.BulletedList -> "- "
                    MarkdownEditAction.NumberedList -> "${index + 1}. "
                    MarkdownEditAction.TaskList -> "- [ ] "
                    MarkdownEditAction.Heading -> "# "
                    else -> ""
                }
        PrefixEdit(start + line.start + prefix.offset, prefix.existing.length, replacement)
    }
    val text = StringBuilder(value.text)
    for (edit in edits.asReversed()) {
        text.replace(edit.start, edit.start + edit.oldLength, edit.prefix)
    }
    fun map(offset: Int): Int {
        var delta = 0
        for (edit in edits) {
            if (offset < edit.start) break
            if (offset <= edit.start + edit.oldLength) {
                return edit.start + delta + edit.prefix.length
            }
            delta += edit.prefix.length - edit.oldLength
        }
        return offset + delta
    }
    val source = text.toString()
    val selection = TextRange(map(value.selection.start), map(value.selection.end))
    return TextFieldValue(source, markdownSelection(source, selection))
}

private fun replaceSelection(
    value: TextFieldValue,
    start: Int,
    end: Int,
    replacement: String,
): TextFieldValue =
    TextFieldValue(
        value.text.replaceRange(start, end, replacement),
        directedRange(value.selection, start, start + replacement.length),
    )

private fun directedRange(original: TextRange, start: Int, end: Int): TextRange =
    if (original.reversed) TextRange(end, start) else TextRange(start, end)

private fun selectedLines(source: String, selection: TextRange): Pair<Int, Int> {
    val start = source.lastIndexOf('\n', selection.min - 1) + 1
    val last =
        if (selection.max > selection.min && source.getOrNull(selection.max - 1) == '\n') {
            selection.max - 1
        } else {
            selection.max
        }
    val newline = source.indexOf('\n', last).let { if (it < 0) source.length else it }
    val end = if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
    return start to end
}

private fun editorLines(source: String): List<EditorLine> = buildList {
    var start = 0
    while (start <= source.length) {
        val newline = source.indexOf('\n', start)
        val next = if (newline < 0) source.length else newline + 1
        val rawEnd = if (newline < 0) source.length else newline
        val end = if (rawEnd > start && source[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
        add(EditorLine(start, end, next, source.substring(start, end)))
        if (newline < 0) break
        start = next
    }
}

private fun markdownFenceText(
    content: String,
    language: String,
    newline: String,
    preferred: Char,
): String {
    val info = language.lineSequence().firstOrNull().orEmpty().trim()
    val character = if (preferred == '`' && '`' in info) '~' else preferred
    val marker = character.toString().repeat(maxOf(3, longestRun(content, character) + 1))
    return marker + info + newline + content + (if (content.isEmpty()) "" else newline) + marker
}

private fun longestRun(source: String, character: Char): Int {
    var longest = 0
    var current = 0
    for (char in source) {
        current = if (char == character) current + 1 else 0
        longest = maxOf(longest, current)
    }
    return longest
}
