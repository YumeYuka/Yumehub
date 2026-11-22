package moe.yumeyuka.yumehub.feature.markdown

import moe.yumeyuka.markdown.core.HtmlDocument
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlSemanticKind

/** 浮动、同行图片和表格必须保留整份 HTML 的布局上下文，不拆成独立原生媒体。 */
internal fun needsWebMarkdownLayout(document: HtmlDocument): Boolean =
    document.children.any(::needsWebLayout)

private fun needsWebLayout(node: HtmlNode): Boolean {
    if (node !is HtmlElement) return false
    // 引用状态图标仍由原生语义渲染，不把普通引用当作 SVG 图片。
    if (node.tag == "svg" && node.hasClass("octicon")) return false
    // 勾选框交给 WebView 的官方 HTML / CSS 外观，不再由原生 Canvas 绘制。
    if (node.semantic.kind == HtmlSemanticKind.TASK_CHECKBOX) return true
    return node.tag in WebLayoutTags ||
        WebLayoutAttributes.any { !node.attr(it).isNullOrBlank() } ||
        WebLayoutClasses.any(node::hasClass) ||
        node.children.any(::needsWebLayout)
}

private val WebLayoutTags =
    setOf("img", "picture", "svg", "table", "details", "video", "audio", "source")

private val WebLayoutAttributes = setOf("style", "align", "width", "height", "colspan", "rowspan")

private val WebLayoutClasses =
    setOf(
        "d-flex",
        "d-inline-flex",
        "d-table",
        "float-left",
        "float-right",
        "text-center",
        "text-right",
        "position-absolute",
        "position-relative",
        "markdown-accessibility-table",
    )
