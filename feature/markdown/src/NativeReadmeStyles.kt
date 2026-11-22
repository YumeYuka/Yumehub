package moe.yumeyuka.yumehub.feature.markdown

import android.content.res.AssetManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlassColors
import moe.yumeyuka.markdown.compose.HtmlPalette
import moe.yumeyuka.markdown.compose.HtmlStrings
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.HtmlTypography
import moe.yumeyuka.yumehub.locale.GithubStrings_Markdown

/**
 * 原生正文的样式基线。
 *
 * 颜色取自本模块 assets 内官方 colors_*.css / syntax*.css 的同一批变量，与 WebView 参考共用一份
 * 来源，避免两套渲染器各自漂移；变量缺失或无法解析时回退到 Glasmio 语义色，不中断渲染。
 */
internal object NativeReadmeStyles {
    private val cache = HashMap<Boolean, HtmlPalette>()

    @Synchronized
    fun build(
        assets: AssetManager,
        colors: GlassColors,
        strings: GithubStrings_Markdown,
        bodyFont: FontFamily?,
    ): HtmlStyles {
        return HtmlStyles(
            palette = cache.getOrPut(colors.isDark) { parse(assets, colors) },
            typography =
                HtmlTypography(
                    bodyFontFamily = bodyFont ?: FontFamily.Default,
                    bodyFontSize = 16.sp,
                ),
            strings = strings.toHtmlStrings(),
        )
    }

    private fun parse(
        assets: AssetManager,
        colors: GlassColors,
    ): HtmlPalette {
        val dark = colors.isDark
        fun asset(name: String) =
            assets.openMarkdownAsset(name).bufferedReader().use { it.readText() }
        val tokens = cssVariables(asset(if (dark) "colors_dark.css" else "colors_light.css"))
        val syntax = syntaxRules(asset(if (dark) "syntax_dark.css" else "syntax.css"), tokens)

        fun token(name: String): Color? =
            tokens[name]?.let { raw ->
                runCatching { parseCssColor(resolve(tokens, raw)) }.getOrNull()
            }

        fun color(name: String, fallback: Color): Color = token(name) ?: fallback

        val background = token("--appBackground") ?: Color.Transparent
        val border = color("--border", colors.separator)
        val palette =
            HtmlPalette(
                isDark = dark,
                textPrimary = color("--textPrimary", colors.content),
                textSecondary = color("--textSecondary", colors.secondaryContent),
                textTertiary = color("--textTertiary", colors.tertiaryContent),
                background = background,
                link = color("--link", colors.accent),
                border = border,
                horizontalRule = color("--hr-background", border),
                codeBackground = color("--pre-background", colors.elevatedBackground),
                heading = color("--textPrimary", colors.content),
                h6 = color("--h6-color", colors.secondaryContent),
                quote = color("--blockquote-color", colors.secondaryContent),
                quoteBorder = color("--blockquote-border", border),
                mention = color("--mention-color", colors.content),
                mentionViewerForeground = color("--user-mention-color-fg", colors.content),
                mentionBackground = color("--user-mention-color-bg", Color.Transparent),
                tableHeaderBackground = background,
                tableAlternateBackground = color("--tr-alt-background", background),
                tableBorder = color("--thead-border", border),
                kbd = color("--kbd-color", colors.secondaryContent),
                kbdBackground = color("--kbd-background", colors.elevatedBackground),
                kbdBorder = color("--kbd-border", border),
                deletionBackground = color("--diffLineDeletionBackground", Color.Transparent),
                deletionText = color("--diffLineNumberDeletionText", colors.content),
                deletionNumberBackground =
                    color("--diffLineNumberDeletionBackground", Color.Transparent),
                deletionNumberText = color("--diffLineNumberDeletionText", colors.secondaryContent),
                deletionEmphasisBackground =
                    color("--suggestedChangeDeletionBackground", Color.Transparent),
                deletionEmphasisText = color("--suggestedChangeDeletionText", colors.content),
                additionBackground = color("--diffLineAdditionBackground", Color.Transparent),
                additionText = color("--diffLineNumberAdditionText", colors.content),
                additionNumberBackground =
                    color("--diffLineNumberAdditionBackground", Color.Transparent),
                additionNumberText = color("--diffLineNumberAdditionText", colors.secondaryContent),
                additionEmphasisBackground =
                    color("--suggestedChangeAdditionBackground", Color.Transparent),
                additionEmphasisText = color("--suggestedChangeAdditionText", colors.content),
                taskHeaderBackground = color("--tlb-header-bg", background),
                alertColors =
                    mapOf(
                        "note" to color("--color-accent-fg", colors.accent),
                        "tip" to color("--color-success-fg", colors.accent),
                        "important" to color("--color-done-fg", colors.accent),
                        "warning" to color("--color-attention-fg", colors.accent),
                        "caution" to color("--color-danger-fg", colors.destructive),
                    ),
                syntaxColors = syntax.first,
                syntaxBackgrounds = syntax.second,
                referenceIconColors =
                    mapOf(
                        "octicon-issue-opened" to color("--color-issue-open", colors.content),
                        "octicon-issue-closed" to color("--color-issue-closed", colors.content),
                        "octicon-git-pull-request" to color("--color-pr-open", colors.content),
                        "octicon-git-pull-request-closed" to
                            color("--color-pr-closed", colors.content),
                        "octicon-git-merge" to color("--color-pr-merged", colors.content),
                        "octicon-git-pull-request-draft" to
                            color("--color-pr-draft", colors.content),
                        "octicon-skip" to color("--color-skip", colors.secondaryContent),
                    ),
            )
        return palette
    }

