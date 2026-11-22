package moe.yumeyuka.yumehub.feature.markdown

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.HtmlPalette
import moe.yumeyuka.markdown.compose.HtmlStrings
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.HtmlTypography
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

internal data class NativeMarkdownStyleState(val styles: HtmlStyles?, val failed: Boolean)

/** 参考资源仍属于应用；新库只取得解析后的样式值与字体，不捆绑 APK 提取资产。 */
@Composable
internal fun rememberNativeMarkdownStyles(
    transparentBackground: Boolean = false,
    retryKey: Any? = null,
): NativeMarkdownStyleState {
    val assets = LocalContext.current.applicationContext.assets
    val colors = GlasmioTheme.colors
    val strings = LocalGithubStrings.current
    val dark = colors.isDark
    var reference by
        remember(assets, dark) { mutableStateOf(NativeMarkdownStyleAssets.cached(dark)) }
    var failed by remember(assets, dark, retryKey) { mutableStateOf(false) }
    LaunchedEffect(assets, dark, retryKey) {
        if (reference != null) return@LaunchedEffect
        try {
            reference = withContext(Dispatchers.IO) { NativeMarkdownStyleAssets.load(assets, dark) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val loaded = reference ?: return NativeMarkdownStyleState(null, failed)
    fun value(name: String) = loaded.color(name)
    val syntaxColors = loaded.syntaxColors
    val syntaxBackgrounds = loaded.syntaxBackgrounds
    val palette =
        HtmlPalette(
            textPrimary = value("textPrimary"),
            textSecondary = value("textSecondary"),
            background =
                if (transparentBackground) Color.Transparent else colors.elevatedBackground,
            link = value("link"),
            border = value("border"),
            codeBackground = value("code-background"),
            isDark = dark,
            textTertiary = value("textTertiary"),
            heading = value("textPrimary"),
            h6 = value("h6-color"),
            horizontalRule = value("hr-background"),
            quote = value("blockquote-color"),
            quoteBorder = value("blockquote-border"),
            mention = value("mention-color"),
            mentionViewerForeground = value("user-mention-color-fg"),
            mentionBackground = value("user-mention-color-bg"),
            tableAlternateBackground = value("tr-alt-background"),
            tableBorder = value("thead-border"),
            kbd = value("kbd-color"),
            kbdBackground = value("kbd-background"),
            kbdBorder = value("kbd-border"),
            deletionBackground = value("diffLineDeletionBackground"),
            deletionNumberBackground = value("diffLineNumberDeletionBackground"),
            deletionNumberText = value("diffLineNumberDeletionText"),
            deletionEmphasisBackground = value("suggestedChangeDeletionBackground"),
            deletionEmphasisText = value("suggestedChangeDeletionText"),
            additionBackground = value("diffLineAdditionBackground"),
            additionNumberBackground = value("diffLineNumberAdditionBackground"),
            additionNumberText = value("diffLineNumberAdditionText"),
            additionEmphasisBackground = value("suggestedChangeAdditionBackground"),
            additionEmphasisText = value("suggestedChangeAdditionText"),
            changedBackground = requireNotNull(syntaxBackgrounds[".pl-mc"]),
            changedText = requireNotNull(syntaxColors[".pl-mc"]),
            taskHeaderBackground = value("tlb-header-bg"),
            alertColors =
                mapOf(
                    "note" to value("color-accent-fg"),
                    "tip" to value("color-success-fg"),
                    "important" to value("color-done-fg"),
                    "warning" to value("color-attention-fg"),
                    "caution" to value("color-danger-fg"),
                ),
            syntaxColors = syntaxColors,
            syntaxBackgrounds = syntaxBackgrounds,
            referenceIconColors =
                mapOf(
                    "octicon-issue-opened" to value("color-issue-open"),
                    "octicon-issue-closed" to value("color-issue-closed"),
                    "octicon-git-pull-request" to value("color-pr-open"),
                    "octicon-git-pull-request-closed" to value("color-pr-closed"),
                    "octicon-git-merge" to value("color-pr-merged"),
                    "octicon-git-pull-request-draft" to value("color-pr-draft"),
                    "octicon-skip" to value("color-skip"),
                    "color-fg-accent" to value("color-accent-fg"),
                    "color-fg-attention" to value("color-attention-fg"),
                    "color-fg-done" to value("color-done-fg"),
                    "color-text-secondary" to value("textSecondary"),
                    "color-fg-muted" to value("textSecondary"),
                ),
        )
    return NativeMarkdownStyleState(
        styles =
            HtmlStyles(
                palette = palette,
                typography =
                    HtmlTypography(
                        bodyFontFamily =
                            GlasmioTheme.typography.bodyLarge.fontFamily ?: FontFamily.Default,
                        codeFontFamily = FontFamily.Monospace,
                        bodyFontSize = 16.sp,
                    ),
                strings =
                    HtmlStrings(
                        copyCode = strings.Markdown.CopyCode,
                        copiedCode = strings.Markdown.CopiedCode,
                        applySuggestion = strings.Markdown.ApplySuggestion,
                        applyingSuggestion = strings.Markdown.ApplyingSuggestion,
                        detailsExpand = strings.Markdown.DetailsExpand,
                        detailsCollapse = strings.Markdown.DetailsCollapse,
                        renderingFailed = strings.Markdown.RenderingFailed,
                        unsupportedContent = strings.Markdown.UnsupportedContent,
                        retry = strings.Common.Retry,
                    ),
            ),
        failed = false,
    )
}

private data class NativeMarkdownReferenceStyle(
    val variables: Map<String, String>,
    val syntaxColors: Map<String, Color>,
    val syntaxBackgrounds: Map<String, Color>,
) {
    fun color(name: String): Color = parseReferenceColor(resolveVariable(name, emptySet()))

    private fun resolveVariable(name: String, visited: Set<String>): String {
        check(name !in visited) { "循环引用的 Markdown 样式变量" }
        val raw = requireNotNull(variables[name]) { "缺少 Markdown 样式变量: $name" }
        return ReferenceVariable.replace(raw) { match ->
            resolveVariable(match.groupValues[1], visited + name)
        }
    }
}

/** 只缓存 CSS 资源值，正文随宿主主题取字体。 */
private object NativeMarkdownStyleAssets {
    private val lock = Any()
    private val cache = mutableMapOf<Boolean, NativeMarkdownReferenceStyle>()

    fun cached(dark: Boolean): NativeMarkdownReferenceStyle? = synchronized(lock) { cache[dark] }

    fun load(assets: AssetManager, dark: Boolean): NativeMarkdownReferenceStyle =
        synchronized(lock) {
            cache[dark]?.let {
                return@synchronized it
            }
            fun read(name: String) =
                assets.openMarkdownAsset(name).bufferedReader().use { it.readText() }
            val css =
                withoutReferenceComments(read(if (dark) "colors_dark.css" else "colors_light.css"))
            val variables =
                ReferenceDeclaration.findAll(css).associate {
                    it.groupValues[1] to it.groupValues[2].trim()
                }
            val syntax =
                withoutReferenceComments(read(if (dark) "syntax_dark.css" else "syntax.css"))
            val syntaxColors = linkedMapOf<String, Color>()
            val syntaxBackgrounds = linkedMapOf<String, Color>()
            for (rule in ReferenceRule.findAll(syntax)) {
                val declarations =
                    rule.groupValues[2]
                        .split(';')
                        .mapNotNull { declaration ->
                            val separator = declaration.indexOf(':')
                            if (separator < 0) null
                            else
                                declaration.take(separator).trim() to
                                    declaration.drop(separator + 1).trim()
                        }
                        .toMap()
                for (selector in rule.groupValues[1].split(',').map(String::trim)) {
                    if (selector.isEmpty()) continue
                    declarations["color"]?.let { syntaxColors[selector] = parseReferenceColor(it) }
                    declarations["background-color"]?.let {
                        syntaxBackgrounds[selector] = parseReferenceColor(it)
                    }
                }
            }
            NativeMarkdownReferenceStyle(variables, syntaxColors, syntaxBackgrounds).also {
                reference ->
                // 在后台先验证所有颜色变量，避免构图期间才发现资产损坏。
                variables.keys.filter { it != "code-font" }.forEach(reference::color)
                cache[dark] = reference
            }
        }
}

private fun parseReferenceColor(raw: String): Color {
    val value = raw.trim()
    if (value.startsWith('#')) {
        val digits = value.drop(1)
        val expanded =
            when (digits.length) {
                3,
                4 -> digits.flatMap { listOf(it, it) }.joinToString("")
                6,
                8 -> digits
                else -> error("无效 Markdown 参考颜色")
            }
        val red = expanded.take(2).toInt(16) / 255f
        val green = expanded.substring(2, 4).toInt(16) / 255f
        val blue = expanded.substring(4, 6).toInt(16) / 255f
        val alpha = if (expanded.length == 8) expanded.takeLast(2).toInt(16) / 255f else 1f
        return Color(red, green, blue, alpha)
    }
    val match = ReferenceRgb.matchEntire(value) ?: error("不支持的 Markdown 参考颜色")
    val parts = match.groupValues[1].split(',').map { it.trim().toFloat() }
    require(parts.size == 3 || parts.size == 4)
    require(parts.take(3).all { it in 0f..255f })
    val alpha = parts.getOrElse(3) { 1f }
    require(alpha in 0f..1f)
    return Color(parts[0] / 255f, parts[1] / 255f, parts[2] / 255f, alpha)
}

private fun withoutReferenceComments(value: String): String = ReferenceComment.replace(value, "")

private val ReferenceComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
private val ReferenceDeclaration = Regex("--([A-Za-z0-9-]+)\\s*:\\s*([^;]+);")
private val ReferenceVariable = Regex("var\\(\\s*--([A-Za-z0-9-]+)\\s*\\)")
private val ReferenceRule = Regex("([^{}]+)\\{([^{}]*)\\}")
private val ReferenceRgb = Regex("rgba?\\(([^)]+)\\)")
