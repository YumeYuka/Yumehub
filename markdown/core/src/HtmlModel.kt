package moe.yumeyuka.markdown.core

/** 解析上下文不包含网络客户端、账号或令牌。 */
data class HtmlParseOptions(
    val pageUrl: String? = null,
    val repositoryRootUrl: String? = null,
    val readme: Boolean = false,
    val ref: String? = null,
    val maxHtmlBytes: Int = 4 * 1024 * 1024,
    val maxNodes: Int = 100_000,
    val maxDepth: Int = 256,
    val viewerLogin: String? = null,
)

/** 解析器创建的集合均为不可变快照，不保留第三方 DOM。 */
@ConsistentCopyVisibility
data class HtmlDocument
internal constructor(
    val children: List<HtmlNode>,
    val anchors: Map<String, HtmlAnchor>,
    val plainText: String,
    val taskCount: Int,
    val diagnostics: List<HtmlDiagnostic>,
) {
    /** 与官方锚点行为一致：只解码一次，并兼容 user-content 前缀。 */
    fun resolveAnchor(raw: String): HtmlAnchor? {
        val name = decodeHtmlAnchor(raw.removePrefix("#"))
        return listOf(
                name,
                name.lowercase(),
                "user-content-$name",
                "user-content-${name.lowercase()}",
            )
            .firstNotNullOfOrNull(anchors::get)
    }
}

sealed interface HtmlNode {
    val key: String
}

/** 文本实体已由 DOM 解码；空白原样保存，white-space 由渲染层按上下文处理。 */
data class HtmlText(override val key: String, val text: String) : HtmlNode

@ConsistentCopyVisibility
data class HtmlElement
internal constructor(
    override val key: String,
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<HtmlNode>,
    val semantic: HtmlSemantic,
) : HtmlNode {
    fun attr(name: String): String? =
        attributes[name] ?: attributes.entries.firstOrNull { it.key.equals(name, true) }?.value

    fun hasClass(name: String): Boolean =
        attr("class")?.splitToSequence(' ', '\t', '\n', '\r', '\u000c')?.any { it == name } == true

    /** 适用于 code/pre/source；不会按视觉样式折叠空白或插入段落换行。 */
    fun textContent(): String = buildString { appendNodeText(this@HtmlElement) }

    /** 只序列化已清洗的 IR；SVG 属性保持标准大小写，可交给原生 SVG 解码器。 */
    fun toHtml(): String = buildString { appendNodeHtml(this@HtmlElement) }
}

private fun StringBuilder.appendNodeText(node: HtmlNode) {
    when (node) {
        is HtmlText -> append(node.text)
        is HtmlElement -> node.children.forEach(::appendNodeText)
    }
}

private fun StringBuilder.appendNodeHtml(node: HtmlNode) {
    when (node) {
        is HtmlText -> appendEscaped(node.text, attribute = false)
        is HtmlElement -> {
            append('<').append(node.tag)
            if (node.tag == "svg" && node.attr("xmlns") == null) {
                append(" xmlns=\"http://www.w3.org/2000/svg\"")
            }
            if (node.tag == "svg" && node.attr("xmlns:xlink") == null) {
                append(" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
            }
            node.attributes.forEach { (name, value) ->
                append(' ').append(name).append("=\"")
                appendEscaped(value, attribute = true)
                append('"')
            }
            append('>')
            node.children.forEach(::appendNodeHtml)
            if (node.tag !in HtmlVoidTags) append("</").append(node.tag).append('>')
        }
    }
}

private fun StringBuilder.appendEscaped(text: String, attribute: Boolean) {
    text.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> if (attribute) append("&quot;") else append(character)
            else -> append(character)
        }
    }
}

internal val HtmlVoidTags =
    setOf("area", "br", "col", "hr", "img", "input", "source", "track", "wbr")

enum class HtmlSemanticKind {
    GENERIC,
    TASK_CHECKBOX,
    TASK_LIST_BLOCK,
    SUGGESTION,
    ALERT,
    MATH,
    MERMAID,
    FOOTNOTE,
    FOOTNOTE_REFERENCE,
}

data class HtmlSemantic(
    val kind: HtmlSemanticKind = HtmlSemanticKind.GENERIC,
    val task: HtmlTask? = null,
    val suggestion: HtmlSuggestion? = null,
    val math: HtmlMath? = null,
    val mermaid: HtmlMermaid? = null,
    val alert: HtmlAlert? = null,
    val media: HtmlMedia? = null,
    val anchorNames: List<String> = emptyList(),
)

data class HtmlTask(val position: Int, val checked: Boolean, val disabled: Boolean)

@ConsistentCopyVisibility
data class HtmlSuggestion
internal constructor(
    val id: String?,
    val rawHtml: String,
    val lines: List<HtmlSuggestionLine>,
)

@ConsistentCopyVisibility
data class HtmlSuggestionLine
internal constructor(
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val change: HtmlLineChange,
    val text: String,
    val content: List<HtmlNode>,
)

enum class HtmlLineChange {
    CONTEXT,
    ADDITION,
    DELETION,
}

data class HtmlMath(val source: String, val display: Boolean)

data class HtmlMermaid(val source: String)

data class HtmlAlert(val kind: String)

/**
 * 图片地址均已通过同一 URL 安全规则归一化。img 的 url 保留 src；没有 src 时使用首个候选， 仅作为旧调用方的兼容入口，不代表按主题或 DPR 选中了资源。
 *
 * candidates 仅保存 img 或 picture 直接 source 的 srcset，不混入 src；解析结果列表为不可变快照， 保留源码顺序和重复描述符，由渲染层在资源选择时处理。
 * media/type/sizes 继续保存在元素属性中；具体主题、格式、布局宽度与 DPR 选择归渲染层。
 */
data class HtmlMedia(
    val url: String,
    val fallbackUrl: String? = null,
    val candidates: List<HtmlMediaCandidate> = emptyList(),
)

data class HtmlMediaCandidate(
    val url: String,
    val fallbackUrl: String? = null,
    val descriptor: HtmlMediaDescriptor = HtmlMediaDescriptor.Density(1.0),
)

/** 无描述符按 1x 保存；仅接收单个 w/x 描述符。零值、非有限密度及超出 Int 的宽度不进入候选， 不将未来兼容的 h 描述符等扩展误当成可选择的资源。 */
sealed interface HtmlMediaDescriptor {
    data class Density(val value: Double) : HtmlMediaDescriptor {
        init {
            require(value.isFinite() && value > 0)
        }
    }

    data class Width(val value: Int) : HtmlMediaDescriptor {
        init {
            require(value > 0)
        }
    }
}

@ConsistentCopyVisibility
data class HtmlAnchor
internal constructor(
    val name: String,
    val nodeKey: String,
    val ancestorKeys: List<String>,
)

data class HtmlDiagnostic(val kind: HtmlDiagnosticKind, val nodeKey: String, val detail: String)

enum class HtmlDiagnosticKind {
    REMOVED_ELEMENT,
    REMOVED_ATTRIBUTE,
    BLOCKED_URL,
    INVALID_METADATA,
    UNSUPPORTED_ELEMENT,
}

/** 明确失败由宿主显示；超限时不截断后冒充完整文档。 */
class HtmlParseException(message: String) : IllegalArgumentException(message)
