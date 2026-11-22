package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlLineChange
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlSemanticKind
import moe.yumeyuka.markdown.core.HtmlText

/** 相邻行内节点保持同一个文本布局，块边距按 CSS 的相邻外边距取大值。 */
@Composable
internal fun NativeHtmlChildren(
    nodes: List<HtmlNode>,
    context: NativeHtmlRenderContext,
    modifier: Modifier = Modifier,
    trimEdges: Boolean = true,
) {
    val visible = nodes.filterNot { it is HtmlElement && it.isHidden(context.style.palette.isDark) }
    val groups = htmlFlowGroups(visible)
    if (groups.any { nativeHtmlFloatImages(it, context.style.palette.isDark).isNotEmpty() }) {
        NativeHtmlFloatFlow(groups, context, modifier, trimEdges)
        return
    }
    Column(modifier) {
        var previousBottom = 0.dp
        groups.forEachIndexed { index, group ->
            val element = group.singleOrNull() as? HtmlElement
            val margins = element?.takeIf(HtmlElement::isNativeBlock)?.nativeMargins(context)
            val top = margins?.first ?: 0.dp
            val gap = if (index == 0 && trimEdges) 0.dp else maxOf(previousBottom, top)
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            key(group.first().key) {
                if (element != null && element.isNativeBlock()) NativeHtmlBlock(element, context)
                else NativeHtmlText(group, context, Modifier.fillMaxWidth())
            }
            previousBottom = margins?.second ?: 0.dp
        }
        if (!trimEdges && previousBottom > 0.dp) Spacer(Modifier.height(previousBottom))
    }
}

private fun htmlFlowGroups(nodes: List<HtmlNode>): List<List<HtmlNode>> = buildList {
    val inline = mutableListOf<HtmlNode>()
    fun flush() {
        if (inline.any { it !is HtmlText || it.text.any { char -> !char.isWhitespace() } }) {
            add(inline.toList())
        }
        inline.clear()
    }
    nodes.forEach { node ->
        if (node is HtmlElement && node.isNativeBlock()) {
            flush()
            add(listOf(node))
        } else inline += node
    }
    flush()
}

internal fun HtmlElement.isNativeBlock(): Boolean =
    semantic.kind in BlockSemantics ||
        tag in BlockTags ||
        cssProperty("display") in setOf("block", "flex", "table")

private val BlockSemantics =
    setOf(
        HtmlSemanticKind.ALERT,
        HtmlSemanticKind.SUGGESTION,
        HtmlSemanticKind.TASK_LIST_BLOCK,
    )
private val BlockTags =
    setOf(
        "address",
        "article",
        "aside",
        "blockquote",
        "div",
        "dl",
        "dt",
        "dd",
        "fieldset",
        "figure",
        "figcaption",
        "footer",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "hr",
        "header",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "pre",
        "section",
        "table",
        "markdown-accessibility-table",
        "ul",
        "details",
        "summary",
        "video",
        "audio",
        "picture",
    )

@Composable
internal fun HtmlElement.nativeMargins(context: NativeHtmlRenderContext): Pair<Dp, Dp> {
    val spacing = context.style.spacing
    return when {
        // 官方 HTML 的标题包装层不建立新的格式化上下文，标题外边距应向外折叠。
        hasClass("markdown-heading") ->
            children
                .filterIsInstance<HtmlElement>()
                .firstOrNull { it.tag in HeadingTags }
                ?.nativeMargins(context) ?: (0.dp to 0.dp)
        tag == "markdown-accessibility-table" -> 0.dp to spacing.blockGap
        hasClass("tlb-header-title") -> 0.dp to 0.dp
        hasClass("my-2") -> 8.dp to (if (hasClass("mb-0")) 0.dp else 8.dp)
        hasClass("mb-0") -> 0.dp to 0.dp
        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' ->
            spacing.headingTop to spacing.headingBottom
        tag == "hr" -> spacing.horizontalRuleVertical to spacing.horizontalRuleVertical
        tag == "dt" -> spacing.blockGap to 0.dp
        tag in setOf("p", "pre", "blockquote", "details", "dl", "dd", "ul", "ol", "table") ->
            0.dp to spacing.blockGap
        semantic.kind in BlockSemantics || hasClass("highlight") -> 0.dp to spacing.blockGap
        else -> 0.dp to 0.dp
    }
}

