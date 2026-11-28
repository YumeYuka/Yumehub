package moe.yumeyuka.yumehub.locale

/**
 * All copy for one language, grouped by screen like YumeBox's `YumeStrings`.
 *
 * Compose reads [LocalAppStrings]; code outside composition reads [CurrentAppStrings]. Each page or
 * feature group lives in its own `*Strings.kt` file and is assembled here.
 */
public data class AppStrings(
    val Downloads: DownloadsStrings,
    val Settings: SettingsStrings,
    val Activity: ActivityStrings,
    val Notifications: NotificationsStrings,
    val WorkDetail: WorkDetailStrings,
    val WorkList: WorkListStrings,
    val Repository: RepositoryStrings,
    val Release: ReleaseStrings,
    val Workspaces: WorkspacesStrings,
    val Home: HomeStrings,
    val Common: CommonStrings,
    val Login: LoginStrings,
    val TwoFactor: TwoFactorStrings,
    val Profile: ProfileStrings,
    val EditorSearch: EditorSearchStrings,
    val Search: SearchStrings,
    val EditorMenu: EditorMenuStrings,
    val MyRepositories: MyRepositoriesStrings,
    val Explore: ExploreStrings,
    val Markdown: MarkdownStrings,
    val LinkHandling: LinkHandlingStrings,
)
