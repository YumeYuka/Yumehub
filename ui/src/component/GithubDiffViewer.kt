package moe.yumeyuka.yumehub.ui.component

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffLineDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffLineType
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady
import moe.yumeyuka.yumehub.ui.theme.GithubEditorFontFamily

@Composable
fun githubAnnotatedCode(
    source: String,
    tokens: List<GithubTextMateSpan>,
): AnnotatedString =
    remember(source, tokens) {
        buildAnnotatedString {
            append(source)
            tokens.forEach { token ->
                if (token.start >= 0 && token.end > token.start && token.end <= source.length) {
                    addStyle(
                        SpanStyle(
                            color = token.color,
                            fontStyle =
                                if (token.fontStyleFlags and 1 != 0) FontStyle.Italic else null,
                            fontWeight =
                                if (token.fontStyleFlags and 2 != 0) FontWeight.Bold else null,
                            textDecoration =
                                when (token.fontStyleFlags and 12) {
                                    4 -> TextDecoration.Underline
                                    8 -> TextDecoration.LineThrough
                                    12 ->
                                        TextDecoration.combine(
                                            listOf(
                                                TextDecoration.Underline,
                                                TextDecoration.LineThrough,
                                            ),
                                        )
                                    else -> null
                                },
                        ),
                        token.start,
                        token.end,
                    )
                }
            }
        }
    }

private data class HighlightedDiffHunk(
    val tokens: List<List<GithubTextMateSpan>>,
    val inlineChanges: List<DiffInlineRange?>,
)

private data class DiffInlineRange(val start: Int, val end: Int)

/** 只将相邻增删行组按顺序配对，线性比较共同前后缀，不跨上下文寻找相似行。 */
private fun diffInlineChanges(lines: List<StructuredDiffLineDto>): List<DiffInlineRange?> {
    val ranges = MutableList<DiffInlineRange?>(lines.size) { null }
    fun isChange(type: StructuredDiffLineType): Boolean =
        type == StructuredDiffLineType.Deletion || type == StructuredDiffLineType.Addition
    fun splitsSurrogate(text: String, offset: Int): Boolean =
        offset > 0 &&
            offset < text.length &&
            text[offset - 1].isHighSurrogate() &&
            text[offset].isLowSurrogate()

    var index = 0
    while (index < lines.size) {
        val firstKind = lines[index].type
        if (!isChange(firstKind)) {
            index++
            continue
        }
        val firstStart = index
        while (index < lines.size && lines[index].type == firstKind) index++
        val secondStart = index
        if (index == lines.size || !isChange(lines[index].type)) continue
        val secondKind = lines[index].type
        while (index < lines.size && lines[index].type == secondKind) index++
        val pairCount = minOf(secondStart - firstStart, index - secondStart)
        repeat(pairCount) { pair ->
            val firstRow = firstStart + pair
            val secondRow = secondStart + pair
            val first = lines[firstRow].text
            val second = lines[secondRow].text
            var prefix = 0
            while (prefix < minOf(first.length, second.length) && first[prefix] == second[prefix]) {
                prefix++
            }
            if (splitsSurrogate(first, prefix) || splitsSurrogate(second, prefix)) prefix--
            var suffix = 0
            while (
                suffix < minOf(first.length, second.length) - prefix &&
                    first[first.lastIndex - suffix] == second[second.lastIndex - suffix]
            ) suffix++
            if (
                splitsSurrogate(first, first.length - suffix) ||
                    splitsSurrogate(second, second.length - suffix)
            )
                suffix--
            val firstEnd = first.length - suffix
            val secondEnd = second.length - suffix
            if (firstEnd > prefix) ranges[firstRow] = DiffInlineRange(prefix, firstEnd)
            if (secondEnd > prefix) ranges[secondRow] = DiffInlineRange(prefix, secondEnd)
        }
    }
    return ranges
}