private val HeadingTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")

@Composable
internal fun NativeHtmlBlock(element: HtmlElement, context: NativeHtmlRenderContext) {
    val modifier = nativeHtmlNodeModifier(element, context)
    val inherited =
        context.withInlineStyle(
            context.style.inlineStyleForClasses(element.classes(), context.ancestorClasses),
        )
    val aligned =
        inherited.copy(
            textStyle =
                inherited.textStyle.copy(
                    textAlign = nativeHtmlTextAlign(element) ?: inherited.textStyle.textAlign,
                    textDirection =
                        when (element.attr("dir")) {
                            "rtl" -> TextDirection.Rtl
                            "ltr" -> TextDirection.Ltr
                            else -> inherited.textStyle.textDirection
                        },
                ),
            ancestorClasses = context.ancestorClasses + listOf(element.classes()),
        )
    when (element.semantic.kind) {
        HtmlSemanticKind.SUGGESTION -> {
            NativeHtmlSuggestion(element, aligned, modifier)
            return
        }
        HtmlSemanticKind.ALERT -> {
            NativeHtmlAlert(element, aligned, modifier)
            return
        }
        HtmlSemanticKind.MERMAID -> {
            NativeHtmlCode(
                element = element,
                context = aligned,
                modifier = modifier,
                source = element.semantic.mermaid?.source,
            )
            return
        }
        HtmlSemanticKind.MATH -> {
            NativeHtmlCode(
                element = element,
                context = aligned,
                modifier = modifier,
                source = element.semantic.math?.source,
            )
            return
        }
        else -> Unit
    }
    when (element.tag) {
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6" -> {
            val level = element.tag[1].digitToInt()
            val textStyle =
                if (element.hasClass("tlb-header-title")) {
                    aligned.cssTypography.resolve(
                        aligned.textStyle.copy(
                            fontSize = aligned.style.typography.bodyFontSize * .88235294f,
                            lineHeight = aligned.style.typography.bodyFontSize * 1.17647059f,
                            fontWeight = FontWeight.Medium,
                            fontStyle = FontStyle.Normal,
                        ),
                    )
                } else aligned.cssTypography.headingStyle(level)
            val underlinePadding =
                aligned.cssTypography.emSpacing(
                    textStyle.fontSize,
                    context.style.spacing.headingUnderlinePaddingEm,
                )
            val headingModifier =
                modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .then(
                        if (level <= 2)
                            Modifier.drawBehind {
                                    val width = context.style.spacing.borderWidth.toPx()
                                    drawLine(
                                        context.style.palette.border,
                                        Offset(0f, size.height - width / 2),
                                        Offset(size.width, size.height - width / 2),
                                        width,
                                    )
                                }
                                .padding(
                                    bottom = underlinePadding + context.style.spacing.borderWidth,
                                )
                        else Modifier,
                    )
            NativeHtmlText(
                element.children,
                aligned.copy(textStyle = textStyle, inHeading = true),
                headingModifier,
            )
        }
        "p",
        "summary",
        "figcaption",
        "address" -> {
            val title = element.hasClass("markdown-alert-title")
            val base =
                if (title)
                    aligned.textStyle.copy(
                        fontWeight = FontWeight.Medium,
                        color = aligned.style.alertColor(aligned.alertKind ?: "note"),
                    )
                else aligned.textStyle
            NativeHtmlText(
                element.children,
                aligned.copy(textStyle = base),
                modifier.fillMaxWidth(),
            )
        }
        "pre" -> NativeHtmlCode(element, aligned, modifier)
        "blockquote" -> NativeHtmlQuote(element, aligned, modifier)
        "ul",
        "ol" -> NativeHtmlList(element, aligned, modifier)
        "details" -> NativeHtmlDetails(element, aligned, modifier)
        "table" ->
            NativeHtmlTable(
                element = element,
                style = aligned.style,
                bodyTextStyle = aligned.textStyle,
                modifier = modifier,
                renderCell = { cell, textStyle, cellModifier ->
                    NativeHtmlChildren(
                        cell.children,
                        aligned.copy(textStyle = textStyle),
                        cellModifier,
                    )
                },
                onIssue = { message ->
                    aligned.actions.onError?.invoke(
                        HtmlRenderIssue(element.key, "TABLE_INTRINSIC", message),
                    )
                },
                elementModifier = { node -> nativeHtmlNodeModifier(node, aligned) },
            )
        "hr" -> {
            val height =
                aligned.cssTypography.emSpacing(
                    aligned.textStyle.fontSize,
                    aligned.style.spacing.horizontalRuleHeightEm,
                )
            Spacer(
                modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(aligned.style.palette.horizontalRule),
            )
        }
        "dt" ->
            NativeHtmlText(
                element.children,
                aligned.copy(
                    textStyle =
                        aligned.textStyle.copy(
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.SemiBold,
                        ),
                ),
                modifier.fillMaxWidth(),
            )
        "dd" ->
            NativeHtmlChildren(
                element.children,
                aligned,
                modifier.padding(horizontal = aligned.style.spacing.definitionIndent),
            )
        "video",
        "audio" -> NativeHtmlMedia(element, aligned, modifier)
        "picture" -> {
            val image =
                element.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "img" }
            if (image != null) NativeHtmlImage(image, aligned, modifier, picture = element)
            else NativeHtmlChildren(element.children, aligned, modifier)
        }
        else -> NativeHtmlContainerElement(element, aligned, modifier)
    }
}

