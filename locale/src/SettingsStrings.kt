package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Settings(
    val TopBarBlur: String,
    val TopBarBlurDefault: String,
    val TopBarBlurReduceTransparencyOn: String,
)

internal val ZhHansSettingsStrings: GithubStrings_Settings =
    GithubStrings_Settings(
        TopBarBlur = "顶栏渐进模糊",
        TopBarBlurDefault = "默认关闭",
        TopBarBlurReduceTransparencyOn = "降低透明度已开启",
    )

internal val EnSettingsStrings: GithubStrings_Settings =
    GithubStrings_Settings(
        TopBarBlur = "Top bar progressive blur",
        TopBarBlurDefault = "Off by default",
        TopBarBlurReduceTransparencyOn = "Reduce transparency is on",
    )
