package moe.yumeyuka.markdown.compose

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.launch
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlSemanticKind
import moe.yumeyuka.markdown.core.HtmlText

/** 同一段的格式片段留在同一个 BasicText 中，保留原生换行、链接及跨片段选择。 */
@Composable
internal fun NativeHtmlText(
    nodes: List<HtmlNode>,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
    preserveWhitespace: Boolean = context.inCode,
) {
    val imageLines =
        remember(nodes, context.excludedImageKeys, context.style.palette.isDark) {
            nativeHtmlImageParagraphLines(nodes, context)
        }
    if (imageLines != null && context.textExclusion == null) {
        NativeHtmlImageParagraph(imageLines, context, modifier)
        return
    }
    val latestContext by rememberUpdatedState(context)
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()
    val measurements = remember(nodes) { mutableStateMapOf<String, InlineMeasurement>() }
    val intrinsicImages = remember(nodes) { mutableStateMapOf<String, InlineIntrinsicImage>() }
    val tabWidths = remember(nodes) { mutableStateMapOf<String, Float>() }
    val openLink: (String) -> Unit = remember {
        { url ->
            if (url.startsWith('#')) latestContext.onOpenAnchor(url)
            else latestContext.actions.onOpenLink(url)
        }
    }
    val baselineShift: (SpanStyle, Boolean) -> BaselineShift =
        remember(resolver, density.density, density.fontScale, context.style.typography) {
            { style, superscript ->
                scriptBaselineShift(style, superscript, context, density, resolver)
            }
        }
    val baseContent by
        remember(
            nodes,
            context.style,
            context.textStyle,
            context.inCode,
            context.inHeading,
            context.ancestorClasses,
            context.excludedImageKeys,
            preserveWhitespace,
            baselineShift,
        ) {
            derivedStateOf {
                NativeInlineBuilder(context, openLink, baselineShift = baselineShift)
                    .build(nodes, preserveWhitespace)
            }
        }
    val content = baseContent
    val anchorKeys = remember(nodes) { inlineAnchorKeys(nodes) }
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(anchorKeys, context.anchorRequesters, requester) {
        anchorKeys.forEach { context.anchorRequesters[it] = requester }
        onDispose {
            anchorKeys.forEach { key ->
                if (context.anchorRequesters[key] === requester) {
                    context.anchorRequesters.remove(key)
                }
            }
        }
    }
    val rootElement = nodes.singleOrNull() as? HtmlElement
    val textStyle =
        context.textStyle.copy(
            textAlign = rootElement?.let(::nativeHtmlTextAlign) ?: context.textStyle.textAlign,
            textDirection =
                when (rootElement?.attr("dir")?.lowercase()) {
                    "rtl" -> TextDirection.Rtl
                    "ltr" -> TextDirection.Ltr
                    else -> context.textStyle.textDirection
                },
        )
    val maxLines =
        if (context.inCode && preserveWhitespace) {
            val raw = content.text.text
            (raw.count { it == '\n' } + if (raw.endsWith('\n')) 0 else 1).coerceAtLeast(1)
        } else Int.MAX_VALUE
    if (content.objects.isEmpty() && content.tabs.isEmpty()) {
        val exclusion = context.textExclusion
        if (exclusion != null && !context.inCode) {
            NativeHtmlFlowText(
                content.text,
                textStyle,
                exclusion,
                modifier.bringIntoViewRequester(requester),
            )
            return
        }
        BasicText(
            text = content.text,
            modifier = modifier.bringIntoViewRequester(requester),
            style = textStyle,
            softWrap = !context.inCode,
            maxLines = maxLines,
        )
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val tabIntervals =
        content.tabs
            .map { it.style }
            .distinct()
            .associateWith { style ->
                textMeasurer
                    .measure(
                        text = "        ",
                        style = textStyle.merge(style),
                        softWrap = false,
                    )
                    .multiParagraph
                    .maxIntrinsicWidth
                    .coerceAtLeast(1f)
            }
    val placeholderIndexes =
        remember(content.placeholderIds) {
            content.placeholderIds.withIndex().associate { it.value to it.index }
        }
    val exclusionPadding = with(density) { (context.textExclusion?.width ?: 0).toDp() }
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(
                start = if (context.textExclusion?.right == false) exclusionPadding else 0.dp,
                end = if (context.textExclusion?.right == true) exclusionPadding else 0.dp,
            ),
    ) {
        val availableWidth =
            if (maxWidth.value.isFinite() && maxWidth > 0.dp) maxWidth
            else context.cssTypography.emSpacing(context.textStyle.fontSize, 32f)
        val inlineContent = buildMap {
            content.objects.forEach { item ->
                val size =
                    inlineObjectSize(
                        item,
                        measurements[item.id],
                        intrinsicImages[item.id],
                        availableWidth,
                        density,
                        context,
                    )
                val align =
                    when (item.kind) {
                        InlineObjectKind.ICON -> PlaceholderVerticalAlign.TextTop
                        InlineObjectKind.TASK -> PlaceholderVerticalAlign.TextCenter
                        else ->
                            when (item.element.cssProperty("vertical-align")) {
                                "middle" -> PlaceholderVerticalAlign.TextCenter
                                "top",
                                "text-top" -> PlaceholderVerticalAlign.TextTop
                                "bottom",
                                "text-bottom" -> PlaceholderVerticalAlign.TextBottom
                                else -> PlaceholderVerticalAlign.AboveBaseline
                            }
                    }
                put(
                    item.id,
                    InlineTextContent(
                        Placeholder(
                            width = with(density) { size.first.toSp() },
                            height = with(density) { size.second.toSp() },
                            placeholderVerticalAlign = align,
                        ),
                    ) {
                        val current = latestContext.withInlineStyle(item.style)
                        val inlineModifier =
                            Modifier.fillMaxSize()
                                .then(
                                    item.link?.let { target ->
                                        Modifier.clickable { openLink(target) }
                                    } ?: Modifier,
                                )
                        when (item.kind) {
                            InlineObjectKind.IMAGE ->
                                NativeHtmlImage(
                                    item.element,
                                    current,
                                    inlineModifier,
                                    inline = true,
                                    picture = item.picture,
                                    onIntrinsicSize = { width, height ->
                                        if (width > 0 && height > 0) {
                                            scope.launch {
                                                val value = InlineIntrinsicImage(width, height)
                                                if (intrinsicImages[item.id] != value) {
                                                    intrinsicImages[item.id] = value
                                                }
                                            }
                                        }
                                    },
                                )
                            InlineObjectKind.TASK ->
                                NativeHtmlTask(item.element, current, inlineModifier)
                            InlineObjectKind.ICON ->
                                NativeHtmlIcon(item.element, current, inlineModifier)
                            InlineObjectKind.MEDIA ->
                                NativeHtmlMedia(item.element, current, inlineModifier)
                        }
                    },
                )
            }
            content.tabs.forEach { tab ->
                val width = tabWidths[tab.id] ?: tabIntervals.getValue(tab.style)
                put(
                    tab.id,
                    InlineTextContent(
                        Placeholder(
                            width = with(density) { width.toDp().toSp() },
                            height = 0.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                        ),
                    ) {
                        DisableSelection { Spacer(Modifier.fillMaxSize()) }
                    },
                )
            }
        }
        // Android 的 Minimum 行高仍复用第一行的 metrics，后续高图片会被覆盖。
        // 图片行让 ReplacementSpan 自然撑高；无图片的硬换行仍保留正文行高。
        val mediaObjects =
            content.objects.filter {
                it.kind == InlineObjectKind.IMAGE || it.kind == InlineObjectKind.MEDIA
            }
        val paragraphText =
            if (mediaObjects.isEmpty()) content.text
            else {
                AnnotatedString.Builder(content.text)
                    .apply {
                        val imageRanges =
                            content.text.getStringAnnotations(0, content.text.length).filter { range
                                ->
                                mediaObjects.any { it.id == range.item }
                            }
                        var start = 0
                        while (start < content.text.length) {
                            val newline = content.text.text.indexOf('\n', start)
                            val end = if (newline < 0) content.text.length else newline + 1
                            if (imageRanges.none { it.start < end && it.end > start }) {
                                addStyle(
                                    ParagraphStyle(
                                        lineHeight = textStyle.lineHeight,
                                        lineHeightStyle = textStyle.lineHeightStyle,
                                    ),
                                    start,
                                    end,
                                )
                            }
                            start = end
                        }
                    }
                    .toAnnotatedString()
            }
        val lineHeightStyle = textStyle.lineHeightStyle ?: LineHeightStyle.Default
        val paragraphStyle =
            if (mediaObjects.isNotEmpty())
                textStyle.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
            else if (content.objects.isEmpty()) textStyle
            else
                textStyle.copy(
                    lineHeightStyle =
                        LineHeightStyle(
                            alignment = lineHeightStyle.alignment,
                            trim = lineHeightStyle.trim,
                            mode = LineHeightStyle.Mode.Minimum,
                        ),
                )
        BasicText(
            text = paragraphText,
            modifier = modifier.bringIntoViewRequester(requester),
            style = paragraphStyle,
            inlineContent = inlineContent,
            softWrap = !context.inCode,
            maxLines = maxLines,
            onTextLayout = { layout ->
                if (content.tabs.isNotEmpty()) {
                    val widths =
                        nativeTabWidths(layout, content.tabs, tabIntervals, placeholderIndexes)
                    scope.launch {
                        widths.forEach { (id, width) ->
                            if (
                                abs((tabWidths[id] ?: Float.NaN) - width) > 0.25f ||
                                    tabWidths[id] == null
                            ) {
                                tabWidths[id] = width
                            }
                        }
                    }
                }
            },
        )
    }
}

