package moe.yumeyuka.markdown.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 颜色由宿主提供；默认派生色仅继承宿主调色板，不绑定应用主题。 */
data class HtmlPalette(
    val textPrimary: Color,
    val textSecondary: Color,
    val background: Color,
    val link: Color,
    val border: Color,
    val codeBackground: Color,
    val isDark: Boolean = false,
    val textTertiary: Color = textSecondary,
    val heading: Color = textPrimary,
    val h6: Color = textSecondary,
    val horizontalRule: Color = border,
    val quote: Color = textSecondary,
    val quoteBorder: Color = border,
    val mention: Color = textPrimary,
    val mentionViewerForeground: Color = mention,
    val mentionBackground: Color = Color.Transparent,
    val tableHeaderBackground: Color = background,
    val tableAlternateBackground: Color = codeBackground,
    val tableBorder: Color = border,
    val kbd: Color = textSecondary,
    val kbdBackground: Color = codeBackground,
    val kbdBorder: Color = border,
    val deletionBackground: Color = Color.Transparent,
    val deletionText: Color = textPrimary,
    val deletionNumberBackground: Color = deletionBackground,
    val deletionNumberText: Color = deletionText,
    val deletionEmphasisBackground: Color = deletionBackground,
    val deletionEmphasisText: Color = deletionText,
    val additionBackground: Color = Color.Transparent,
    val additionText: Color = textPrimary,
    val additionNumberBackground: Color = additionBackground,
    val additionNumberText: Color = additionText,
    val additionEmphasisBackground: Color = additionBackground,
    val additionEmphasisText: Color = additionText,
    val changedBackground: Color = Color.Transparent,
    val changedText: Color = textPrimary,
    val taskHeaderBackground: Color = tableHeaderBackground,
    val alertColors: Map<String, Color> = emptyMap(),
    val syntaxColors: Map<String, Color> = emptyMap(),
    val syntaxBackgrounds: Map<String, Color> = emptyMap(),
    val referenceIconColors: Map<String, Color> = emptyMap(),
) {
    fun alertColor(kind: String): Color = alertColors[kind.lowercase()] ?: link
}

/** em 比例与 assets 的 markdown.css 保持一致，基准字号和字体交给宿主校准。 */
data class HtmlTypography(
    val bodyFontFamily: FontFamily = FontFamily.Default,
    val codeFontFamily: FontFamily = FontFamily.Monospace,
    val bodyFontSize: TextUnit = 16.sp,
    val bodyLineHeightMultiplier: Float = 1.5f,
    val headingLineHeightMultiplier: Float = 1.25f,
    val codeFontScale: Float = 0.85f,
    val codeLineHeightMultiplier: Float = 1.45f,
    val kbdFontScale: Float = 0.7f,
    val kbdLineHeightMultiplier: Float = 1.2f,
    val suggestionFontSize: TextUnit = 12.sp,
    val suggestionLineHeight: TextUnit = 20.sp,
) {
    init {
        require(bodyFontSize.isSp && bodyFontSize.value > 0f)
        require(bodyLineHeightMultiplier > 0f)
        require(headingLineHeightMultiplier > 0f)
        require(codeFontScale > 0f && codeLineHeightMultiplier > 0f)
        require(kbdFontScale > 0f && kbdLineHeightMultiplier > 0f)
    }

    fun headingScale(level: Int): Float =
        when (level.coerceIn(1, 6)) {
            1 -> 1.6f
            2 -> 1.35f
            3 -> 1.2f
            4 -> 1.05f
            5 -> 0.9f
            else -> 0.85f
        }

    fun bodyStyle(color: Color = Color.Unspecified): TextStyle =
        textStyle(
            color = color,
            fontFamily = bodyFontFamily,
            fontSize = bodyFontSize,
            lineHeight = bodyFontSize * bodyLineHeightMultiplier,
        )

    fun headingStyle(level: Int, color: Color = Color.Unspecified): TextStyle {
        val size = bodyFontSize * headingScale(level)
        return textStyle(
            color = color,
            fontFamily = bodyFontFamily,
            fontSize = size,
            lineHeight = size * headingLineHeightMultiplier,
            fontWeight = FontWeight.SemiBold,
        )
    }

    fun codeStyle(color: Color = Color.Unspecified): TextStyle {
        val size = bodyFontSize * codeFontScale
        return textStyle(
            color = color,
            fontFamily = codeFontFamily,
            fontSize = size,
            lineHeight = size * codeLineHeightMultiplier,
        )
    }

    fun kbdStyle(color: Color = Color.Unspecified): TextStyle {
        val size = bodyFontSize * kbdFontScale
        return textStyle(
            color = color,
            fontFamily = codeFontFamily,
            fontSize = size,
            lineHeight = size * kbdLineHeightMultiplier,
        )
    }

    fun suggestionStyle(color: Color = Color.Unspecified): TextStyle =
        textStyle(
            color = color,
            fontFamily = codeFontFamily,
            fontSize = suggestionFontSize,
            lineHeight = suggestionLineHeight,
        )

    private fun textStyle(
        color: Color,
        fontFamily: FontFamily,
        fontSize: TextUnit,
        lineHeight: TextUnit,
        fontWeight: FontWeight = FontWeight.Normal,
    ): TextStyle =
        TextStyle(
            color = color,
            fontFamily = fontFamily,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle =
                LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
        )
}

