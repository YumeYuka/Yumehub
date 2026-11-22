package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

data class SearchSlice<T>(val items: List<T>, val nextCursor: String?, val total: Int? = null)

@Serializable
data class SearchCodeFile(
    val owner: String,
    val repository: String,
    val ref: String,
    val path: String,
    val language: String?,
    val snippets: List<SearchSnippet>,
    val ownerAvatarUrl: String? = null,
)

@Serializable
data class SearchSnippet(
    val start: Int,
    val end: Int,
    val jump: Int,
    val lines: List<SearchCodeLine>,
)

@Serializable data class SearchCodeLine(val text: String, val matches: List<SearchCodeMatch>)

@Serializable data class SearchCodeMatch(val start: Int, val end: Int)

/** 先保留命中，再从返回的片段中取最近的上下文；重叠片段只显示一次。 */
fun SearchCodeFile.previewLines(limit: Int): List<Map.Entry<Int, SearchCodeLine>> {
    val rows = sortedMapOf<Int, SearchCodeLine>()
    val hits = sortedSetOf<Int>()
    for (snippet in snippets) {
        var marked = false
        snippet.lines.forEachIndexed { index, line ->
            val number = snippet.start + index
            if (number > snippet.end) return@forEachIndexed
            if (line.matches.isNotEmpty()) {
                hits += number
                rows[number] = line
                marked = true
            } else rows.putIfAbsent(number, line)
        }
        if (!marked && snippet.jump in rows && snippet.jump in snippet.start..snippet.end)
            hits += snippet.jump
    }
    val selected = hits.take(limit).toMutableSet()
    if (selected.size < limit) {
        val anchors = selected.toList()
        selected +=
            rows.keys
                .asSequence()
                .filterNot { it in selected }
                .sortedBy { n -> anchors.minOfOrNull { kotlin.math.abs(n - it) } ?: 0 }
                .take(limit - selected.size)
                .toList()
    }
    return rows.entries.filter { it.key in selected }
}

internal fun parseSearchCodeLine(html: String): SearchCodeLine {
    if ('<' !in html && '&' !in html) return SearchCodeLine(html, emptyList())
    val text = StringBuilder()
    val matches = mutableListOf<SearchCodeMatch>()
    var markDepth = 0
    var start = 0
    Jsoup.parseBodyFragment(html)
        .body()
        .traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    if (node is TextNode) text.append(node.wholeText)
                    if (node is Element && node.normalName() == "mark" && markDepth++ == 0) {
                        start = text.length
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (
                        node is Element &&
                            node.normalName() == "mark" &&
                            --markDepth == 0 &&
                            start < text.length
                    ) {
                        matches += SearchCodeMatch(start, text.length)
                    }
                }
            },
        )
    return SearchCodeLine(text.toString(), matches)
}

internal fun JsonObject.nextSearchCursor(after: String?): String? {
    val info = this["pageInfo"] as? JsonObject ?: throw DashboardFeedException()
    return when (info["hasNextPage"]?.jsonPrimitive?.booleanOrNull) {
        false -> null
        true ->
            info.searchText("endCursor")?.takeIf { it.isNotBlank() && it != after }
                ?: throw DashboardFeedException()
        null -> throw DashboardFeedException()
    }
}

internal fun JsonObject.searchNodes(): List<JsonObject> =
    (this["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: throw DashboardFeedException()

internal fun JsonObject.searchText(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.requiredSearchText(key: String): String =
    searchText(key)?.takeIf { it.isNotBlank() } ?: throw DashboardFeedException()

internal fun parseSearchCode(connection: JsonObject, after: String?): SearchSlice<SearchCodeFile> {
    val files =
        connection.searchNodes().map { node ->
            val repo = node.getValue("repository").jsonObject
            SearchCodeFile(
                repo.getValue("owner").jsonObject.requiredSearchText("login"),
                repo.requiredSearchText("name"),
                node.requiredSearchText("refName"),
                node.requiredSearchText("path"),
                (node["language"] as? JsonObject)?.searchText("name"),
                node.getValue("snippets").jsonArray.map { item ->
                    val snippet = item.jsonObject
                    SearchSnippet(
                        snippet.getValue("startingLineNumber").jsonPrimitive.int,
                        snippet.getValue("endingLineNumber").jsonPrimitive.int,
                        snippet.getValue("jumpToLineNumber").jsonPrimitive.int,
                        snippet.getValue("lines").jsonArray.map {
                            parseSearchCodeLine(it.jsonPrimitive.content)
                        },
                    )
                },
                ownerAvatarUrl = repo.getValue("owner").jsonObject.searchText("avatarUrl"),
            )
        }
    return SearchSlice(files, connection.nextSearchCursor(after))
}

internal val ActorSearchQuery =
    """
    query ExploreActors(${'$'}query: String!, ${'$'}after: String) {
      search(query: ${'$'}query, type: USER, first: 30, after: ${'$'}after) {
        userCount pageInfo { hasNextPage endCursor }
        nodes { __typename ... on User { login name avatarUrl }
                ... on Organization { login name avatarUrl } }
      }
    }
    """
        .trimIndent()

internal val CodeSearchQuery =
    """
    query GlobalCodeSearch(${'$'}query: String!, ${'$'}after: String) {
      codeSearch(query: ${'$'}query, first: 30, after: ${'$'}after) {
        pageInfo { hasNextPage endCursor }
        nodes { repository { name owner { login avatarUrl } } refName path language { name }
                snippets { lines startingLineNumber endingLineNumber jumpToLineNumber } }
      }
    }
    """
        .trimIndent()