/** 每个 hunk 独立分析旧、新侧；未知的 hunk 外词法状态不作推断。 */
private suspend fun highlightDiffHunk(
    context: Context,
    lines: List<StructuredDiffLineDto>,
    language: String?,
    isDark: Boolean,
): HighlightedDiffHunk {
    val coroutineContext = currentCoroutineContext()
    val mapped = MutableList(lines.size) { emptyList<GithubTextMateSpan>() }
    for (oldSide in listOf(true, false)) {
        val indices =
            lines.indices.filter { index ->
                val line = lines[index]
                if (oldSide) line.type != StructuredDiffLineType.Addition
                else line.type != StructuredDiffLineType.Deletion
            }
        val source = buildString {
            indices.forEachIndexed { index, row ->
                if (index > 0) append('\n')
                append(lines[row].text)
            }
        }
        if (source.isEmpty()) continue
        val tokens =
            highlightGithubTextMate(
                    context,
                    source,
                    language,
                    isDark,
                    isActive = { coroutineContext.isActive },
                )
                .sortedBy { it.start }
        var offset = 0
        var tokenIndex = 0
        indices.forEach { row ->
            coroutineContext.ensureActive()
            val line = lines[row]
            val length = line.text.length
            while (tokenIndex < tokens.size && tokens[tokenIndex].end <= offset) tokenIndex++
            val lineTokens = mutableListOf<GithubTextMateSpan>()
            var cursor = tokenIndex
            while (cursor < tokens.size && tokens[cursor].start < offset + length) {
                val token = tokens[cursor++]
                val start = maxOf(token.start, offset)
                val end = minOf(token.end, offset + length)
                if (end > start)
                    lineTokens += token.copy(start = start - offset, end = end - offset)
            }
            // 上下文行优先采用新侧，删除行只采用旧侧。
            if (!oldSide || line.type == StructuredDiffLineType.Deletion) mapped[row] = lineTokens
            offset += length + 1
        }
    }
    return HighlightedDiffHunk(mapped, diffInlineChanges(lines))
}