@Composable
private fun NativeHtmlContainerElement(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val spacing = context.style.spacing
    val rounded = element.hasClass("rounded") || element.hasClass("tlb")
    val bordered = element.hasClass("tlb-border") || element.hasClass("tlb")
    var container = modifier
    if (rounded) container = container.clip(RoundedCornerShape(10.dp))
    if (bordered)
        container =
            container.border(
                spacing.borderWidth,
                context.style.palette.border,
                RoundedCornerShape(10.dp),
            )
    if (element.hasClass("tlb-rounded-header")) {
        container =
            container
                .background(context.style.palette.taskHeaderBackground)
                .padding(vertical = 12.dp)
    }
    if (element.hasClass("tlb-text")) {
        val padding = context.cssTypography.emSpacing(context.textStyle.fontSize, 0.8824f)
        container = container.padding(vertical = padding)
    }
    if (element.hasClass("d-flex") || element.cssProperty("display") == "flex") {
        Row(container.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            element.children
                .filterNot { it is HtmlText && it.text.isBlank() }
                .forEach { child ->
                    key(child.key) {
                        if (child is HtmlElement && child.tag == "input")
                            NativeHtmlTask(child, context)
                        else if (child is HtmlElement && child.tag == "svg") {
                            NativeHtmlIcon(child, context)
                        } else {
                            val flex =
                                child is HtmlElement &&
                                    (child.hasClass("flex-1") ||
                                        child.hasClass("flex-auto") ||
                                        child.cssProperty("flex")?.startsWith('1') == true)
                            val childModifier =
                                if (flex) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max)
                            NativeHtmlChildren(listOf(child), context, childModifier)
                        }
                    }
                }
        }
    } else NativeHtmlChildren(element.children, context, container.fillMaxWidth())
}

