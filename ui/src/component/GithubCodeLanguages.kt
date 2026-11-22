package moe.yumeyuka.yumehub.ui.component

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public val GithubCodeLanguages: List<String>
    get() = GithubGrammarIndex.languages

public fun githubCodeLanguageForFilename(filename: String): String? =
    GithubGrammarIndex.forFile(filename)?.name

internal data class GithubGrammar(val name: String?, val scope: String, val path: String)

/** 一个索引供编辑器菜单、文件名、Markdown 代码围栏和嵌入语法共用，不在这里读取 grammar。 */
internal object GithubGrammarIndex {
    private val byScope = mutableMapOf<String, GithubGrammar>()
    private val byAlias = mutableMapOf<String, GithubGrammar>()
    private val bySuffix = mutableMapOf<String, GithubGrammar>()
    private val byFilename = mutableMapOf<String, GithubGrammar>()
    val grammars: Collection<GithubGrammar>
        get() = byScope.values

    val languages: List<String>

    init {
        val index = checkNotNull(javaClass.getResourceAsStream("/textmate/languages.json"))
        val entries =
            index.bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject.getValue("languages").jsonArray
            }
        for (entry in entries) {
            val obj = entry.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content
            val grammar =
                GithubGrammar(
                    name,
                    obj.getValue("scopeName").jsonPrimitive.content,
                    obj.getValue("grammar").jsonPrimitive.content,
                )
            byScope[grammar.scope] = grammar
            if (name == null) continue
            byAlias[name] = grammar
            for ((key, map) in
                listOf("aliases" to byAlias, "extensions" to bySuffix, "filenames" to byFilename)) {
                obj[key]?.jsonArray?.forEach {
                    map.putIfAbsent(it.jsonPrimitive.content.lowercase(), grammar)
                }
            }
        }
        languages = byScope.values.mapNotNull { it.name }.sorted() + "plaintext"
    }

    fun forFile(path: String): GithubGrammar? {
        val base = path.trim().substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase()
        byFilename[lower]?.let {
            return it
        }
        if (lower.startsWith("dockerfile.") || lower.startsWith("containerfile."))
            return byAlias["dockerfile"]
        if (base.endsWith(".C") || base.endsWith(".H")) return byAlias["cpp"]
        var dot = lower.indexOf('.')
        while (dot >= 0) {
            bySuffix[lower.substring(dot + 1)]?.let {
                return it
            }
            dot = lower.indexOf('.', dot + 1)
        }
        return null
    }

    fun forHint(hint: String?): GithubGrammar? {
        val value = hint?.trim() ?: return null
        val key = value.lowercase().removePrefix(".")
        return byScope[value] ?: byAlias[key] ?: bySuffix[key] ?: forFile(value)
    }

    fun forScope(scope: String): GithubGrammar? = byScope[scope]
}