@Composable
fun GithubDiffViewer(diff: StructuredDiffDto, modifier: Modifier, language: String?) {
    if (!LocalGithubRouteEntryReady.current) return
    val context = LocalContext.current.applicationContext
    val strings = LocalGithubStrings.current.Repository
    val isDark = GlasmioTheme.colors.isDark
    val horizontalScrollState =
        key(diff.id, diff.baseCommitOid, diff.headCommitOid) { rememberScrollState() }
    // Draw actual lines immediately on expansion and restoration. Highlighting changes only spans.
    val hunks =
        remember(diff.id, diff.baseCommitOid, diff.headCommitOid) {
            buildList<List<StructuredDiffLineDto>> {
                var lines = mutableListOf<StructuredDiffLineDto>()
                for (line in diff.lines) {
                    if (line.type == StructuredDiffLineType.Hunk) {
                        if (lines.isNotEmpty()) {
                            add(lines)
                            lines = mutableListOf()
                        }
                    } else {
                        lines.add(line.copy(text = githubStructuredDiffCodeText(line.text)))
                    }
                }
                if (lines.isNotEmpty()) add(lines)
            }
        }
    var highlights by
        remember(hunks, language, isDark) { mutableStateOf<List<HighlightedDiffHunk>>(emptyList()) }
    LaunchedEffect(hunks, language, isDark) {
        try {
            highlights =
                withContext(Dispatchers.Default) {
                    hunks.map { lines ->
                        ensureActive()
                        highlightDiffHunk(context, lines, language, isDark)
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Syntax highlighting is optional. The original patch remains readable.
        }
    }
    val colors = remember(isDark) { githubDiffColors(isDark) }
    val style =
        GlasmioTheme.textStyles.footnote.copy(
            color = colors.foreground,
            fontFamily = GithubEditorFontFamily,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
    val measurer = rememberTextMeasurer()
    // 行号沿用文件头图标的起始缩进；两侧按实际位数共用列宽，至少预留两位。
    val gutterInset = GlasmioListRowDefaults.horizontalPadding
    val digits =
        remember(hunks) {
            maxOf(
                2,
                diff.lines.maxOfOrNull { maxOf(it.left ?: 0, it.right ?: 0) }?.toString()?.length
                    ?: 1,
            )
        }
    val numberWidth =
        with(LocalDensity.current) {
            measurer.measure("9".repeat(digits), style).size.width.toDp() + 8.dp
        }
    val markerWidth =
        with(LocalDensity.current) { measurer.measure("+", style).size.width.toDp() + 8.dp }
    val codeSpaceWidth =
        with(LocalDensity.current) { measurer.measure("M", style).size.width.toDp() }
    Column(modifier.fillMaxWidth().background(colors.background)) {
        if (hunks.isEmpty()) {
            BasicText(strings.DiffNoTextChanges, style = style)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minimumWidth = maxWidth
                // 同一文件只使用一个横向滚动容器，纵向手势继续交给页面列表。
                Column(
                    Modifier.horizontalScroll(horizontalScrollState)
                        .widthIn(min = minimumWidth)
                        .width(IntrinsicSize.Max),
                ) {
                    hunks.forEachIndexed { index, lines ->
                        val highlighted = highlights.getOrNull(index)
                        key(
                            diff.id,
                            diff.baseCommitOid,
                            diff.headCommitOid,
                            language,
                            index,
                        ) {
                            SelectionContainer {
                                Column {
                                    lines.forEachIndexed { row, line ->
                                        GithubDiffCodeRow(
                                            line = line,
                                            tokens = highlighted?.tokens?.get(row).orEmpty(),
                                            inlineChange = highlighted?.inlineChanges?.get(row),
                                            colors = colors,
                                            style = style,
                                            numberWidth = numberWidth,
                                            markerWidth = markerWidth,
                                            gutterInset = gutterInset,
                                            codeSpaceWidth = codeSpaceWidth,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GithubDiffCodeRow(
    line: StructuredDiffLineDto,
    tokens: List<GithubTextMateSpan>,
    inlineChange: DiffInlineRange?,
    colors: GithubDiffColors,
    style: TextStyle,
    numberWidth: Dp,
    markerWidth: Dp,
    gutterInset: Dp,
    codeSpaceWidth: Dp,
) {
    val background =
        when (line.type) {
            StructuredDiffLineType.Addition -> colors.additionBackground
            StructuredDiffLineType.Deletion -> colors.deletionBackground
            else -> colors.background
        }
    val numberBackground =
        when (line.type) {
            StructuredDiffLineType.Addition -> colors.additionNumberBackground
            StructuredDiffLineType.Deletion -> colors.deletionNumberBackground
            else -> colors.background
        }
    val wordBackground =
        when (line.type) {
            StructuredDiffLineType.Addition -> colors.additionWordBackground
            StructuredDiffLineType.Deletion -> colors.deletionWordBackground
            else -> colors.background
        }
    val annotated = githubAnnotatedCode(line.text, tokens)
    val displayText =
        remember(annotated, inlineChange, wordBackground) {
            if (inlineChange == null) annotated
            else
                buildAnnotatedString {
                    append(annotated)
                    addStyle(
                        SpanStyle(background = wordBackground),
                        inlineChange.start,
                        inlineChange.end,
                    )
                }
        }
    Row(
        Modifier.fillMaxWidth().background(background).heightIn(min = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DisableSelection {
            Row(Modifier.background(numberBackground).padding(start = gutterInset)) {
                val oldLine = line.left?.takeIf { line.type != StructuredDiffLineType.Addition }
                val newLine = line.right?.takeIf { line.type != StructuredDiffLineType.Deletion }
                for (number in listOf(oldLine, newLine)) {
                    BasicText(
                        number?.toString().orEmpty(),
                        modifier = Modifier.width(numberWidth).padding(end = 8.dp),
                        style =
                            style.copy(
                                color =
                                    if (line.type == StructuredDiffLineType.Context)
                                        colors.mutedForeground
                                    else colors.foreground,
                                textAlign = TextAlign.End,
                            ),
                        softWrap = false,
                    )
                }
            }
            BasicText(
                text =
                    when (line.type) {
                        StructuredDiffLineType.Addition -> "+"
                        StructuredDiffLineType.Deletion -> "-"
                        else -> " "
                    },
                modifier = Modifier.width(markerWidth).padding(horizontal = 4.dp),
                style = style.copy(textAlign = TextAlign.Center),
                softWrap = false,
            )
        }
        BasicText(
            displayText,
            modifier =
                Modifier.padding(
                    start =
                        if (line.type == StructuredDiffLineType.Context) 0.dp else codeSpaceWidth,
                ),
            style = style,
            softWrap = false,
        )
    }
}