@Composable
private fun NativeHtmlQuote(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val padding =
        context.cssTypography.emSpacing(
            context.textStyle.fontSize,
            context.style.spacing.quotePaddingEm,
        )
    NativeHtmlChildren(
        element.children,
        context.copy(textStyle = context.textStyle.copy(color = context.style.palette.quote)),
        modifier
            .fillMaxWidth()
            .drawBehind {
                val width = context.style.spacing.quoteBorderWidth.toPx()
                val radius = context.style.spacing.quoteBorderRadius.toPx()
                drawRoundRect(
                    context.style.palette.quoteBorder,
                    size = Size(width, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            .padding(start = padding),
    )
}

@Composable
private fun NativeHtmlAlert(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val color = context.style.alertColor(element.semantic.alert?.kind ?: "note")
    val border =
        context.cssTypography.emSpacing(
            context.textStyle.fontSize,
            context.style.spacing.alertBorderWidthEm,
        )
    NativeHtmlChildren(
        element.children,
        context.copy(
            alertKind = element.semantic.alert?.kind ?: "note",
        ),
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color,
                    Offset(border.toPx() / 2, 0f),
                    Offset(border.toPx() / 2, size.height),
                    border.toPx(),
                )
            }
            .padding(start = border)
            .padding(
                horizontal = context.style.spacing.alertHorizontalPadding,
                vertical = context.style.spacing.alertVerticalPadding,
            ),
    )
}

@Composable
private fun NativeHtmlList(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val gap =
        context.cssTypography.emSpacing(
            context.textStyle.fontSize,
            context.style.spacing.listItemGapEm,
        )
    val classes = element.classes()
    val noMarker =
        classes.any { it in setOf("no-list", "list-style-none", "tlb-ol") } ||
            element.cssProperty("list-style-type") == "none"
    val resetIndent = classes.any { it in setOf("no-list", "contains-task-list", "tlb-ol") }
    val indent = if (resetIndent) 0.dp else nativeListIndent(element, context)
    val items = element.children.filterIsInstance<HtmlElement>().filter { it.tag == "li" }
    val reversed = element.tag == "ol" && element.attr("reversed") != null
    var number = element.attr("start")?.toIntOrNull() ?: if (reversed) items.size else 1
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0 && !item.hasClass("tlb-li") && !items[index - 1].hasClass("tlb-li")) {
                Spacer(Modifier.height(gap))
            }
            number = item.attr("value")?.toIntOrNull() ?: number
            val hideMarker = noMarker || item.hasClass("task-list-item") || item.hasClass("tlb-li")
            val itemIndent =
                if (resetIndent && !hideMarker) nativeListIndent(element, context) else indent
            val marker = nativeListMarker(element, number, context.listDepth)
            val identity = item.attr("data-fixture-id") ?: item.attr("id") ?: item.key
            val nestedMargin =
                if (
                    context.listDepth > 0 &&
                        !item.hasClass("tlb-li") &&
                        !item.hasClass("task-list-item")
                )
                    context.style.spacing.nestedListItemMargin
                else 0.dp
            key(item.key) {
                Box(Modifier.fillMaxWidth().padding(start = itemIndent + nestedMargin)) {
                    // CSS marker 位于 li 内容盒之外，不参与正文宽度或选区。
                    androidx.compose.ui.layout.Layout(
                        modifier = nativeHtmlNodeModifier(item, context).fillMaxWidth(),
                        content = {
                            if (!hideMarker) {
                                DisableSelection {
                                    BasicText(
                                        "$marker ",
                                        Modifier.testTag("html-list-marker:$identity"),
                                        style = context.textStyle,
                                    )
                                }
                            }
                            NativeHtmlChildren(
                                item.children,
                                context.copy(listDepth = context.listDepth + 1),
                                Modifier.fillMaxWidth().testTag("html-list-content:$identity"),
                            )
                        },
                    ) { measurables, constraints ->
                        val body = measurables.last().measure(constraints.copy(minHeight = 0))
                        val markerPlaceable =
                            if (hideMarker) null
                            else measurables.first().measure(androidx.compose.ui.unit.Constraints())
                        layout(body.width, maxOf(body.height, markerPlaceable?.height ?: 0)) {
                            body.placeRelative(0, 0)
                            markerPlaceable?.placeRelative(-markerPlaceable.width, 0)
                        }
                    }
                }
            }
            number += if (reversed) -1 else 1
        }
    }
}