    private fun cssVariables(css: String): Map<String, String> =
        VariablePattern.findAll(css.replace(CommentPattern, "")).associate {
            it.groupValues[1] to it.groupValues[2].trim()
        }

    private fun resolve(tokens: Map<String, String>, value: String, depth: Int = 0): String {
        if (!value.startsWith("var(")) return value
        require(depth < 16) { "CSS 变量存在循环引用" }
        val name = value.removePrefix("var(").removeSuffix(")").trim()
        return resolve(tokens, tokens.getValue(name), depth + 1)
    }

    /** 宿主按原 CSS 顺序保留规则，同级选择器由后出现的覆盖。 */
    private fun syntaxRules(
        css: String,
        tokens: Map<String, String>,
    ): Pair<Map<String, Color>, Map<String, Color>> {
        val colors = linkedMapOf<String, Color>()
        val backgrounds = linkedMapOf<String, Color>()
        fun parse(raw: String): Color? = runCatching {
            parseCssColor(resolve(tokens, raw))
        }
            .getOrNull()
        for (rule in RulePattern.findAll(css.replace(CommentPattern, ""))) {
            val declarations =
                rule.groupValues[2]
                    .split(';')
                    .mapNotNull { declaration ->
                        val pair = declaration.split(':', limit = 2)
                        if (pair.size == 2) pair[0].trim() to pair[1].trim() else null
                    }
                    .toMap()
            val foreground = declarations["color"]?.let(::parse)
            val background =
                (declarations["background-color"] ?: declarations["background"])?.let(::parse)
            for (selector in rule.groupValues[1].split(',').map(String::trim)) {
                if (selector.isEmpty()) continue
                foreground?.let { colors[selector] = it }
                background?.let { backgrounds[selector] = it }
            }
        }
        return colors to backgrounds
    }

    private fun parseCssColor(raw: String): Color {
        val value = raw.trim()
        if (value.startsWith("rgba(") || value.startsWith("rgb(")) {
            val parts = value.substringAfter('(').substringBefore(')').split(',').map(String::trim)
            require(parts.size == 3 || parts.size == 4) { "无法解析的 rgb 颜色" }
            return Color(
                red = parts[0].toFloat() / 255f,
                green = parts[1].toFloat() / 255f,
                blue = parts[2].toFloat() / 255f,
                alpha = parts.getOrNull(3)?.toFloat() ?: 1f,
            )
        }
        return Color(android.graphics.Color.parseColor(value))
    }

    private fun GithubStrings_Markdown.toHtmlStrings(): HtmlStrings =
        HtmlStrings(
            copyCode = CopyCode,
            copiedCode = CopiedCode,
            detailsExpand = DetailsExpand,
            detailsCollapse = DetailsCollapse,
            renderingFailed = RenderingFailed,
            retry = Retry,
        )

    private val CommentPattern = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val VariablePattern = Regex("(--[A-Za-z0-9-]+)\\s*:\\s*([^;]+);")
    private val RulePattern = Regex("([^{}]+)\\{([^{}]*)\\}")
}