internal fun nativeHtmlTextAlign(element: HtmlElement): TextAlign? =
    when ((element.cssProperty("text-align") ?: element.attr("align"))?.lowercase()) {
        "left" -> TextAlign.Left
        "right" -> TextAlign.Right
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        "start" -> TextAlign.Start
        "end" -> TextAlign.End
        else -> null
    }

private fun scriptBaselineShift(
    style: SpanStyle,
    superscript: Boolean,
    context: NativeHtmlRenderContext,
    density: Density,
    resolver: FontFamily.Resolver,
): BaselineShift {
    val typeface =
        resolver
            .resolve(
                style.fontFamily,
                style.fontWeight ?: FontWeight.Normal,
                style.fontStyle ?: FontStyle.Normal,
                style.fontSynthesis ?: FontSynthesis.All,
            )
            .value as Typeface
    val fontSizePx =
        with(density) { (style.fontSize.takeIf { it.isSp } ?: context.textStyle.fontSize).toPx() }
    val ascent =
        Paint(Paint.ANTI_ALIAS_FLAG)
            .apply {
                this.typeface = typeface
                textSize = fontSizePx
            }
            .ascent()
    // Blink 以父字号计算位移；Compose 先应用 baselineShift，再应用本层缩小字号。
    val cssPixel = with(density) { 1.dp.toPx() }
    val downwards = if (superscript) -(fontSizePx / 3f + cssPixel) else fontSizePx / 5f + cssPixel
    return BaselineShift(if (ascent < 0f) downwards / ascent else 0f)
}

