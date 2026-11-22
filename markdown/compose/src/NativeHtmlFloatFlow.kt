package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlText

internal data class NativeHtmlFloatImage(
    val element: HtmlElement,
    val right: Boolean,
    val link: String?,
)

internal data class NativeHtmlTextExclusion(
    val width: Int,
    val height: Int,
    val right: Boolean,
)

/** 仅提取当前段落的浮图，外层 article/div 继续由自己的子流处理。 */
internal fun nativeHtmlFloatImages(
    nodes: List<HtmlNode>,
    dark: Boolean,
): List<NativeHtmlFloatImage> {
    val root = nodes.singleOrNull() as? HtmlElement
    if (root != null && root.isNativeBlock() && root.tag != "p") return emptyList()
    return buildList {
        fun visit(node: HtmlNode, link: String?) {
            if (node !is HtmlElement) return
            if (node.isHidden(dark)) return
            val side = node.cssProperty("float") ?: node.attr("align")
            if (node.tag == "img" && side in setOf("left", "right")) {
                add(NativeHtmlFloatImage(node, side == "right", link))
            } else {
                node.children.forEach { visit(it, node.attr("href") ?: link) }
            }
        }
        nodes.forEach { visit(it, null) }
    }
}

/** 浮图参与当前块流的测量与定位；后续块只在图片仍占用的高度内缩窄。 图片始终放在同一个父布局中，不能依靠负偏移越过其他卡片。 */
@Composable
internal fun NativeHtmlFloatFlow(
    groups: List<List<HtmlNode>>,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
    trimEdges: Boolean,
) {
    val floats = groups.map { nativeHtmlFloatImages(it, context.style.palette.isDark) }
    val excluded = floats.flatten().map { it.element.key }.toSet()
    val textContext = context.copy(excludedImageKeys = context.excludedImageKeys + excluded)
    val naturalSizes = remember(groups) { mutableStateMapOf<String, IntSize>() }
    val margins = groups.map { group ->
        (group.singleOrNull() as? HtmlElement)
            ?.takeIf { it.isNativeBlock() }
            ?.nativeMargins(context) ?: (0.dp to 0.dp)
    }
    fun hasText(nodes: List<HtmlNode>): Boolean = nodes.any { node ->
        when (node) {
            is HtmlText -> node.text.isNotBlank()
            is HtmlElement ->
                node.key !in excluded &&
                    !node.isHidden(context.style.palette.isDark) &&
                    (node.tag in setOf("img", "br", "table", "ul", "ol", "pre") ||
                        hasText(node.children))
        }
    }
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val placements = mutableListOf<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>()
        var y = 0
        var previousBottom = 0
        var floatBottom = 0
        var floatWidth = 0
        var floatRight = true
        val imageGap = 20.dp.roundToPx()
        groups.forEachIndexed { index, group ->
            val (top, bottom) = margins[index]
            if (index != 0 || !trimEdges) y += maxOf(previousBottom, top.roundToPx())
            val clear = (group.singleOrNull() as? HtmlElement)?.cssProperty("clear")
            if (clear == "both" || clear == if (floatRight) "right" else "left") {
                y = maxOf(y, floatBottom)
            }
            if (hasText(group)) {
                val narrowed = y < floatBottom
                val element = group.singleOrNull() as? HtmlElement
                val flowingText = element == null || element.tag == "p"
                val exclusion =
                    if (narrowed && flowingText)
                        NativeHtmlTextExclusion(floatWidth + imageGap, floatBottom - y, floatRight)
                    else null
                val bodyWidth =
                    (width - if (narrowed && !flowingText) floatWidth + imageGap else 0)
                        .coerceAtLeast(1)
                val body =
                    subcompose("body:${group.first().key}") {
                            val bodyContext = textContext.copy(textExclusion = exclusion)
                            if (element != null && element.isNativeBlock()) {
                                NativeHtmlBlock(element, bodyContext)
                            } else {
                                NativeHtmlText(group, bodyContext, Modifier.fillMaxWidth())
                            }
                        }
                        .single()
                        .measure(Constraints.fixedWidth(bodyWidth))
                placements +=
                    Triple(
                        body,
                        if (narrowed && !flowingText && !floatRight) floatWidth + imageGap else 0,
                        y,
                    )
                y += body.height
            }
            floats[index].forEach { image ->
                // 新浮图不能覆盖仍未结束的上一张浮图。
                val imageTop = maxOf(y, floatBottom)
                val requested = image.element.cssDimension("width")
                val rawWidth = image.element.cssProperty("width") ?: image.element.attr("width")
                val percentage = rawWidth?.takeIf { it.endsWith('%') }?.dropLast(1)?.toFloatOrNull()
                val imageWidth =
                    (requested?.dp?.roundToPx()
                            ?: percentage?.let { (width * it / 100f).toInt() }
                            ?: naturalSizes[image.element.key]?.width?.dp?.roundToPx()
                            ?: width)
                        .coerceIn(1, width.coerceAtLeast(1))
                val placeable =
                    subcompose("float:${image.element.key}") {
                            val linkModifier =
                                image.link?.let { url ->
                                    Modifier.clickable {
                                        if (url.startsWith('#')) context.onOpenAnchor(url)
                                        else context.actions.onOpenLink(url)
                                    }
                                } ?: Modifier
                            NativeHtmlImage(
                                image.element,
                                context,
                                nativeHtmlNodeModifier(image.element, context, linkModifier),
                                onIntrinsicSize = { imageWidth, imageHeight ->
                                    val size = IntSize(imageWidth, imageHeight)
                                    if (naturalSizes[image.element.key] != size) {
                                        naturalSizes[image.element.key] = size
                                    }
                                },
                            )
                        }
                        .single()
                        .measure(Constraints.fixedWidth(imageWidth))
                placements +=
                    Triple(placeable, if (image.right) width - imageWidth else 0, imageTop)
                floatBottom = imageTop + placeable.height
                floatWidth = imageWidth
                floatRight = image.right
            }
            previousBottom = bottom.roundToPx()
        }
        if (!trimEdges) y += previousBottom
        layout(width, maxOf(y, floatBottom)) {
            placements.forEach { (placeable, x, top) -> placeable.place(x, top) }
        }
    }
}
