package moe.yumeyuka.yumehub.locale

public data class GithubStrings_LinkHandling(
    val Title: String,
    val OpenWithYumehub: String,
    val OfficialGithub: String,
    val SettingsOpenFailed: String,
)

internal val ZhHansLinkHandlingStrings: GithubStrings_LinkHandling =
    GithubStrings_LinkHandling(
        Title = "链接关联",
        OpenWithYumehub = "关联链接",
        OfficialGithub = "管理 GitHub 关联",
        SettingsOpenFailed = "无法打开系统设置",
    )

internal val EnLinkHandlingStrings: GithubStrings_LinkHandling =
    GithubStrings_LinkHandling(
        Title = "Link handling",
        OpenWithYumehub = "Associate links",
        OfficialGithub = "Manage GitHub associations",
        SettingsOpenFailed = "Unable to open system settings",
    )
