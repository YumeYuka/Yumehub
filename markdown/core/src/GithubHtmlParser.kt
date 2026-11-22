package moe.yumeyuka.markdown.core

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 只消费服务端 HTML；不解析 Markdown、不执行脚本，也不请求网络。 */
object GithubHtmlParser {
    fun parse(html: String, options: HtmlParseOptions = HtmlParseOptions()): HtmlDocument {
        require(options.maxHtmlBytes > 0 && options.maxNodes > 0 && options.maxDepth > 0)
        if (
            html.length > options.maxHtmlBytes ||
                html.toByteArray(Charsets.UTF_8).size > options.maxHtmlBytes
        ) {
            throw HtmlParseException("HTML 超过允许的字节数")
        }
        return ParseSession(options).parse(html)
    }
}

private class ParseSession(private val options: HtmlParseOptions) {
    private val diagnostics = mutableListOf<HtmlDiagnostic>()
    private val anchors = linkedMapOf<String, HtmlAnchor>()
    private val nodes = IdentityHashMap<Element, HtmlElement>()
    private val specialSources = IdentityHashMap<Element, SpecialSource>()
    private val imageCandidates = IdentityHashMap<Element, List<HtmlMediaCandidate>>()
    private val urls = HtmlUrlResolver(options)
    private var taskCount = 0

    fun parse(html: String): HtmlDocument {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        validateTree(body)
        sanitize(body)
        markViewerMentions(body)
        val children =
            freeze(
                body.childNodes().mapIndexedNotNull { i, node ->
                    convert(node, "n$i", emptyList())
                },
            )
        return HtmlDocument(
            children = children,
            anchors = freezeMap(anchors),
            plainText = HtmlPlainText.project(children),
            taskCount = taskCount,
            diagnostics = freeze(diagnostics),
        )
    }