@Composable
private fun nativeListIndent(element: HtmlElement, context: NativeHtmlRenderContext): Dp {
    val specified =
        element.cssProperty("padding-inline-start") ?: element.cssProperty("padding-left")
    if (specified != null) {
        val value = specified.removeSuffix("em").removeSuffix("px").toFloatOrNull()
        if (value != null && value.isFinite() && value >= 0f) {
            return if (specified.endsWith("em"))
                context.cssTypography.emSpacing(context.textStyle.fontSize, value)
            else value.dp
        }
    }
    // assets 只覆盖 ul；ol 保留 Android WebView 实测的 UA 40 CSS px 缩进。
    return if (element.tag == "ol") context.style.spacing.orderedListIndent
    else
        context.cssTypography.emSpacing(
            context.textStyle.fontSize,
            context.style.spacing.listIndentEm,
        )
}

private fun nativeListMarker(element: HtmlElement, number: Int, depth: Int): String {
    val specified = element.cssProperty("list-style-type") ?: element.attr("type")
    if (element.tag == "ol") {
        val type =
            when (specified) {
                "lower-alpha",
                "lower-latin" -> "a"
                "upper-alpha",
                "upper-latin" -> "A"
                "lower-roman" -> "i"
                "upper-roman" -> "I"
                else -> specified
            }
        return orderedMarker(number, type)
    }
    return when (
        specified
            ?: when (depth) {
                0 -> "disc"
                1 -> "circle"
                else -> "square"
            }
    ) {
        "circle" -> "◦"
        "square" -> "▪"
        else -> "•"
    }
}

private fun orderedMarker(number: Int, type: String?): String =
    when (type) {
        "a",
        "A" -> {
            var remainder = number.coerceAtLeast(1)
            val result = buildString {
                while (remainder > 0) {
                    remainder--
                    append(('a'.code + remainder % 26).toChar())
                    remainder /= 26
                }
            }
                .reversed()
            (if (type == "A") result.uppercase() else result) + "."
        }
        "i",
        "I" -> {
            var remainder = number.coerceAtLeast(1)
            val result = buildString {
                listOf(
                        1000 to "m",
                        900 to "cm",
                        500 to "d",
                        400 to "cd",
                        100 to "c",
                        90 to "xc",
                        50 to "l",
                        40 to "xl",
                        10 to "x",
                        9 to "ix",
                        5 to "v",
                        4 to "iv",
                        1 to "i",
                    )
                    .forEach { (value, digit) ->
                        while (remainder >= value) {
                            append(digit)
                            remainder -= value
                        }
                    }
            }
            (if (type == "I") result.uppercase() else result) + "."
        }
        else -> "$number."
    }

