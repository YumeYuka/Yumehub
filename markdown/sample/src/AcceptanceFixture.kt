package moe.yumeyuka.markdown.sample

import android.content.res.AssetManager
import org.json.JSONObject

/** 仓库夹具只有合成内容，资源解析固定在 APK assets 内。 */
data class AcceptanceFixture(
    val name: String,
    val html: String,
    val pageUrl: String,
    val repositoryRootUrl: String,
    val expectedVisibleText: List<String>,
    val expectedHiddenText: List<String>,
    val sourceFile: String,
    val referenceKind: String = "assets-reference",
    val engineCases: List<ReferenceEngineInput> = emptyList(),
    val geometryNodeIds: List<String> = emptyList(),
    val objectNodeIds: List<String> = emptyList(),
)

data class ReferenceEngineInput(
    val kind: String,
    val source: String,
    val display: Boolean = false,
)

object AcceptanceFixtures {
    fun load(assets: AssetManager, name: String): AcceptanceFixture {
        if (name == "long-document" || name == "long-paragraph") return longDocument(name)
        val index = JSONObject(assets.open("index.json").bufferedReader().use { it.readText() })
        val cases = index.getJSONArray("cases")
        val entry =
            (0 until cases.length())
                .map { cases.getJSONObject(it) }
                .firstOrNull { it.getString("name") == name } ?: error("Unknown fixture: $name")
        val file = entry.getString("file")
        require(!file.startsWith('/') && ".." !in file.split('/'))
        val html = assets.open(file).bufferedReader().use { it.readText() }
        fun strings(field: String): List<String> =
            entry.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList()
        return AcceptanceFixture(
            name = name,
            html = html,
            pageUrl = entry.optString("pageUrl", "https://fixture.invalid/html/fixture.html"),
            repositoryRootUrl = entry.optString("repositoryRootUrl", "https://fixture.invalid/"),
            expectedVisibleText = strings("expectedVisibleText"),
            expectedHiddenText = strings("expectedHiddenText"),
            sourceFile = file,
            referenceKind = entry.optString("referenceKind", "assets-reference"),
            geometryNodeIds = strings("geometryNodeIds"),
            objectNodeIds = strings("objectNodeIds"),
            engineCases =
                entry.optJSONArray("engineCases")?.let { engines ->
                    (0 until engines.length()).map { i ->
                        val engine = engines.getJSONObject(i)
                        ReferenceEngineInput(
                            engine.getString("kind"),
                            engine.getString("source"),
                            engine.optBoolean("display"),
                        )
                    }
                } ?: emptyList(),
        )
    }

    private fun longDocument(name: String): AcceptanceFixture {
        val html =
            if (name == "long-paragraph") {
                "<h1 id='long-start'>Long start</h1><p>" +
                    "原生长文选择 Native long paragraph. ".repeat(25_000) +
                    "</p><p id='long-end'>Long end</p>"
            } else {
                buildString {
                    append("<h1 id='long-start'>Long start</h1>")
                    repeat(6_000) { i ->
                        append("<p id='paragraph-$i'>Paragraph $i：")
                        append("原生长文选择 Native scrolling and selection. ".repeat(5))
                        append("</p>")
                    }
                    append("<p id='long-end'>Long end</p>")
                }
            }
        return AcceptanceFixture(
            name,
            html,
            "https://fixture.invalid/html/fixture.html",
            "https://fixture.invalid/",
            listOf("Long start", "Long end"),
            emptyList(),
            "generated:$name",
        )
    }
}