/** px 型间距以 dp 表示；随字号变化的 CSS em 单独保留，供布局按实际字体尺寸换算。 */
data class HtmlSpacing(
    val blockGap: Dp = 16.dp,
    val paragraphGap: Dp = 16.dp,
    val headingTop: Dp = 24.dp,
    val headingBottom: Dp = 16.dp,
    val headingUnderlinePaddingEm: Float = 0.3f,
    val listIndentEm: Float = 1.5f,
    val listItemGapEm: Float = 0.25f,
    val orderedListIndent: Dp = 28.dp,
    val nestedListItemMargin: Dp = 16.dp,
    val quotePaddingEm: Float = 1f,
    val quoteBorderWidth: Dp = 2.dp,
    val quoteBorderRadius: Dp = 2.dp,
    val borderWidth: Dp = 1.dp,
    val tableCellHorizontal: Dp = 13.dp,
    val tableCellVertical: Dp = 6.dp,
    val codePadding: Dp = 12.dp,
    val codeRadius: Dp = 6.dp,
    val inlineCodeHorizontalEm: Float = 0.4f,
    val inlineCodeVerticalEm: Float = 0.2f,
    val kbdHorizontalEm: Float = 0.31f,
    val kbdVerticalEm: Float = 0.18f,
    val kbdRadiusEm: Float = 0.25f,
    val alertHorizontalPadding: Dp = 16.dp,
    val alertVerticalPadding: Dp = 8.dp,
    val alertBorderWidthEm: Float = 0.25f,
    val suggestionRadius: Dp = 4.dp,
    val suggestionPadding: Dp = 8.dp,
    val suggestionNumberMinWidth: Dp = 40.dp,
    val suggestionNumberHorizontal: Dp = 8.dp,
    val horizontalRuleVertical: Dp = 14.dp,
    val horizontalRuleHeightEm: Float = 0.25f,
    val definitionIndent: Dp = 16.dp,
)

/** 空文案表示宿主未提供相应操作，渲染器应隐藏可选按钮。 */
data class HtmlStrings(
    val copyCode: String = "",
    val copiedCode: String = "",
    val applySuggestion: String = "",
    val applyingSuggestion: String = "",
    val detailsExpand: String = "",
    val detailsCollapse: String = "",
    val renderingFailed: String = "",
    val unsupportedContent: String = "",
    val retry: String = "",
)

