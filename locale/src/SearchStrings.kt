package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Search(
    val Filter: String,
    val People: String,
    val Placeholder: (String) -> String,
)

internal val ZhHansSearchStrings: GithubStrings_Search =
    GithubStrings_Search(
        Filter = "筛选搜索结果",
        People = "用户",
        Placeholder = { "搜索$it" },
    )

internal val EnSearchStrings: GithubStrings_Search =
    GithubStrings_Search(
        Filter = "Filter search results",
        People = "People",
        Placeholder = { "Search ${it.lowercase()}" },
    )
