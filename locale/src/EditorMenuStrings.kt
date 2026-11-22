package moe.yumeyuka.yumehub.locale

public data class GithubStrings_EditorMenu(
    val More: String,
    val Expanded: String,
    val Collapsed: String,
    val WordWrap: String,
    val LineNumbers: String,
    val Undo: String,
    val Redo: String,
    val Language: String,
    val PlainText: String,
    val CurrentLanguage: String,
    val Enabled: String,
    val Disabled: String,
)

internal val ZhHansEditorMenuStrings: GithubStrings_EditorMenu =
    GithubStrings_EditorMenu(
        More = "更多",
        Expanded = "已展开",
        Collapsed = "已折叠",
        WordWrap = "换行",
        LineNumbers = "行号",
        Undo = "撤销",
        Redo = "重做",
        Language = "语言",
        PlainText = "纯文本",
        CurrentLanguage = "当前语言",
        Enabled = "开启",
        Disabled = "关闭",
    )

internal val EnEditorMenuStrings: GithubStrings_EditorMenu =
    GithubStrings_EditorMenu(
        More = "More",
        Expanded = "Expanded",
        Collapsed = "Collapsed",
        WordWrap = "Word wrap",
        LineNumbers = "Line numbers",
        Undo = "Undo",
        Redo = "Redo",
        Language = "Language",
        PlainText = "Plain text",
        CurrentLanguage = "Current language",
        Enabled = "On",
        Disabled = "Off",
    )