private fun nativeTabWidths(
    layout: TextLayoutResult,
    tabs: List<InlineTab>,
    intervals: Map<SpanStyle, Float>,
    placeholderIndexes: Map<String, Int>,
): Map<String, Float> = buildMap {
    val lineDeltas = mutableMapOf<Int, Float>()
    tabs.forEach { tab ->
        val index = placeholderIndexes[tab.id] ?: return@forEach
        val rect = layout.placeholderRects.getOrNull(index) ?: return@forEach
        val line = layout.getLineForOffset(tab.offset)
        val delta = lineDeltas[line] ?: 0f
        val leading =
            if (layout.getParagraphDirection(tab.offset) == ResolvedTextDirection.Rtl) {
                layout.getLineRight(line) - rect.right
            } else rect.left - layout.getLineLeft(line)
        val interval = intervals.getValue(tab.style)
        val position = leading + delta
        val remainder = ((position % interval) + interval) % interval
        val width = (interval - remainder).coerceAtLeast(1f)
        put(tab.id, width)
        // Android 将占位宽度向上取整；后续 tab 的目标需包含前面占位的实际变化。
        lineDeltas[line] = delta + ceil(width) - rect.width
    }
}

private class NativeInlineBuilder(
    private val context: NativeHtmlRenderContext,
    private val openLink: (String) -> Unit,
    private val baselineShift: (SpanStyle, Boolean) -> BaselineShift,
) {
    private val text = AnnotatedString.Builder()
    private val objects = mutableListOf<InlineObject>()
    private val tabs = mutableListOf<InlineTab>()
    private val placeholderIds = mutableListOf<String>()
    private var lastCharacter: Char? = null
    private var trailingCollapsibleSpace = false

    fun build(nodes: List<HtmlNode>, preserveWhitespace: Boolean): NativeInlineContent {
        val state =
            InlineState(
                style = context.textStyle.toSpanStyle(),
                ancestors = context.ancestorClasses,
                preserveWhitespace = preserveWhitespace,
            )
        appendNodes(nodes, state)
        val annotated = text.toAnnotatedString()
        val result =
            if (trailingCollapsibleSpace && annotated.isNotEmpty()) {
                annotated.subSequence(0, annotated.length - 1)
            } else annotated
        return NativeInlineContent(result, objects.toList(), tabs.toList(), placeholderIds.toList())
    }

    private fun appendNodes(nodes: List<HtmlNode>, state: InlineState) {
        nodes.forEach { node ->
            when (node) {
                is HtmlText -> appendText(node.text, state)
                is HtmlElement -> appendElement(node, state)
            }
        }
    }

    private fun appendElement(element: HtmlElement, inherited: InlineState) {
        if (element.key in context.excludedImageKeys) return
        if (element.isHidden(context.style.palette.isDark)) return
        if (element.tag == "wbr") return
        if (element.tag == "br") {
            if (!context.inCode) appendCharacter('\n', collapsible = false)
            return
        }
        if (element.semantic.kind == HtmlSemanticKind.MATH) {
            val mathSource = element.semantic.math?.source.orEmpty()
            if (mathSource.isNotEmpty()) {
                val mathStyle =
                    inherited.style.copy(
                        fontFamily = context.style.typography.codeFontFamily,
                        background = context.style.palette.codeBackground,
                    )
                text.withStyle(mathStyle) {
                    appendText(mathSource, inherited.copy(style = mathStyle))
                }
            }
            return
        }
        val classes = element.classes()
        val style = elementStyle(element, inherited)
        val state =
            inherited.copy(
                style = style,
                ancestors = inherited.ancestors + listOf(classes),
                preserveWhitespace =
                    when (element.cssProperty("white-space")) {
                        "pre",
                        "pre-wrap",
                        "break-spaces" -> true
                        "normal",
                        "nowrap" -> false
                        else -> inherited.preserveWhitespace || element.tag == "pre"
                    },
                link = element.attr("href") ?: inherited.link,
                picture =
                    when (element.tag) {
                        "picture" -> element
                        "img" -> inherited.picture
                        else -> null
                    },
            )
        val kind = inlineObjectKind(element)
        val start = text.length
        // Android 的 baselineShift 按 Span 层叠加，只在 sub/sup 本层写入增量。
        val wrapperStyle =
            style.copy(
                baselineShift =
                    if (element.tag == "sub" || element.tag == "sup") {
                        style.baselineShift
                    } else null,
            )
        text.withStyle(wrapperStyle) {
            if ("pl-c2" in classes) appendText("^M", state.copy(preserveWhitespace = true))
            val href = element.attr("href")
            if (element.tag == "a" && href != null) {
                withLink(
                    LinkAnnotation.Url(
                        url = href,
                        styles = null,
                        linkInteractionListener = { openLink(href) },
                    ),
                ) {
                    if (kind != null) appendObject(element, kind, state)
                    else appendNodes(element.children, state)
                }
            } else if (kind != null) {
                appendObject(element, kind, state)
            } else {
                val block = element.tag in InlineBlockTags
                if (block && lastCharacter != null && lastCharacter != '\n') {
                    appendCharacter('\n', collapsible = false)
                }
                appendNodes(element.children, state)
                if (block && lastCharacter != '\n') appendCharacter('\n', collapsible = false)
            }
        }
        element.attr("title")?.takeIf(String::isNotBlank)?.let { title ->
            if (text.length > start) {
                text.addStringAnnotation("html:title", title, start, text.length)
            }
        }
    }

    private fun appendObject(element: HtmlElement, kind: InlineObjectKind, state: InlineState) {
        val id = "html-inline:${element.key}"
        val alternate =
            when (kind) {
                InlineObjectKind.IMAGE -> element.attr("alt")
                InlineObjectKind.TASK ->
                    if (element.semantic.task?.checked == true) "[x]" else "[ ]"
                else -> element.attr("aria-label")
            }?.takeIf(String::isNotEmpty) ?: "\uFFFC"
        placeholderIds += id
        text.appendInlineContent(id, alternate)
        lastCharacter = '\uFFFC'
        trailingCollapsibleSpace = false
        objects += InlineObject(id, kind, element, state.style, state.link, state.picture)
    }

    private fun appendText(value: String, state: InlineState) {
        value.forEach { character ->
            if (state.preserveWhitespace && context.inCode && character == '\t') {
                val id = "html-tab:${text.length}"
                tabs += InlineTab(id, text.length, state.style)
                placeholderIds += id
                text.appendInlineContent(id, "\t")
                lastCharacter = '\t'
                trailingCollapsibleSpace = false
            } else if (!state.preserveWhitespace && character in CollapsibleWhitespace) {
                if (
                    lastCharacter != null &&
                        lastCharacter != '\n' &&
                        !(lastCharacter == ' ' && trailingCollapsibleSpace)
                ) {
                    appendCharacter(' ', collapsible = true)
                }
            } else appendCharacter(character, collapsible = false)
        }
    }

    private fun appendCharacter(character: Char, collapsible: Boolean) {
        text.append(character)
        lastCharacter = character
        trailingCollapsibleSpace = collapsible
    }

    private fun elementStyle(element: HtmlElement, inherited: InlineState): SpanStyle {
        val styles = context.style
        val fontSize = inherited.style.fontSize.takeIf { it.isSp } ?: context.textStyle.fontSize
        var own =
            when (element.tag) {
                "b",
                "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                "em",
                "i",
                "var",
                "cite",
                "dfn" -> SpanStyle(fontStyle = FontStyle.Italic)
                "s",
                "strike",
                "del" -> decoration(inherited.style, TextDecoration.LineThrough)
                "u",
                "ins",
                "abbr" -> decoration(inherited.style, TextDecoration.Underline)
                "sub" ->
                    SpanStyle(
                        fontSize = context.cssTypography.scaleFontSize(fontSize, 5f / 6f),
                        baselineShift = baselineShift(inherited.style, false),
                    )
                "sup" ->
                    SpanStyle(
                        fontSize = context.cssTypography.scaleFontSize(fontSize, 5f / 6f),
                        baselineShift = baselineShift(inherited.style, true),
                    )
                "small" ->
                    SpanStyle(fontSize = context.cssTypography.scaleFontSize(fontSize, 5f / 6f))
                "big" -> SpanStyle(fontSize = context.cssTypography.scaleFontSize(fontSize, 1.2f))
                "code",
                "tt" ->
                    SpanStyle(
                        fontFamily = styles.typography.codeFontFamily,
                        fontSize =
                            if (context.inCode || context.inHeading) fontSize
                            else
                                context.cssTypography.scaleFontSize(
                                    fontSize,
                                    styles.typography.codeFontScale,
                                ),
                        background =
                            if (context.inCode) Color.Transparent
                            else styles.palette.codeBackground,
                    )
                "samp",
                "pre" -> SpanStyle(fontFamily = styles.typography.codeFontFamily)
                "kbd" ->
                    SpanStyle(
                        fontFamily = styles.typography.codeFontFamily,
                        fontSize =
                            context.cssTypography.scaleFontSize(
                                fontSize,
                                styles.typography.kbdFontScale,
                            ),
                        color = styles.palette.kbd,
                        background = styles.palette.kbdBackground,
                    )
                "mark" -> SpanStyle(background = styles.palette.mentionBackground)
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6" ->
                    context.cssTypography
                        .headingStyle(element.tag.last().digitToInt())
                        .toSpanStyle()
                "a" ->
                    if (element.attr("href") != null) {
                        decoration(inherited.style, TextDecoration.Underline)
                            .copy(color = styles.palette.link)
                    } else SpanStyle()
                else -> SpanStyle()
            }
        own =
            own.merge(
                styles.inlineStyleForClasses(element.classes(), inherited.ancestors),
            )
        element.cssProperty("font-weight")?.let { weight ->
            val resolved =
                when (weight.lowercase()) {
                    "normal" -> FontWeight.Normal
                    "bold",
                    "bolder" -> FontWeight.Bold
                    else -> weight.toIntOrNull()?.takeIf { it in 1..1000 }?.let(::FontWeight)
                }
            if (resolved != null) own = own.merge(SpanStyle(fontWeight = resolved))
        }
        if (element.cssProperty("font-style") == "italic") {
            own = own.merge(SpanStyle(fontStyle = FontStyle.Italic))
        }
        val cssDecoration = element.cssProperty("text-decoration").orEmpty()
        if ("none" in cssDecoration) {
            own = own.copy(textDecoration = TextDecoration.None)
        }
        if ("underline" in cssDecoration) {
            own = own.merge(decoration(inherited.style, TextDecoration.Underline))
        }
        if ("line-through" in cssDecoration) {
            own = own.merge(decoration(inherited.style, TextDecoration.LineThrough))
        }
        if (element.hasClass("tlb-link-style-none")) {
            own = own.copy(color = inherited.style.color, textDecoration = TextDecoration.None)
        }
        return inherited.style.merge(own)
    }
}

