package moe.yumeyuka.yumehub.locale

public data class SearchStrings(
    val Filter: String,
    val People: String,
    val Placeholder: (String) -> String,
)

internal val ZhHansSearchStrings: SearchStrings =
    SearchStrings(
        Filter = "筛选搜索结果",
        People = "用户",
        Placeholder = { "搜索$it" },
    )

internal val EnSearchStrings: SearchStrings =
    SearchStrings(
        Filter = "Filter search results",
        People = "People",
        Placeholder = { "Search ${it.lowercase()}" },
    )
