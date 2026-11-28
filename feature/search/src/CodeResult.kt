package moe.yumeyuka.yumehub.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile
import moe.yumeyuka.yumehub.data.repository.SearchCodeLine
import moe.yumeyuka.yumehub.data.repository.previewLines
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.DiffCardFrame
import moe.yumeyuka.yumehub.ui.component.TextMateSpan
import moe.yumeyuka.yumehub.ui.component.AnnotatedCode
import moe.yumeyuka.yumehub.ui.component.codeLanguageForFilename
import moe.yumeyuka.yumehub.ui.component.diffColors
import moe.yumeyuka.yumehub.ui.component.highlightTextMate
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.DiffCodePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.theme.EditorFontFamily
import moe.yumeyuka.yumehub.ui.theme.languageColor

private const val PreviewLineCount = 5

@Composable
internal fun CodeResult(
    file: SearchCodeFile?,
    onOpenFile: (SearchCodeFile, Int?) -> Unit,
) {
    LoadingContent(
        loading = file == null,
        placeholder = { CodeResultBody(null, onOpenFile) },
    ) {
        if (file != null) CodeResultBody(file, onOpenFile)
    }
}

@Composable
private fun CodeResultBody(file: SearchCodeFile?, onOpenFile: (SearchCodeFile, Int?) -> Unit) {
    val colors = GlasmioTheme.colors
    val styles = GlasmioTheme.textStyles
    val diffColors = remember(colors.isDark) { diffColors(colors.isDark) }
    val lines = remember(file) { file?.previewLines(PreviewLineCount).orEmpty() }
    DiffCardFrame(
        if (file == null) Modifier.clearAndSetSemantics {}
        else Modifier.clickable { onOpenFile(file, file.snippets.firstOrNull()?.jump) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (file == null) {
                    AvatarPlaceholder(18.dp)
                    TextLinePlaceholder(styles.footnote, fraction = 0.4f)
                } else {
                    UserAvatar(file.ownerAvatarUrl, file.owner, size = 18.dp)
                    BasicText(
                        "${file.owner} / ${file.repository}",
                        style = styles.footnote.copy(color = colors.secondaryContent),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (file == null) {
                    TextLinePlaceholder(styles.body, Modifier.weight(1f), fraction = 0.55f)
                } else {
                    BasicText(
                        file.path.substringAfterLast('/'),
                        Modifier.weight(1f),
                        style = styles.body.copy(color = colors.content),
                    )
                }
                file?.language?.let { language ->
                    Box(Modifier.size(9.dp).background(languageColor(language), CircleShape))
                    BasicText(
                        language,
                        style = styles.caption1.copy(color = colors.secondaryContent),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(diffColors.background).padding(bottom = 12.dp)) {
            val codeStyle =
                styles.footnote.copy(
                    color = diffColors.foreground,
                    fontFamily = EditorFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                )
            val digits = (lines.lastOrNull()?.key ?: 99).toString().length.coerceAtLeast(2)
            val measurer = rememberTextMeasurer()
            val gutter =
                with(LocalDensity.current) {
                    measurer.measure("9".repeat(digits), codeStyle).size.width.toDp() + 8.dp
                }
            if (file == null) {
                DiffCodePlaceholder(
                    codeStyle,
                    Modifier.padding(start = 16.dp),
                    gutter,
                    PreviewLineCount,
                )
                return@Column
            }
            val language = codeLanguageForFilename(file.path) ?: file.language?.lowercase()
            val rowHeight =
                with(LocalDensity.current) { codeStyle.lineHeight.toDp().coerceAtLeast(20.dp) }
            BoxWithConstraints(
                Modifier.fillMaxWidth().heightIn(min = rowHeight * PreviewLineCount),
            ) {
                Column(
                    Modifier.horizontalScroll(rememberScrollState())
                        .widthIn(min = maxWidth)
                        .width(IntrinsicSize.Max),
                ) {
                    lines.forEach { (number, line) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = rowHeight)
                                .clickable { onOpenFile(file, number) }
                                .padding(start = 16.dp, end = 16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            BasicText(
                                number.toString(),
                                Modifier.width(gutter).padding(end = 8.dp),
                                style =
                                    codeStyle.copy(
                                        color = diffColors.mutedForeground,
                                        textAlign = TextAlign.End,
                                    ),
                                softWrap = false,
                            )
                            BasicText(
                                annotatedMatchLine(line, language),
                                style = codeStyle,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun annotatedMatchLine(line: SearchCodeLine, language: String?): AnnotatedString {
    val text = line.text
    val context = LocalContext.current.applicationContext
    val colors = GlasmioTheme.colors
    val dark = colors.isDark
    var tokens by remember(text, language, dark) { mutableStateOf(emptyList<TextMateSpan>()) }
    LaunchedEffect(text, language, dark) {
        tokens =
            withContext(Dispatchers.Default) {
                highlightTextMate(context, text, language, dark) { isActive }
            }
    }
    val source = AnnotatedCode(text, tokens)
    val mark = colors.accent.copy(alpha = if (dark) 0.38f else 0.24f)
    return remember(source, line.matches, mark) {
        buildAnnotatedString {
            append(source)
            for (match in line.matches) {
                addStyle(SpanStyle(background = mark), match.start, match.end)
            }
        }
    }
}
