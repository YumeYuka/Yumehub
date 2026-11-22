package moe.yumeyuka.yumehub.locale

/**
 * All copy for one language, grouped by screen like YumeBox's `YumeStrings`.
 *
 * Compose reads [LocalGithubStrings]; code outside composition reads [GithubTxt]. Each page or
 * feature group lives in its own `*Strings.kt` file and is assembled here.
 */
public data class GithubStrings(
    val Downloads: GithubStrings_Downloads,
    val Settings: GithubStrings_Settings,
    val Activity: GithubStrings_Activity,
    val Notifications: GithubStrings_Notifications,
    val WorkDetail: GithubStrings_WorkDetail,
    val WorkList: GithubStrings_WorkList,
    val Repository: GithubStrings_Repository,
    val Release: GithubStrings_Release,
    val Workspaces: GithubStrings_Workspaces,
    val Home: GithubStrings_Home,
    val Common: GithubStrings_Common,
    val Login: GithubStrings_Login,
    val TwoFactor: GithubStrings_TwoFactor,
    val Profile: GithubStrings_Profile,
    val EditorSearch: GithubStrings_EditorSearch,
    val Search: GithubStrings_Search,
    val EditorMenu: GithubStrings_EditorMenu,
    val MyRepositories: GithubStrings_MyRepositories,
    val Explore: GithubStrings_Explore,
    val Markdown: GithubStrings_Markdown,
    val LinkHandling: GithubStrings_LinkHandling,
)
