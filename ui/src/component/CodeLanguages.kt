package moe.yumeyuka.yumehub.ui.component

public val CodeLanguages: List<String>
    get() = GithubGrammarIndex.languages

public fun codeLanguageForFilename(filename: String): String? =
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
        loadGrammarTable(byScope, byAlias, bySuffix, byFilename)
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
