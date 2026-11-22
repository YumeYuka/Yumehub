package moe.yumeyuka.markdown.core

/** 完整 IR 的文本投影；当前可见选择仍须由宿主按折叠与隐藏状态筛选。 */
internal object HtmlPlainText {
    fun project(nodes: List<HtmlNode>): String =
        Projection().apply { nodes.forEach(::visit) }.result()

    private class Projection {
        private val text = StringBuilder()
        private var pendingSpace = false
        private var preformatted = 0
        private var structuralBreak = -1

        fun result(): String =
            if (structuralBreak == text.lastIndex) text.dropLast(1).toString() else text.toString()

        fun visit(node: HtmlNode) {
            when (node) {
                is HtmlText -> append(node.text)
                is HtmlElement -> visitElement(node)
            }
        }

        private fun visitElement(node: HtmlElement) {
            if (node.tag == "br") {
                pendingSpace = false
                text.append('\n')
                structuralBreak = -1
                return
            }
            if (node.tag == "img") {
                append(node.attr("alt").orEmpty())
                return
            }
            node.semantic.math?.let {
                if (it.display) newline()
                append(it.source)
                if (it.display) newline()
                return
            }
            node.semantic.mermaid?.let {
                newline()
                text.append(it.source)
                structuralBreak = -1
                newline()
                return
            }
            val block = node.tag in BlockTags
            if (block) newline()
            if (node.tag == "pre") {
                pendingSpace = false
                preformatted++
            }
            var hasCell = false
            node.children.forEach { child ->
                if (node.tag == "tr" && child is HtmlElement && child.tag in CellTags) {
                    if (hasCell) {
                        pendingSpace = false
                        text.append('\t')
                    }
                    hasCell = true
                }
                visit(child)
            }
            if (node.tag == "pre") preformatted--
            if (block) newline()
        }

        private fun append(value: String) {
            if (preformatted > 0) {
                text.append(value)
                if (value.isNotEmpty()) structuralBreak = -1
                return
            }
            value.forEach { character ->
                if (character == ' ' || character in '\t'..'\r') {
                    pendingSpace = true
                } else {
                    if (
                        pendingSpace &&
                            text.isNotEmpty() &&
                            text.last() != '\n' &&
                            text.last() != '\t'
                    ) {
                        text.append(' ')
                    }
                    pendingSpace = false
                    text.append(character)
                    structuralBreak = -1
                }
            }
        }

        private fun newline() {
            pendingSpace = false
            if (text.isNotEmpty() && text.last() != '\n') {
                text.append('\n')
                structuralBreak = text.lastIndex
            }
        }
    }
}

private val BlockTags =
    setOf(
        "address",
        "article",
        "aside",
        "blockquote",
        "dd",
        "details",
        "div",
        "dl",
        "dt",
        "fieldset",
        "figcaption",
        "figure",
        "footer",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "header",
        "hr",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "pre",
        "section",
        "summary",
        "table",
        "tbody",
        "tfoot",
        "thead",
        "tr",
        "ul",
    )

private val CellTags = setOf("td", "th")
