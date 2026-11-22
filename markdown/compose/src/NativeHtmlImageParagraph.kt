package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlText

internal data class NativeHtmlParagraphImage(val element: HtmlElement, val link: String?)

internal data class NativeHtmlImageLine(
    val nodes: List<HtmlNode>,
    val images: List<NativeHtmlParagraphImage>?,
)

/** 只把独占硬换行的图片行取出；真正的图文混排继续交给行内文本布局。 */
internal fun nativeHtmlImageParagraphLines(
    nodes: List<HtmlNode>,
    context: NativeHtmlRenderContext,
): List<NativeHtmlImageLine>? {
    fun images(node: HtmlNode, link: String? = null): List<NativeHtmlParagraphImage>? =
        when (node) {
            is HtmlText -> if (node.text.isBlank()) emptyList() else null
            is HtmlElement ->
                when {
                    node.key in context.excludedImageKeys ||
                        node.isHidden(context.style.palette.isDark) -> emptyList()
                    node.tag == "img" -> listOf(NativeHtmlParagraphImage(node, link))
                    node.tag in setOf("a", "span", "strong", "b", "em", "i", "del", "s") -> {
                        val children = node.children.map { images(it, node.attr("href") ?: link) }
                        if (children.any { it == null }) null
                        else children.filterNotNull().flatten()
                    }
                    else -> null
                }
        }
    val lines = mutableListOf<List<HtmlNode>>()
    var pending = mutableListOf<HtmlNode>()
    nodes.forEach { node ->
        if (node is HtmlElement && node.tag == "br") {
            lines += pending.toList()
            pending = mutableListOf()
        } else pending += node
    }
    if (pending.isNotEmpty()) lines += pending.toList()
    val result = lines.map { line ->
        val candidates = line.map { images(it) }
        val pictures =
            if (candidates.any { it == null }) null else candidates.filterNotNull().flatten()
        NativeHtmlImageLine(line, pictures?.takeIf { it.isNotEmpty() })
    }
    return result.takeIf { it.any { line -> line.images != null } }
}

/** 高图片使用真实布局高度，避免多张图的基线被同一个文本行高压缩。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeHtmlImageParagraph(
    lines: List<NativeHtmlImageLine>,
    context: NativeHtmlRenderContext,
    modifier: Modifier,
) {
    val natural = remember(lines) { mutableStateMapOf<String, IntSize>() }
    val gap = context.cssTypography.emSpacing(context.textStyle.fontSize, .25f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        lines.forEach { line ->
            val images = line.images
            if (images == null) {
                NativeHtmlText(line.nodes, context, Modifier.fillMaxWidth())
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val availableWidth = maxWidth
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                gap,
                                when (context.textStyle.textAlign) {
                                    TextAlign.Center -> Alignment.CenterHorizontally
                                    TextAlign.Right,
                                    TextAlign.End -> Alignment.End
                                    else -> Alignment.Start
                                },
                            ),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        images.forEach { image ->
                            val element = image.element
                            val intrinsic = natural[element.key]
                            val requested =
                                element.cssDimension("width")
                                    ?: element.cssDimension("height")?.let { height ->
                                        intrinsic?.let { height * it.width / it.height }
                                    }
                            val width =
                                minOf(
                                    (requested ?: intrinsic?.width?.toFloat() ?: 16f).dp,
                                    availableWidth,
                                )
                            val link = image.link
                            val imageModifier =
                                Modifier.width(width)
                                    .align(
                                        when (element.cssProperty("vertical-align")) {
                                            "top",
                                            "text-top" -> Alignment.Top
                                            "middle" -> Alignment.CenterVertically
                                            else -> Alignment.Bottom
                                        },
                                    )
                                    .then(
                                        if (link == null) Modifier
                                        else
                                            Modifier.clickable {
                                                if (link.startsWith('#')) context.onOpenAnchor(link)
                                                else context.actions.onOpenLink(link)
                                            },
                                    )
                            NativeHtmlImage(
                                element,
                                context,
                                nativeHtmlNodeModifier(element, context, imageModifier),
                                onIntrinsicSize = { w, h -> natural[element.key] = IntSize(w, h) },
                            )
                        }
                    }
                }
            }
        }
    }
}