private fun decoration(style: SpanStyle, decoration: TextDecoration): SpanStyle =
    SpanStyle(
        textDecoration = TextDecoration.combine(listOfNotNull(style.textDecoration, decoration)),
    )

private fun inlineObjectKind(element: HtmlElement): InlineObjectKind? =
    when {
        (element.tag == "input" && element.attr("type").equals("checkbox", ignoreCase = true)) ||
            (element.hasClass("tlb-checkbox") && !element.hasClass("tlb-issue-reference-number")) ->
            InlineObjectKind.TASK
        element.tag == "svg" &&
            element.classes().any { it == "octicon" || it.startsWith("octicon-") } ->
            InlineObjectKind.ICON
        element.tag == "img" || element.tag == "svg" -> InlineObjectKind.IMAGE
        element.tag == "video" || element.tag == "audio" -> InlineObjectKind.MEDIA
        else -> null
    }

internal fun NativeHtmlRenderContext.withInlineStyle(style: SpanStyle): NativeHtmlRenderContext =
    copy(textStyle = textStyle.merge(style))

private fun inlineObjectSize(
    item: InlineObject,
    measured: InlineMeasurement?,
    intrinsic: InlineIntrinsicImage?,
    availableWidth: Dp,
    density: Density,
    context: NativeHtmlRenderContext,
): Pair<Dp, Dp> =
    with(density) {
        val fontSize = (item.style.fontSize.takeIf { it.isSp } ?: context.textStyle.fontSize).toDp()
        if (item.kind == InlineObjectKind.TASK) return@with 16.dp to 16.dp
        if (item.kind == InlineObjectKind.ICON) {
            val iconSize =
                context.cssTypography.emSpacing(
                    item.style.fontSize.takeIf { it.isSp } ?: context.textStyle.fontSize,
                    1f,
                )
            return@with (iconSize +
                nativeHtmlIconEndMargin(item.element) +
                if (item.element.hasClass("ml-1")) 4.dp else 0.dp) to iconSize
        }
        val ratio =
            intrinsic?.let { it.width.toFloat() / it.height }?.takeIf { it.isFinite() && it > 0f }
        val widthAttribute =
            item.element.cssDimension("width")?.takeIf { it.isFinite() && it > 0f }?.dp
        val heightAttribute =
            item.element.cssDimension("height")?.takeIf { it.isFinite() && it > 0f }?.dp
        val width =
            widthAttribute
                ?: heightAttribute?.let { height -> ratio?.let { height * it } }
                ?: intrinsic?.width?.dp
                ?: if (item.kind == InlineObjectKind.MEDIA) availableWidth else fontSize
        val fittedWidth = width.coerceIn(1.dp, availableWidth.coerceAtLeast(1.dp))
        val height =
            heightAttribute
                ?: ratio?.let { fittedWidth / it }
                ?: if (item.kind == InlineObjectKind.MEDIA) fittedWidth * (9f / 16f) else fontSize
        fittedWidth to height.coerceAtLeast(1.dp)
    }

