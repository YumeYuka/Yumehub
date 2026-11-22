package moe.yumeyuka.markdown.sample

import android.util.Xml
import java.io.StringReader
import java.io.StringWriter
import moe.yumeyuka.markdown.core.GithubHtmlParser
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlParseOptions
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** 对象身份来自夹具目录，语义来自本端实际解析输入，不以 URL 或 alt 合并实例。 */
data class AcceptanceObjectInput(
    val id: String,
    val nodeKey: String,
    val kind: String,
    val geometryTag: String,
    val parentNodeId: String?,
    val semantics: JSONObject,
    val expectedHidden: Boolean,
)

object AcceptanceObjectInputs {
    fun read(fixture: AcceptanceFixture, dark: Boolean): Map<String, AcceptanceObjectInput> {
        val document =
            GithubHtmlParser.parse(
                fixture.html,
                HtmlParseOptions(
                    pageUrl = fixture.pageUrl,
                    repositoryRootUrl = fixture.repositoryRootUrl,
                ),
            )
        val catalogue = fixture.geometryNodeIds.toSet()
        val output = linkedMapOf<String, AcceptanceObjectInput>()
        fun visit(nodes: List<HtmlNode>, parent: String?, hidden: Boolean) {
            for (element in nodes.filterIsInstance<HtmlElement>()) {
                val tagId = element.attr("data-fixture-id") ?: element.attr("id") ?: element.key
                val href = element.attr("href") ?: element.attr("src")
                val excluded =
                    hidden ||
                        element.attr("hidden") != null ||
                        (dark && href?.endsWith("#gh-light-mode-only") == true) ||
                        (!dark && href?.endsWith("#gh-dark-mode-only") == true)
                val objectId = element.attr("data-fixture-object-id")
                if (objectId != null) {
                    val kind =
                        when {
                            element.semantic.math != null -> "math"
                            element.semantic.mermaid != null -> "mermaid"
                            element.semantic.task != null -> "task"
                            element.tag == "img" -> "image"
                            element.tag == "svg" -> "svg"
                            element.tag in setOf("video", "audio") -> element.tag
                            else -> "unknown"
                        }
                    val semantics = JSONObject()
                    when (kind) {
                        "image" -> semantics.put("alt", element.attr("alt") ?: JSONObject.NULL)
                        "math" ->
                            element.semantic.math?.let {
                                semantics.put("source", it.source).put("display", it.display)
                            }
                        "mermaid" -> semantics.put("source", element.semantic.mermaid?.source)
                        "svg" -> semantics.put("source", cleanSvg(element.toHtml()))
                        "task" ->
                            element.semantic.task?.let {
                                semantics.put("checked", it.checked).put("position", it.position)
                            }
                        "video",
                        "audio" -> {
                            semantics
                                .put(
                                    "url",
                                    element.semantic.media?.url
                                        ?: element.attr("src")
                                        ?: JSONObject.NULL,
                                )
                                .put("controls", element.attr("controls") != null)
                                .put("loop", element.attr("loop") != null)
                                .put("muted", element.attr("muted") != null)
                        }
                    }
                    check(
                        output.put(
                            objectId,
                            AcceptanceObjectInput(
                                objectId,
                                element.key,
                                kind,
                                "html:$tagId",
                                parent,
                                semantics,
                                excluded,
                            ),
                        ) == null,
                    ) {
                        "Duplicate fixture object: $objectId"
                    }
                }
                visit(element.children, if (tagId in catalogue) tagId else parent, excluded)
            }
        }
        visit(document.children, null, false)
        return output
    }

    /** 使用 namespace-aware XML 序列化移除测试标记，保留路径、文字、属性值与节点顺序。 */
    private fun cleanSvg(source: String): String {
        val reader = Xml.newPullParser()
        reader.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        reader.setInput(StringReader(source))
        val output = StringWriter()
        val writer = Xml.newSerializer()
        writer.setOutput(output)
        writer.setPrefix("", "http://www.w3.org/2000/svg")
        writer.setPrefix("xlink", "http://www.w3.org/1999/xlink")
        while (reader.eventType != XmlPullParser.END_DOCUMENT) {
            when (reader.eventType) {
                XmlPullParser.START_TAG -> {
                    writer.startTag(reader.namespace, reader.name)
                    for (i in 0 until reader.attributeCount) {
                        val name = reader.getAttributeName(i)
                        if (!name.startsWith("data-fixture-"))
                            writer.attribute(
                                reader.getAttributeNamespace(i),
                                name,
                                reader.getAttributeValue(i),
                            )
                    }
                }
                XmlPullParser.END_TAG -> writer.endTag(reader.namespace, reader.name)
                XmlPullParser.TEXT,
                XmlPullParser.CDSECT -> writer.text(reader.text)
            }
            reader.next()
        }
        writer.flush()
        return output.toString()
    }
}
