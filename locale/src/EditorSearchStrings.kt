package moe.yumeyuka.yumehub.locale

public data class EditorSearchStrings(
    val Placeholder: String,
    val Searching: String,
    val NoMatches: String,
    val MatchCount: (Int, Int) -> String,
    val Previous: String,
    val Next: String,
    val Close: String,
)

internal val ZhHansEditorSearchStrings: EditorSearchStrings =
    EditorSearchStrings(
        Placeholder = "查找关键字",
        Searching = "查找中",
        NoMatches = "无匹配",
        MatchCount = { current, total -> "$current / $total" },
        Previous = "上一处匹配",
        Next = "下一处匹配",
        Close = "关闭查找",
    )

internal val EnEditorSearchStrings: EditorSearchStrings =
    EditorSearchStrings(
        Placeholder = "Find in file",
        Searching = "Searching",
        NoMatches = "No matches",
        MatchCount = { current, total -> "$current / $total" },
        Previous = "Previous match",
        Next = "Next match",
        Close = "Close find",
    )