data class HtmlStyles(
    val palette: HtmlPalette,
    val typography: HtmlTypography = HtmlTypography(),
    val spacing: HtmlSpacing = HtmlSpacing(),
    val strings: HtmlStrings = HtmlStrings(),
) {
    fun bodyStyle(): TextStyle = typography.bodyStyle(palette.textPrimary)

    fun headingStyle(level: Int): TextStyle =
        typography.headingStyle(
            level = level,
            color = if (level == 6) palette.h6 else palette.heading,
        )

    fun codeStyle(): TextStyle = typography.codeStyle(palette.textPrimary)

    fun kbdStyle(): TextStyle = typography.kbdStyle(palette.kbd)

    fun suggestionStyle(): TextStyle = typography.suggestionStyle(palette.textPrimary)

    fun alertColor(kind: String): Color = palette.alertColor(kind)

    /** 仅映射已知 GitHub class 和宿主传入的高亮规则，不解释任意 CSS。 */
    fun inlineStyleForClasses(
        classes: Set<String>,
        ancestorClasses: List<Set<String>> = emptyList(),
    ): SpanStyle {
        var style = SpanStyle()
        if (classes.any { it in MutedClasses }) {
            style = style.merge(SpanStyle(palette.textSecondary))
        }
        if (classes.any { it in MentionClasses }) {
            style =
                style.merge(SpanStyle(color = palette.mention, fontWeight = FontWeight.SemiBold))
        }
        if ("issue-link" in classes) {
            style =
                style.merge(SpanStyle(color = palette.mention, fontWeight = FontWeight.SemiBold))
        }
        if ("issue-shorthand" in classes) {
            style =
                style.merge(SpanStyle(color = palette.textTertiary, fontWeight = FontWeight.Normal))
        }
        if ("octicon" in classes || classes.any { it.startsWith("octicon-") }) {
            palette.referenceIconColors.entries
                .lastOrNull { it.key in classes }
                ?.value
                ?.let { style = style.merge(SpanStyle(color = it)) }
        }
        if ("user-mention-viewer" in classes) {
            style =
                style.merge(
                    SpanStyle(
                        color = palette.mentionViewerForeground,
                        background = palette.mentionBackground,
                    ),
                )
        }
        if (classes.any { it in BoldSyntaxClasses }) {
            style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        }
        if ("pl-mi" in classes) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if ("pl-corl" in classes) {
            style = style.merge(SpanStyle(textDecoration = TextDecoration.Underline))
        }
        when {
            "pl-md" in classes ->
                style =
                    style.merge(
                        SpanStyle(
                            color = palette.deletionText,
                            background = palette.deletionBackground,
                        ),
                    )
            "pl-mi1" in classes ->
                style =
                    style.merge(
                        SpanStyle(
                            color = palette.additionText,
                            background = palette.additionBackground,
                        ),
                    )
            "pl-mc" in classes ->
                style =
                    style.merge(
                        SpanStyle(
                            color = palette.changedText,
                            background = palette.changedBackground,
                        ),
                    )
        }
        matchingColor(palette.syntaxColors, classes, ancestorClasses)?.let {
            style = style.merge(SpanStyle(color = it))
        }
        matchingColor(palette.syntaxBackgrounds, classes, ancestorClasses)?.let {
            style = style.merge(SpanStyle(background = it))
        }
        if ("x" in classes) {
            val addition =
                ancestorClasses.asReversed().firstNotNullOfOrNull { ancestor ->
                    when {
                        "blob-code-addition" in ancestor -> true
                        "blob-code-deletion" in ancestor -> false
                        else -> null
                    }
                }
            if (addition != null) {
                style =
                    style.merge(
                        SpanStyle(
                            color =
                                if (addition) palette.additionEmphasisText
                                else palette.deletionEmphasisText,
                            background =
                                if (addition) palette.additionEmphasisBackground
                                else palette.deletionEmphasisBackground,
                        ),
                    )
            }
        }
        if ("pl-cce" in classes && ancestorClasses.any { "pl-sr" in it }) {
            style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        }
        return style
    }

    private fun matchingColor(
        rules: Map<String, Color>,
        classes: Set<String>,
        ancestors: List<Set<String>>,
    ): Color? {
        var matched: Color? = null
        var specificity = -1
        for ((selector, color) in rules) {
            val parts = selector.trim().split(Whitespace).filter(String::isNotEmpty)
            if (parts.isEmpty() || !parts.last().matchesClasses(classes)) continue
            var before = ancestors.size
            var matches = true
            for (part in parts.dropLast(1).asReversed()) {
                val index = (before - 1 downTo 0).firstOrNull { part.matchesClasses(ancestors[it]) }
                if (index == null) {
                    matches = false
                    break
                }
                before = index
            }
            // 宿主按原 CSS 顺序提供规则，同等选择器强度由后出现的规则覆盖。
            if (matches && parts.size >= specificity) {
                matched = color
                specificity = parts.size
            }
        }
        return matched
    }
}

private val MutedClasses = setOf("color-text-secondary", "color-fg-muted", "text-gray")
private val MentionClasses = setOf("team-mention", "user-mention")
private val BoldSyntaxClasses = setOf("pl-mh", "pl-ms", "pl-mb", "pl-mdr")
private val Whitespace = Regex("\\s+")

private fun String.matchesClasses(classes: Set<String>): Boolean {
    val required = removePrefix(".").split('.').filter(String::isNotEmpty)
    return required.isNotEmpty() && classes.containsAll(required)
}