    private fun markViewerMentions(body: Element) {
        val login = options.viewerLogin?.takeIf { it.isNotBlank() }
        val host =
            sequenceOf(options.pageUrl, options.repositoryRootUrl)
                .filterNotNull()
                .mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }
                .firstOrNull() ?: "github.com"
        body.getElementsByClass("user-mention").forEach { mention ->
            // viewer 样式由当前宿主账号决定，服务端旧 HTML 不能携带上一账号的高亮。
            mention.removeClass("user-mention-viewer")
            if (login != null) {
                val target = runCatching { java.net.URI(mention.attr("href")) }.getOrNull()
                if (
                    target?.host.equals(host, true) &&
                        target?.path?.trim('/')?.equals(login, true) == true
                ) {
                    mention.addClass("user-mention-viewer")
                }
            }
        }
    }

    private fun validateTree(body: Element) {
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(body to 0)
        var count = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (++count > options.maxNodes) throw HtmlParseException("HTML 节点数超过允许范围")
            if (depth > options.maxDepth) throw HtmlParseException("HTML 嵌套深度超过允许范围")
            node.childNodes().forEach { pending.add(it to depth + 1) }
        }
    }

    private fun sanitize(body: Element) {
        // 先提取惰性元数据，随后清除所有主动元素；源码不会作为脚本执行。
        body.getAllElements().forEach { element ->
            specialSource(element)?.let { specialSources[element] = it }
        }
        val pending = ArrayDeque<Node>()
        pending.add(body)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            when (node) {
                is Comment,
                is DataNode -> node.remove()
                is Element -> {
                    val tag = node.normalName()
                    if (!isSafeName(tag)) {
                        diagnostics +=
                            HtmlDiagnostic(
                                HtmlDiagnosticKind.UNSUPPORTED_ELEMENT,
                                "",
                                "invalid tag name",
                            )
                        val children = node.childNodes().toList()
                        node.unwrap()
                        children.asReversed().forEach(pending::add)
                        continue
                    }
                    if (tag in RemovedTags) {
                        diagnostics += HtmlDiagnostic(HtmlDiagnosticKind.REMOVED_ELEMENT, "", tag)
                        node.remove()
                        continue
                    }
                    sanitizeAttributes(node)
                    node.childNodes().asReversed().forEach(pending::add)
                }
            }
        }
    }

    private fun sanitizeAttributes(element: Element) {
        val tag = element.normalName()
        element.attributes().asList().forEach { attribute ->
            val name = attribute.key.lowercase()
            val value = attribute.value
            when {
                !isAllowedAttribute(name) || name.startsWith("on") -> {
                    element.removeAttr(attribute.key)
                    removedAttribute(name)
                }
                name == "style" -> {
                    val cleaned = cleanStyle(value)
                    if (cleaned.isEmpty()) element.removeAttr(attribute.key)
                    else element.attr(attribute.key, cleaned)
                    if (cleaned != value) removedAttribute("style")
                }
                name == "href" || name == "xlink:href" -> {
                    replaceUrl(
                        element,
                        attribute.key,
                        urls.link(value, allowMailto = tag == "a" && name == "href"),
                    )
                }
                name == "src" || name == "poster" -> {
                    replaceUrl(element, attribute.key, urls.media(value, image = isImage(element)))
                }
                name == "srcset" -> {
                    val candidates = urls.srcset(value)
                    if (isImage(element)) imageCandidates[element] = candidates
                    val cleaned =
                        candidates
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { candidate ->
                                val descriptor =
                                    when (val descriptor = candidate.descriptor) {
                                        is HtmlMediaDescriptor.Density ->
                                            descriptor.value.toString().removeSuffix(".0") + "x"
                                        is HtmlMediaDescriptor.Width -> "${descriptor.value}w"
                                    }
                                "${candidate.url} $descriptor"
                            }
                    replaceUrl(element, attribute.key, cleaned)
                }
                name in SvgReferenceAttributes && !isLocalSvgReference(value) -> {
                    element.removeAttr(attribute.key)
                    removedAttribute(name)
                }
            }
        }
        if (
            tag == "button" ||
                tag == "select" ||
                tag == "textarea" ||
                (tag == "input" && !isTask(element))
        ) {
            element.attr("disabled", "")
        }
    }

    private fun replaceUrl(element: Element, name: String, safe: String?) {
        if (safe != null) element.attr(name, safe)
        else {
            element.removeAttr(name)
            diagnostics += HtmlDiagnostic(HtmlDiagnosticKind.BLOCKED_URL, "", name)
        }
    }

    private fun removedAttribute(name: String) {
        diagnostics += HtmlDiagnostic(HtmlDiagnosticKind.REMOVED_ATTRIBUTE, "", name)
    }

    private fun convert(node: Node, key: String, ancestors: List<String>): HtmlNode? =
        when (node) {
            is TextNode -> HtmlText(key, node.wholeText)
            is Element -> convertElement(node, key, ancestors)
            else -> null
        }

    private fun convertElement(
        element: Element,
        key: String,
        ancestors: List<String>,
    ): HtmlElement {
        val tag = canonicalTag(element)
        val names =
            freeze(
                listOfNotNull(
                        element.attr("id").ifBlank { null },
                        element.attr("name").ifBlank { null },
                    )
                    .distinct(),
            )
        names.forEach { name ->
            anchors.putIfAbsent(name, HtmlAnchor(name, key, freeze(ancestors)))
        }
        // 任务编号在进入子节点前生成，严格保持官方 querySelectorAll 的前序 DOM 次序。
        val task =
            if (isTask(element)) {
                HtmlTask(taskCount++, element.hasAttr("checked"), element.hasAttr("disabled"))
            } else null
        val children =
            freeze(
                element.childNodes().mapIndexedNotNull { i, child ->
                    convert(child, "$key.$i", ancestors + key)
                },
            )
        val special = specialSources[element]
        val suggestion =
            if (element.hasClass("js-suggested-changes-blob")) suggestion(element) else null
        val alert =
            if (element.hasClass("markdown-alert")) {
                HtmlAlert(
                    element
                        .classNames()
                        .firstOrNull {
                            it.startsWith("markdown-alert-") && it != "markdown-alert-title"
                        }
                        ?.removePrefix("markdown-alert-") ?: "note",
                )
            } else null
        val media =
            if (isImage(element)) {
                val candidates = imageCandidates[element] ?: emptyList()
                val url = element.attr("src").ifBlank { candidates.firstOrNull()?.url }
                url?.let { HtmlMedia(it, urls.imageFallback(it), candidates) }
            } else null
        val kind =
            when {
                special?.math != null -> HtmlSemanticKind.MATH
                special?.mermaid != null -> HtmlSemanticKind.MERMAID
                task != null -> HtmlSemanticKind.TASK_CHECKBOX
                suggestion != null -> HtmlSemanticKind.SUGGESTION
                alert != null -> HtmlSemanticKind.ALERT
                element.hasClass("tasklist-block") ||
                    element.hasClass("task-list-block") ||
                    element.hasClass("tlb") ||
                    element.hasClass("contains-task-list") -> HtmlSemanticKind.TASK_LIST_BLOCK
                element.hasAttr("data-footnote-ref") -> HtmlSemanticKind.FOOTNOTE_REFERENCE
                element.hasAttr("data-footnotes") || element.hasClass("footnotes") ->
                    HtmlSemanticKind.FOOTNOTE
                else -> HtmlSemanticKind.GENERIC
            }
        if (tag.lowercase() !in KnownTags && special == null) {
            diagnostics += HtmlDiagnostic(HtmlDiagnosticKind.UNSUPPORTED_ELEMENT, key, tag)
        }
        return HtmlElement(
                key = key,
                tag = tag,
                attributes =
                    freezeMap(
                        element.attributes().asList().associate { attribute ->
                            canonicalAttribute(attribute.key) to attribute.value
                        },
                    ),
                children = children,
                semantic =
                    HtmlSemantic(
                        kind = kind,
                        task = task,
                        suggestion = suggestion,
                        math = special?.math,
                        mermaid = special?.mermaid,
                        alert = alert,
                        media = media,
                        anchorNames = names,
                    ),
            )
            .also { nodes[element] = it }
    }

    private fun suggestion(element: Element): HtmlSuggestion {
        val lines =
            element.select("tr").mapNotNull { row ->
                val cells =
                    row.children().filter { it.normalName() == "td" || it.normalName() == "th" }
                val code =
                    cells.firstOrNull { it.hasClass("blob-code") || it.hasClass("diff-line-code") }
                        ?: return@mapNotNull null
                val inner = code.selectFirst(".blob-code-inner") ?: code
                val content = nodes[inner]?.children ?: emptyList()
                val change =
                    when {
                        code.hasClass("blob-code-addition") || row.hasClass("addition") ->
                            HtmlLineChange.ADDITION
                        code.hasClass("blob-code-deletion") || row.hasClass("deletion") ->
                            HtmlLineChange.DELETION
                        else -> HtmlLineChange.CONTEXT
                    }
                val numbers = cells.filter { it.hasClass("blob-num") }
                fun number(cell: Element?): Int? = cell?.attr("data-line-number")?.toIntOrNull()
                val old =
                    row.attr("data-old-line-number").toIntOrNull()
                        ?: number(numbers.firstOrNull { it.hasClass("blob-num-deletion") })
                        ?: if (change != HtmlLineChange.ADDITION) number(numbers.firstOrNull())
                        else null
                val new =
                    row.attr("data-new-line-number").toIntOrNull()
                        ?: number(numbers.firstOrNull { it.hasClass("blob-num-addition") })
                        ?: if (change != HtmlLineChange.DELETION) number(numbers.lastOrNull())
                        else null
                HtmlSuggestionLine(old, new, change, inner.wholeText(), content)
            }
        return HtmlSuggestion(
            id = element.id().ifBlank { element.attr("data-suggestion-id").ifBlank { null } },
            rawHtml = element.outerHtml(),
            lines = freeze(lines),
        )
    }

    private fun specialSource(element: Element): SpecialSource? {
        val tag = element.normalName()
        val classes = element.classNames()
        val math = tag == "math-renderer" || tag == "math" || classes.any { it in MathClasses }
        if (math) {
            val source =
                firstAttribute(element, "data-math", "data-latex", "data-source", "data-code")
                    ?: element.selectFirst("annotation[encoding=application/x-tex]")?.wholeText()
                    ?: element.selectFirst(".math")?.wholeText()
                    ?: element.wholeText()
            val display =
                element.attr("data-type").lowercase() in setOf("display", "block") ||
                    element.attr("display") == "block" ||
                    classes.any { it in DisplayMathClasses } ||
                    source.trim().startsWith("$$") ||
                    source.trim().startsWith("\\[")
            val stripped = stripMathDelimiters(source)
            if (stripped.isBlank()) invalidMetadata("math source missing")
            return SpecialSource(math = HtmlMath(stripped, display))
        }
        val mermaidMarker =
            element.attr("data-type").equals("mermaid", true) ||
                classes.any { it in MermaidClasses } ||
                (tag == "pre" &&
                    element.selectFirst("code.language-mermaid, code.lang-mermaid") != null)
        val carrier =
            if (mermaidMarker && !element.hasAttr("data-json")) {
                element.selectFirst(
                    ".js-render-enrichment-target[data-json], [data-json], [data-plain]",
                ) ?: element
            } else element
        val json = carrier.attr("data-json").takeIf(String::isNotBlank)
        val jsonValue =
            if (json != null) {
                try {
                    Json.parseToJsonElement(json)
                } catch (_: SerializationException) {
                    if (mermaidMarker) invalidMetadata("data-json is not valid JSON")
                    null
                }
            } else null
        val mermaid =
            mermaidMarker ||
                ((jsonValue as? JsonObject)?.get("lang") as? JsonPrimitive)?.content == "mermaid" ||
                ((jsonValue as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "mermaid"
        if (mermaid) {
            val plain =
                carrier.attr("data-plain").takeUnless {
                    it.isBlank() || it == "true" || it == "false"
                }
            val source =
                jsonValue?.let { jsonSource(it) }
                    ?: firstAttribute(carrier, "data-source", "data-code")
                    ?: plain
                    ?: element.selectFirst("pre code")?.wholeText()
                    ?: element.wholeText()
            if (source.isBlank()) invalidMetadata("mermaid source missing")
            return SpecialSource(mermaid = HtmlMermaid(source))
        }
        return null
    }

    private fun invalidMetadata(detail: String) {
        diagnostics += HtmlDiagnostic(HtmlDiagnosticKind.INVALID_METADATA, "", detail)
    }
}

private data class SpecialSource(val math: HtmlMath? = null, val mermaid: HtmlMermaid? = null)

private fun jsonSource(json: JsonElement, depth: Int = 0): String? {
    if (depth >= 64) throw HtmlParseException("JSON 元数据嵌套深度超过允许范围")
    return when (json) {
        is JsonPrimitive -> json.takeIf { it.isString }?.content
        is JsonArray -> json.firstNotNullOfOrNull { jsonSource(it, depth + 1) }
        is JsonObject -> {
            val direct =
                listOf("source", "code", "raw", "text").firstNotNullOfOrNull { key ->
                    (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            direct
                ?: listOf("data", "payload", "content").firstNotNullOfOrNull { key ->
                    json[key]?.let { jsonSource(it, depth + 1) }
                }
        }
    }
}

private fun firstAttribute(element: Element, vararg names: String): String? =
    names.firstNotNullOfOrNull {
        element.attr(it).takeIf(String::isNotBlank)
    }

private fun stripMathDelimiters(raw: String): String {
    val source = raw.trim()
    for ((start, end) in listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")) {
        if (
            source.length >= start.length + end.length &&
                source.startsWith(start) &&
                source.endsWith(end)
        ) {
            return source.substring(start.length, source.length - end.length)
        }
    }
    return source
}

private fun isTask(element: Element): Boolean = element.classNames().any { it in TaskClasses }

private fun isAllowedAttribute(name: String): Boolean =
    isSafeName(name) &&
        (name.startsWith("data-") ||
            name.startsWith("aria-") ||
            name in AllowedAttributes ||
            name in SvgAttributes)

private fun isSafeName(name: String): Boolean =
    name.isNotEmpty() &&
        (name.first() in 'a'..'z' || name.first() == '_') &&
        name.all { it in 'a'..'z' || it in '0'..'9' || it in "_:-." }

private fun cleanStyle(raw: String): String =
    raw.split(';')
        .mapNotNull { declaration ->
            val separator = declaration.indexOf(':')
            if (separator < 0) return@mapNotNull null
            val name = declaration.substring(0, separator).trim().lowercase()
            val value = declaration.substring(separator + 1).trim()
            val lower = value.lowercase()
            if (
                name !in AllowedStyleProperties ||
                    value.isBlank() ||
                    lower.contains("url(") ||
                    lower.contains("expression(") ||
                    lower.contains("@import") ||
                    lower.contains("javascript:") ||
                    lower.contains("\\") ||
                    value.any { it.code < 0x20 }
            ) {
                null
            } else "$name: $value"
        }
        .joinToString("; ")

private fun isLocalSvgReference(value: String): Boolean {
    if (!value.contains("url", ignoreCase = true)) return true
    val normalized = value.trim()
    if (!normalized.startsWith("url(", true) || !normalized.endsWith(')')) return false
    val reference = normalized.substring(4, normalized.length - 1).trim().trim('\'', '"')
    return reference.startsWith('#') && reference.length > 1 && reference.none { it.isWhitespace() }
}

private fun canonicalTag(element: Element): String =
    SvgTags[element.normalName()] ?: element.normalName()

private fun isImage(element: Element): Boolean =
    element.normalName() == "img" ||
        (element.normalName() == "source" && element.parent()?.normalName() == "picture")

private fun canonicalAttribute(name: String): String =
    SvgAttributeNames[name.lowercase()] ?: name.lowercase()

internal fun <T> freeze(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> freezeMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private val RemovedTags =
    setOf(
        "script",
        "iframe",
        "frame",
        "frameset",
        "object",
        "embed",
        "base",
        "meta",
        "link",
        "style",
        "form",
        "template",
        "applet",
        "noscript",
        "foreignobject",
        "animate",
        "animatetransform",
        "animatemotion",
        "set",
    )

private val TaskClasses =
    setOf("task-list-item-checkbox", "tlb-checkbox", "tlb-issue-reference-number")
private val MathClasses =
    setOf("math", "math-inline", "math-display", "js-inline-math", "js-display-math")
private val DisplayMathClasses = setOf("math-display", "js-display-math", "display-math")
private val MermaidClasses = setOf("mermaid", "language-mermaid", "lang-mermaid")

private val AllowedAttributes =
    setOf(
        "id",
        "name",
        "class",
        "style",
        "title",
        "lang",
        "dir",
        "role",
        "hidden",
        "href",
        "src",
        "srcset",
        "sizes",
        "media",
        "poster",
        "alt",
        "width",
        "height",
        "align",
        "valign",
        "colspan",
        "rowspan",
        "scope",
        "start",
        "reversed",
        "value",
        "type",
        "checked",
        "disabled",
        "open",
        "controls",
        "loop",
        "muted",
        "preload",
        "loading",
        "decoding",
        "kind",
        "srclang",
        "label",
        "default",
        "datetime",
        "cite",
        "xmlns",
        "xmlns:xlink",
        "xlink:href",
        "tabindex",
        "encoding",
    )

private val AllowedStyleProperties =
    setOf(
        "color",
        "background-color",
        "text-align",
        "vertical-align",
        "font-weight",
        "font-style",
        "font-size",
        "line-height",
        "text-decoration",
        "text-decoration-line",
        "white-space",
        "width",
        "height",
        "min-width",
        "max-width",
        "min-height",
        "max-height",
        "display",
        "visibility",
        "float",
        "font-family",
        "letter-spacing",
        "word-break",
        "overflow-wrap",
        "clear",
        "padding",
        "padding-left",
        "padding-right",
        "padding-top",
        "padding-bottom",
        "margin",
        "margin-left",
        "margin-right",
        "margin-top",
        "margin-bottom",
        "border",
        "border-width",
        "border-style",
        "border-color",
        "border-radius",
        "fill",
        "stroke",
    )

private val SvgAttributeNames =
    listOf(
            "viewBox",
            "preserveAspectRatio",
            "gradientUnits",
            "gradientTransform",
            "patternUnits",
            "patternContentUnits",
            "patternTransform",
            "clipPathUnits",
            "markerWidth",
            "markerHeight",
            "markerUnits",
            "refX",
            "refY",
            "textLength",
            "lengthAdjust",
            "startOffset",
            "pathLength",
            "filterUnits",
            "primitiveUnits",
            "stdDeviation",
            "baseFrequency",
            "numOctaves",
            "kernelMatrix",
            "kernelUnitLength",
            "keyPoints",
            "keySplines",
            "keyTimes",
        )
        .associateBy(String::lowercase)

private val SvgAttributes =
    SvgAttributeNames.keys +
        setOf(
            "d",
            "fill",
            "fill-rule",
            "fill-opacity",
            "stroke",
            "stroke-width",
            "stroke-linecap",
            "stroke-linejoin",
            "stroke-miterlimit",
            "stroke-dasharray",
            "stroke-dashoffset",
            "stroke-opacity",
            "opacity",
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "cx",
            "cy",
            "r",
            "rx",
            "ry",
            "points",
            "transform",
            "clip-path",
            "clip-rule",
            "mask",
            "offset",
            "stop-color",
            "stop-opacity",
            "orientation",
            "orient",
            "vector-effect",
            "text-anchor",
            "dominant-baseline",
            "font-family",
            "font-size",
            "font-weight",
            "version",
            "focusable",
            "display",
            "overflow",
        )

private val SvgReferenceAttributes = setOf("fill", "stroke", "clip-path", "mask")

private val SvgTags =
    listOf("linearGradient", "radialGradient", "clipPath", "textPath")
        .associateBy(String::lowercase)

private val KnownTags =
    setOf(
        "a",
        "abbr",
        "article",
        "aside",
        "b",
        "bdi",
        "bdo",
        "blockquote",
        "br",
        "caption",
        "cite",
        "code",
        "col",
        "colgroup",
        "dd",
        "del",
        "details",
        "dfn",
        "div",
        "dl",
        "dt",
        "em",
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
        "i",
        "img",
        "input",
        "ins",
        "kbd",
        "li",
        "main",
        "mark",
        "nav",
        "ol",
        "p",
        "pre",
        "q",
        "rp",
        "rt",
        "ruby",
        "s",
        "samp",
        "section",
        "small",
        "span",
        "strong",
        "sub",
        "summary",
        "sup",
        "table",
        "tbody",
        "td",
        "tfoot",
        "th",
        "thead",
        "time",
        "tr",
        "u",
        "ul",
        "var",
        "wbr",
        "button",
        "select",
        "textarea",
        "option",
        "audio",
        "video",
        "picture",
        "source",
        "track",
        "svg",
        "g",
        "path",
        "rect",
        "circle",
        "ellipse",
        "line",
        "polyline",
        "polygon",
        "text",
        "tspan",
        "defs",
        "lineargradient",
        "radialgradient",
        "stop",
        "use",
        "symbol",
        "clippath",
        "mask",
        "pattern",
        "title",
        "desc",
        "image",
        "textpath",
        "math",
        "math-renderer",
        "mrow",
        "mi",
        "mn",
        "mo",
        "msup",
        "msub",
        "mfrac",
        "msqrt",
        "mroot",
        "mtable",
        "mtr",
        "mtd",
        "semantics",
        "annotation",
        "mtext",
    )
