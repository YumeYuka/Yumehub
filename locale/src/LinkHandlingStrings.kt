package moe.yumeyuka.yumehub.locale

public data class LinkHandlingStrings(
    val Title: String,
    val OpenWithYumehub: String,
    val OfficialGithub: String,
    val SettingsOpenFailed: String,
)

internal val ZhHansLinkHandlingStrings: LinkHandlingStrings =
    LinkHandlingStrings(
        Title = "链接关联",
        OpenWithYumehub = "关联链接",
        OfficialGithub = "管理 GitHub 关联",
        SettingsOpenFailed = "无法打开系统设置",
    )

internal val EnLinkHandlingStrings: LinkHandlingStrings =
    LinkHandlingStrings(
        Title = "Link handling",
        OpenWithYumehub = "Associate links",
        OfficialGithub = "Manage GitHub associations",
        SettingsOpenFailed = "Unable to open system settings",
    )