@Composable
private fun NativeHtmlDetails(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val expanded = context.expandedDetails[element.key] ?: (element.attr("open") != null)
    val summary =
        element.children.filterIsInstance<HtmlElement>().firstOrNull { it.tag == "summary" }
    Column(modifier.fillMaxWidth()) {
        val summaryModifier = summary?.let { nativeHtmlNodeModifier(it, context) } ?: Modifier
        Row(
            summaryModifier.fillMaxWidth().clickable(role = Role.Button) {
                context.expandedDetails[element.key] = !expanded
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val slot = context.slots.icon
            if (slot != null) {
                val size = with(LocalDensity.current) { context.textStyle.fontSize.toDp() }
                slot(
                    NativeHtmlIconRequest(
                        name = if (expanded) "disclosure-expanded" else "disclosure-collapsed",
                        contentDescription = null,
                        tint = context.style.palette.textPrimary,
                        size = size,
                    ),
                    Modifier.size(size),
                )
            }
            if (summary != null) NativeHtmlText(summary.children, context, Modifier.weight(1f))
            else {
                val label =
                    if (expanded) context.style.strings.detailsCollapse
                    else context.style.strings.detailsExpand
                DisableSelection { BasicText(label, style = context.textStyle) }
            }
        }
        if (expanded) {
            NativeHtmlChildren(
                element.children.filterNot { it === summary },
                context,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NativeHtmlCode(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
    source: String? = null,
) {
    val shape = RoundedCornerShape(context.style.spacing.codeRadius)
    val children =
        if (source != null) listOf(HtmlText("${element.key}.src", source)) else element.children
    NativeHtmlText(
        children,
        context.copy(textStyle = context.cssTypography.codeStyle(), inCode = true),
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(context.style.palette.codeBackground)
            .horizontalScroll(rememberScrollState())
            .padding(context.style.spacing.codePadding),
        preserveWhitespace = true,
    )
}

@Composable
private fun NativeHtmlSuggestion(
    element: HtmlElement,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val suggestion = element.semantic.suggestion ?: return
    val spacing = context.style.spacing
    val colors = context.style.palette
    val shape = RoundedCornerShape(spacing.suggestionRadius)
    val textStyle = context.cssTypography.suggestionStyle()
    val measurer = rememberTextMeasurer()
    val numberWidth =
        with(LocalDensity.current) {
                (suggestion.lines.maxOfOrNull { line ->
                        val label = (line.oldLineNumber ?: line.newLineNumber)?.toString().orEmpty()
                        measurer.measure(label, textStyle).size.width
                    } ?: 0)
                    .toDp() + spacing.suggestionNumberHorizontal * 2
            }
            .coerceAtLeast(spacing.suggestionNumberMinWidth)
    Column(modifier.fillMaxWidth().border(spacing.borderWidth, colors.border, shape).clip(shape)) {
        Column(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .width(IntrinsicSize.Max),
        ) {
            suggestion.lines.forEachIndexed { index, line ->
                val addition = line.change == HtmlLineChange.ADDITION
                val deletion = line.change == HtmlLineChange.DELETION
                val background =
                    when {
                        addition -> colors.additionBackground
                        deletion -> colors.deletionBackground
                        else -> colors.background
                    }
                val numberBackground =
                    when {
                        addition -> colors.additionNumberBackground
                        deletion -> colors.deletionNumberBackground
                        else -> background
                    }
                val numberColor =
                    when {
                        addition -> colors.additionNumberText
                        deletion -> colors.deletionNumberText
                        else -> colors.textSecondary
                    }
                key("${element.key}:$index") {
                    Row(Modifier.fillMaxWidth().background(background)) {
                        DisableSelection {
                            val number =
                                if (addition) line.newLineNumber
                                else line.oldLineNumber ?: line.newLineNumber
                            BasicText(
                                number?.toString().orEmpty(),
                                Modifier.width(numberWidth)
                                    .background(numberBackground)
                                    .padding(horizontal = spacing.suggestionNumberHorizontal),
                                style =
                                    textStyle.copy(
                                        color = numberColor,
                                        textAlign = TextAlign.Right,
                                    ),
                                maxLines = 1,
                                softWrap = false,
                            )
                            BasicText(
                                when {
                                    addition -> "+ "
                                    deletion -> "- "
                                    else -> "  "
                                },
                                style = textStyle,
                            )
                        }
                        val lineClasses =
                            when {
                                addition -> setOf("blob-code-addition")
                                deletion -> setOf("blob-code-deletion")
                                else -> emptySet()
                            }
                        if (line.content.isNotEmpty())
                            NativeHtmlText(
                                line.content,
                                context.copy(
                                    textStyle = textStyle,
                                    inCode = true,
                                    ancestorClasses = context.ancestorClasses + listOf(lineClasses),
                                ),
                                preserveWhitespace = true,
                            )
                        else BasicText(line.text, style = textStyle)
                    }
                }
            }
        }
        val id = suggestion.id
        val callback = context.actions.onCommitSuggestion
        if (
            !id.isNullOrBlank() &&
                callback != null &&
                context.style.strings.applySuggestion.isNotBlank()
        ) {
            DisableSelection {
                NativeHtmlAction(
                    NativeHtmlActionRequest(
                        NativeHtmlActionKind.APPLY_SUGGESTION,
                        context.style.strings.applySuggestion,
                        true,
                        { callback(id, suggestion.rawHtml) },
                    ),
                    context,
                    Modifier.padding(spacing.suggestionPadding),
                )
            }
        }
    }
}

@Composable
internal fun NativeHtmlAction(
    request: NativeHtmlActionRequest,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val slot = context.slots.actionButton
    if (slot != null) slot(request, modifier)
    else
        BasicText(
            request.label,
            modifier
                .clickable(enabled = request.enabled, role = Role.Button, onClick = request.onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = context.textStyle.copy(color = context.style.palette.link),
        )
}
