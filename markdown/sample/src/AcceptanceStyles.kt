package moe.yumeyuka.markdown.sample

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import moe.yumeyuka.markdown.compose.HtmlPalette
import moe.yumeyuka.markdown.compose.HtmlStrings
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.HtmlTypography

/** 测试宿主直接读取既有颜色资源，便于核对原生颜色是否来自同一基线。 */
object AcceptanceStyles {
    fun load(context: Context, dark: Boolean): HtmlStyles {
        val assets = context.assets
        fun asset(name: String) =
            assets.open("github-markdown/webview/$name").bufferedReader().use { it.readText() }
        val css = asset(if (dark) "colors_dark.css" else "colors_light.css")
        val variables =
            Regex("(--[A-Za-z0-9-]+)\\s*:\\s*([^;]+);")
                .findAll(css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), ""))
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
        fun resolve(value: String, depth: Int = 0): String {
            require(depth < 16) { "CSS token cycle" }
            val key = value.removePrefix("var(").removeSuffix(")").trim()
            return if (value.startsWith("var(")) resolve(variables.getValue(key), depth + 1)
            else value
        }
        fun color(name: String): Color = parseColor(resolve(variables.getValue(name)))
        val syntax = asset(if (dark) "syntax_dark.css" else "syntax.css")
        val syntaxRules = syntaxRules(syntax, ::resolve)
        val font =
            FontFamily(
                (100..900 step 100).flatMap { weight ->
                    val axis = FontVariation.Settings(FontVariation.weight(weight))
                    listOf(
                        Font(
                            "fonts/Inter.ttf",
                            assets,
                            FontWeight(weight),
                            variationSettings = axis,
                        ),
                        Font(
                            "fonts/Inter-Italic.ttf",
                            assets,
                            FontWeight(weight),
                            FontStyle.Italic,
                            variationSettings = axis,
                        ),
                    )
                },
            )
        return HtmlStyles(
            palette =
                HtmlPalette(
                    isDark = dark,
                    textPrimary = color("--textPrimary"),
                    textSecondary = color("--textSecondary"),
                    textTertiary = color("--textTertiary"),
                    background = color("--appBackground"),
                    link = color("--link"),
                    border = color("--border"),
                    horizontalRule = color("--hr-background"),
                    codeBackground = color("--pre-background"),
                    heading = color("--textPrimary"),
                    h6 = color("--h6-color"),
                    quote = color("--blockquote-color"),
                    quoteBorder = color("--blockquote-border"),
                    mention = color("--mention-color"),
                    mentionViewerForeground = color("--user-mention-color-fg"),
                    mentionBackground = color("--user-mention-color-bg"),
                    tableHeaderBackground = color("--appBackground"),
                    tableAlternateBackground = color("--tr-alt-background"),
                    tableBorder = color("--thead-border"),
                    kbd = color("--kbd-color"),
                    kbdBackground = color("--kbd-background"),
                    kbdBorder = color("--kbd-border"),
                    deletionBackground = color("--diffLineDeletionBackground"),
                    deletionText = color("--diffLineNumberDeletionText"),
                    deletionNumberBackground = color("--diffLineNumberDeletionBackground"),
                    deletionNumberText = color("--diffLineNumberDeletionText"),
                    deletionEmphasisBackground = color("--suggestedChangeDeletionBackground"),
                    deletionEmphasisText = color("--suggestedChangeDeletionText"),
                    additionBackground = color("--diffLineAdditionBackground"),
                    additionText = color("--diffLineNumberAdditionText"),
                    additionNumberBackground = color("--diffLineNumberAdditionBackground"),
                    additionNumberText = color("--diffLineNumberAdditionText"),
                    additionEmphasisBackground = color("--suggestedChangeAdditionBackground"),
                    additionEmphasisText = color("--suggestedChangeAdditionText"),
                    taskHeaderBackground = color("--tlb-header-bg"),
                    alertColors =
                        mapOf(
                            "note" to color("--color-accent-fg"),
                            "tip" to color("--color-success-fg"),
                            "important" to color("--color-done-fg"),
                            "warning" to color("--color-attention-fg"),
                            "caution" to color("--color-danger-fg"),
                        ),
                    syntaxColors = syntaxRules.first,
                    syntaxBackgrounds = syntaxRules.second,
                    referenceIconColors =
                        mapOf(
                            "octicon-issue-opened" to color("--color-issue-open"),
                            "octicon-issue-closed" to color("--color-issue-closed"),
                            "octicon-git-pull-request" to color("--color-pr-open"),
                            "octicon-git-pull-request-closed" to color("--color-pr-closed"),
                            "octicon-git-pull-request-draft" to color("--color-pr-draft"),
                            "octicon-git-merge" to color("--color-pr-merged"),
                            "octicon-skip" to color("--color-skip"),
                        ),
                ),
            typography = HtmlTypography(bodyFontFamily = font, bodyFontSize = 16.sp),
            // 文案是合成验收内容，英文与原 assets 的测试按钮基线一致。
            strings =
                HtmlStrings(
                    copyCode = "Copy",
                    copiedCode = "Copied",
                    applySuggestion = "Commit",
                    applyingSuggestion = "Applying",
                    detailsExpand = "Expand",
                    detailsCollapse = "Collapse",
                    renderingFailed = "Fixture rendering failed",
                    unsupportedContent = "Unsupported fixture",
                    retry = "Retry fixture",
                ),
        )
    }

    private fun syntaxRules(
        css: String,
        resolve: (String) -> String,
    ): Pair<Map<String, Color>, Map<String, Color>> {
        val colors = linkedMapOf<String, Color>()
        val backgrounds = linkedMapOf<String, Color>()
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (rule in Regex("([^{}]+)\\{([^{}]*)\\}").findAll(clean)) {
            val selectors = rule.groupValues[1].split(',').map(String::trim)
            val declarations =
                rule.groupValues[2]
                    .split(';')
                    .mapNotNull { declaration ->
                        val pair = declaration.split(':', limit = 2)
                        if (pair.size == 2) pair[0].trim() to pair[1].trim() else null
                    }
                    .toMap()
            for (selector in selectors) {
                declarations["color"]?.let { colors[selector] = parseColor(resolve(it)) }
                (declarations["background-color"] ?: declarations["background"])?.let {
                    backgrounds[selector] = parseColor(resolve(it))
                }
            }
        }
        return colors to backgrounds
    }

    private fun parseColor(raw: String): Color {
        if (raw.startsWith("rgba(")) {
            val parts = raw.removePrefix("rgba(").removeSuffix(")").split(',').map(String::trim)
            require(parts.size == 4)
            return Color(
                red = parts[0].toFloat() / 255f,
                green = parts[1].toFloat() / 255f,
                blue = parts[2].toFloat() / 255f,
                alpha = parts[3].toFloat(),
            )
        }
        return Color(android.graphics.Color.parseColor(raw))
    }
}