private fun inlineAnchorKeys(nodes: List<HtmlNode>): List<String> = buildList {
    fun visit(node: HtmlNode) {
        if (node is HtmlElement) {
            if (node.semantic.anchorNames.isNotEmpty()) add(node.key)
            node.children.forEach(::visit)
        }
    }
    nodes.forEach(::visit)
}

private data class NativeInlineContent(
    val text: AnnotatedString,
    val objects: List<InlineObject>,
    val tabs: List<InlineTab>,
    val placeholderIds: List<String>,
)

private data class InlineTab(val id: String, val offset: Int, val style: SpanStyle)

private data class InlineState(
    val style: SpanStyle,
    val ancestors: List<Set<String>> = emptyList(),
    val preserveWhitespace: Boolean,
    val link: String? = null,
    val picture: HtmlElement? = null,
)

private data class InlineObject(
    val id: String,
    val kind: InlineObjectKind,
    val element: HtmlElement,
    val style: SpanStyle,
    val link: String?,
    val picture: HtmlElement?,
)

private enum class InlineObjectKind {
    IMAGE,
    TASK,
    ICON,
    MEDIA,
}

private data class InlineIntrinsicImage(val width: Int, val height: Int)

private data class InlineMeasurement(val widthPx: Int, val heightPx: Int, val baselinePx: Float)

private val CollapsibleWhitespace = setOf(' ', '\t', '\n', '\r', '\u000c')
private val InlineBlockTags = setOf("div", "p", "section", "article", "li", "dt", "dd")
