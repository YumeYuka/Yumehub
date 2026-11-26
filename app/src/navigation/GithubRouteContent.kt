package moe.yumeyuka.yumehub.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.core.model.githubWebUri
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar
import moe.yumeyuka.yumehub.feature.actions.ActionsPage
import moe.yumeyuka.yumehub.feature.actions.ActionsRoute
import moe.yumeyuka.yumehub.feature.commitdetail.*
import moe.yumeyuka.yumehub.feature.discovery.DiscoveryPage
import moe.yumeyuka.yumehub.feature.discovery.DiscoveryRoute
import moe.yumeyuka.yumehub.feature.discussiondetail.*
import moe.yumeyuka.yumehub.feature.editor.EditorPage
import moe.yumeyuka.yumehub.feature.editor.EditorRoute
import moe.yumeyuka.yumehub.feature.explore.ExplorePage
import moe.yumeyuka.yumehub.feature.home.HomePage
import moe.yumeyuka.yumehub.feature.issuesearch.IssueSearchPage
import moe.yumeyuka.yumehub.feature.issuesearch.IssueSearchRoute
import moe.yumeyuka.yumehub.feature.notifications.NotificationsPage
import moe.yumeyuka.yumehub.feature.profile.FollowingPage
import moe.yumeyuka.yumehub.feature.profile.FollowingRoute
import moe.yumeyuka.yumehub.feature.profile.ProfilePage
import moe.yumeyuka.yumehub.feature.profile.ProfileRoute
import moe.yumeyuka.yumehub.feature.profile.UserProfilePage
import moe.yumeyuka.yumehub.feature.profile.UserProfileRoute
import moe.yumeyuka.yumehub.feature.profilecollections.ProfileCollectionsPage
import moe.yumeyuka.yumehub.feature.profilecollections.ProfileCollectionsRoute
import moe.yumeyuka.yumehub.feature.pullrequestcommits.PullRequestCommitsPage
import moe.yumeyuka.yumehub.feature.pullrequestcommits.PullRequestCommitsRoute
import moe.yumeyuka.yumehub.feature.pullrequestdiff.PullRequestDiffPage
import moe.yumeyuka.yumehub.feature.pullrequestdiff.PullRequestDiffRoute
import moe.yumeyuka.yumehub.feature.releases.ReleasesPage
import moe.yumeyuka.yumehub.feature.releases.ReleasesRoute
import moe.yumeyuka.yumehub.feature.repositories.MyRepositoriesPage
import moe.yumeyuka.yumehub.feature.repositories.MyRepositoriesRoute
import moe.yumeyuka.yumehub.feature.repository.RepositoryPage
import moe.yumeyuka.yumehub.feature.repository.RepositoryRoute
import moe.yumeyuka.yumehub.feature.repositoryactivity.*
import moe.yumeyuka.yumehub.feature.search.ExploreSearchPage
import moe.yumeyuka.yumehub.feature.search.ExploreSearchRoute
import moe.yumeyuka.yumehub.feature.settings.SettingsPage
import moe.yumeyuka.yumehub.feature.settings.SettingsRoute
import moe.yumeyuka.yumehub.feature.starred.StarredPage
import moe.yumeyuka.yumehub.feature.starred.StarredRoute
import moe.yumeyuka.yumehub.feature.workdetail.WorkDetailPage
import moe.yumeyuka.yumehub.feature.workdetail.WorkDetailRoute
import moe.yumeyuka.yumehub.feature.worklist.WorkListPage
import moe.yumeyuka.yumehub.feature.worklist.WorkListRoute
import moe.yumeyuka.yumehub.feature.workspaces.CommitHistoryPage
import moe.yumeyuka.yumehub.feature.workspaces.CommitHistoryRoute
import moe.yumeyuka.yumehub.feature.workspaces.WorkspacesPage
import moe.yumeyuka.yumehub.feature.workspaces.WorkspacesRoute
import moe.yumeyuka.yumehub.ui.navigation.GithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.LocalGithubLinkHandler
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler

/**
 * 外壳提供给全部路由的环境。
 *
 * 外壳按会变的入参整体记住这个对象，路由内容经 [LocalGithubShellArgs] 读取。栈宿主的内容 lambda 因此不必捕获外壳 状态，外壳重组也不会波及栈内页面。
 */
@Immutable
internal class GithubShellArgs(
    val component: GithubAppNavigationComponent,
    val pageReports: GithubPageReports,
    val topTabs: Boolean,
    /** 系统导航栏 inset；标签根另加底栏留白。 */
    val navigationInset: Dp,
    val currentLogin: String,
    val accounts: StateFlow<List<AccountAvatar>>,
    val onAddAccount: () -> Unit,
    val onSwitchAccount: (String) -> Unit,
    val keepBottomBarExpanded: Boolean,
    val pageBackdrops: Map<GithubTab, LayerBackdrop>,
    val bottomBarBackdrop: Backdrop,
    val onSignOut: () -> Unit,
)

internal val LocalGithubShellArgs =
    compositionLocalOf<GithubShellArgs> { error("LocalGithubShellArgs 未提供") }

/** 锁按 owner 集合归属：多个页面同时上报时按集合合并，只有全部释放才解锁； 旧页面退出或上报 false 时只能释放自己那一条，不会误清别的页面的锁。 */
internal class GithubPageReports(private val state: GithubNavigationState) {
    private val lockedOwners = mutableSetOf<Any>()

    fun setLocked(owner: Any, locked: Boolean) {
        if (locked) lockedOwners.add(owner) else lockedOwners.remove(owner)
        state.locked = lockedOwners.isNotEmpty()
    }

    fun release(owner: Any) {
        if (lockedOwners.remove(owner)) state.locked = lockedOwners.isNotEmpty()
    }
}

/**
 * 路由表：只负责把路由键映射到 feature 的整页组件，并向页面注入 [GithubPageHost]。
 *
 * 外壳环境经 [LocalGithubShellArgs] 读取，所属子组件经 [LocalGithubRouteChild] 读取；页面自己持有 `GithubPage`、标题
 * 与操作，这里不重复顶栏、列表状态与返回逻辑。
 */
@Composable
internal fun GithubRouteContent(route: GithubRoute, navigator: GithubNavigator) {
    val args = LocalGithubShellArgs.current
    val openInBrowser = rememberExternalUriHandler()
    val latestArgs by rememberUpdatedState(args)
    val child = LocalGithubRouteChild.current
    val tab = route.mainTab()
    val pageOwner = remember(child, route, navigator) { Any() }
    val releasePage =
        remember(args.pageReports, pageOwner) { { args.pageReports.release(pageOwner) } }
    // 归属按子组件身份实时判断：退场中的旧页已不在栈顶，相同 route 的新页不会被它误认。
    // 标签根还要求自己是当前主标签。两处读取都是可订阅状态，页面重回栈顶时会重新上报。
    val isCurrent: () -> Boolean =
        remember(child, tab) {
            { child.isTop && (tab == null || latestArgs.component.mainTab == tab) }
        }
    // 返回按钮只随提交锁禁用，栈顶校验由 host.backEnabled 在动作执行时补上，避免退场灰化。
    // 页面自身发起的跳转只要求仍在栈顶，避免事件回调早于锁释放的重组时被丢弃。
    val canNavigateBack: () -> Boolean = remember { { !latestArgs.component.state.locked } }
    val canNavigate: () -> Boolean = remember(isCurrent) { { isCurrent() } }
    val openLink: (String) -> Unit =
        remember(child, navigator, openInBrowser) {
            { url ->
                if (canNavigate()) {
                    if (!latestArgs.component.openLink(url)) {
                        openInBrowser(githubWebUri(url)?.toASCIIString() ?: url)
                    }
                }
            }
        }
    val openRoute: (GithubRoute) -> Unit = { target -> if (canNavigate()) navigator.push(target) }
    val openRepository: (RepositoryDto) -> Unit = { repository ->
        repository.toRoute()?.let(openRoute)
    }
    val openProfileRepositories: (String, Boolean) -> Unit = { login, organization ->
        openRoute(
            ProfileCollectionsRoute(
                login,
                ProfileCollectionsRoute.Kind.Repositories,
                organization,
            ),
        )
    }
    val openProfileStarred: (String) -> Unit = { login ->
        openRoute(ProfileCollectionsRoute(login, ProfileCollectionsRoute.Kind.Starred))
    }
    val openProfileOrganizations: (String) -> Unit = { login ->
        openRoute(ProfileCollectionsRoute(login, ProfileCollectionsRoute.Kind.Organizations))
    }
    val openWork: (WorkTarget) -> Unit = { target ->
        openRoute(WorkDetailRoute(target.owner, target.repository, target.kind, target.number))
    }
    // 只有标签根且底栏可见时为悬浮底栏留白；详情与 topTabs 根仅保留系统导航栏 inset。
    val bottomPadding =
        args.navigationInset + if (tab != null && !args.topTabs) NavigationBarClearance else 0.dp
    // host 只能按真正会变的入参重建，否则 CompositionLocal 每次都换人。
    val host =
        remember(child, route, navigator, bottomPadding, args.pageReports, pageOwner) {
            GithubPageHost(
                bottomPadding = bottomPadding,
                backEnabled = tab == null,
                isCurrentPage = isCurrent,
                canNavigateBack = canNavigateBack,
                canNavigate = canNavigate,
                onBack = { if (canNavigate()) navigator.pop() },
                onListState = { listState ->
                    // 底栏只跟随主页列表，详情列表不上报。
                    if (tab != null && isCurrent()) {
                        latestArgs.component.state.mainListStates[tab] = listState
                    }
                },
                shellActions = {
                    if (tab != null) {
                        PageTabSwitcher(
                            latestArgs.topTabs,
                            latestArgs.component.mainTab,
                            { target -> if (canNavigate()) latestArgs.component.selectTab(target) },
                        )
                    }
                },
                onOpenEditor = { session ->
                    if (canNavigate()) navigator.push(EditorRoute(session))
                },
                onLocked = { locked ->
                    if (isCurrent()) latestArgs.pageReports.setLocked(pageOwner, locked)
                },
                onDispose = releasePage,
            )
        }
    CompositionLocalProvider(
        LocalGithubPageHost provides host,
        LocalGithubLinkHandler provides openLink,
    ) {
        when (route) {
            HomeTabRoute ->
                HomePage(
                    login = args.currentLogin,
                    onOpenProfile = if (args.topTabs) ({ openRoute(ProfileRoute) }) else null,
                    onOpenSearch = { openRoute(ExploreSearchRoute) },
                    onOpenProjects = {
                        openRoute(
                            RepositoryActivityRoute(
                                args.currentLogin,
                                "",
                                RepositoryActivityKind.PersonalProjects,
                            ),
                        )
                    },
                    onOpenDiscussions = {
                        openRoute(
                            RepositoryActivityRoute(
                                args.currentLogin,
                                "",
                                RepositoryActivityKind.PersonalDiscussions,
                            ),
                        )
                    },
                    onOpenRepositories = { openRoute(MyRepositoriesRoute) },
                    onOpenWork = { kind -> openRoute(IssueSearchRoute(kind)) },
                    onOpenStarred = { openRoute(StarredRoute) },
                    onOpenRepository = openRepository,
                    onOpenOrganizations = { openProfileOrganizations(args.currentLogin) },
                )
            NotificationsTabRoute ->
                NotificationsPage(
                    showSelectionActions = args.topTabs,
                    onOpenWork = { owner, name, kind, number ->
                        openRoute(WorkDetailRoute(owner, name, kind, number))
                    },
                )
            ExploreTabRoute ->
                ExplorePage(
                    onOpenSearch = { openRoute(ExploreSearchRoute) },
                    onOpenTrending = { openRoute(DiscoveryRoute.Trending) },
                    onOpenFeatured = { openRoute(DiscoveryRoute.Featured) },
                    onOpenRepository = openRepository,
                    onOpenWork = openWork,
                    onOpenUser = { user ->
                        user.login
                            ?.takeIf { it.isNotBlank() }
                            ?.let { login ->
                                openRoute(
                                    UserProfileRoute(
                                        login = login,
                                        avatarUrl = user.avatarUrl,
                                        name = user.name,
                                        organization = user.type == "Organization",
                                    ),
                                )
                            }
                    },
                )
            is DiscoveryRoute -> DiscoveryPage(route, args.currentLogin, openRepository)
            ProfileRoute ->
                ProfilePage(
                    accounts = args.accounts.collectAsStateWithLifecycle().value,
                    onAddAccount = {
                        if (canNavigate() && canNavigateBack()) latestArgs.onAddAccount()
                    },
                    onSwitchAccount = { login ->
                        if (canNavigate() && canNavigateBack()) latestArgs.onSwitchAccount(login)
                    },
                    onOpenSettings = { openRoute(SettingsRoute) },
                    onOpenFollowing = { openRoute(FollowingRoute(args.currentLogin)) },
                    onOpenRepositories = openProfileRepositories,
                    onOpenStarred = openProfileStarred,
                    onOpenOrganizations = openProfileOrganizations,
                )
            is ExploreSearchRoute ->
                ExploreSearchPage(
                    onOpenRepository = openRepository,
                    onOpenWork = openWork,
                    onOpenUser = { user ->
                        user.login?.let {
                            openRoute(
                                UserProfileRoute(
                                    it,
                                    user.avatarUrl,
                                    user.name,
                                    organization = user.type == "Organization",
                                ),
                            )
                        }
                    },
                    onOpenFile = { file, line ->
                        openRoute(
                            WorkspacesRoute(
                                file.owner,
                                file.repository,
                                file.ref,
                                file.path,
                                true,
                                line?.let { "L$it" },
                            ),
                        )
                    },
                )
            is EditorRoute -> EditorPage(route.session)
            is FollowingRoute ->
                FollowingPage(
                    route.login,
                    initialFollowing = route.initialFollowing,
                    onOpenUser = { login, avatarUrl, name, nodeId ->
                        openRoute(UserProfileRoute(login, avatarUrl, name, nodeId))
                    },
                )
            is UserProfileRoute ->
                UserProfilePage(
                    login = route.login,
                    avatarUrl = route.avatarUrl,
                    name = route.name,
                    nodeId = route.nodeId,
                    organization = route.organization,
                    onOpenFollowing = { openRoute(FollowingRoute(it)) },
                    onOpenRepositories = openProfileRepositories,
                    onOpenStarred = openProfileStarred,
                    onOpenOrganizations = openProfileOrganizations,
                )
            is ProfileCollectionsRoute ->
                ProfileCollectionsPage(
                    route = route,
                    onOpenRepository = openRepository,
                    onOpenOrganization = { login, avatarUrl, name ->
                        openRoute(UserProfileRoute(login, avatarUrl, name, organization = true))
                    },
                )
            SettingsRoute -> SettingsPage(onSignOut = { if (canNavigate()) latestArgs.onSignOut() })
            MyRepositoriesRoute ->
                MyRepositoriesPage(
                    login = args.currentLogin,
                    onOpenRepository = openRepository,
                )
            StarredRoute ->
                StarredPage(
                    login = args.currentLogin,
                    onOpenRepository = openRepository,
                )
            is IssueSearchRoute ->
                IssueSearchPage(
                    login = args.currentLogin,
                    kind = route.kind,
                    onOpenWork = openWork,
                )
            is RepositoryRoute ->
                RepositoryPage(
                    owner = route.owner,
                    name = route.name,
                    onOpenWorkList = { owner, name, kind, createInitially ->
                        openRoute(WorkListRoute(owner, name, kind, createInitially))
                    },
                    onCreateDiscussion = { owner, name ->
                        openRoute(
                            RepositoryActivityRoute(
                                owner,
                                name,
                                RepositoryActivityKind.Discussions,
                                createInitially = true,
                            ),
                        )
                    },
                    onOpenLicense = { owner, name ->
                        openRoute(WorkspacesRoute(owner, name, license = true))
                    },
                    onOpenWorkspaces = { owner, name, ref, path, file, anchor ->
                        openRoute(WorkspacesRoute(owner, name, ref, path, file, anchor))
                    },
                    onOpenRepository = { owner, name -> openRoute(RepositoryRoute(owner, name)) },
                    onExit = host.onBack,
                    readme = route.readme,
                )
            is WorkspacesRoute ->
                WorkspacesPage(
                    route = route,
                    onOpenCommitHistory = { ref ->
                        openRoute(CommitHistoryRoute(route.owner, route.name, ref))
                    },
                )
            is RepositoryActivityRoute ->
                RepositoryActivityPage(
                    route = route,
                    account = args.currentLogin,
                    onOpenDiscussion = { owner, name, number ->
                        openRoute(DiscussionDetailRoute(owner, name, number))
                    },
                )
            is ActionsRoute ->
                ActionsPage(
                    route,
                    args.currentLogin,
                    open = openRoute,
                )
            is PullRequestDiffRoute -> PullRequestDiffPage(route, args.currentLogin)
            is PullRequestCommitsRoute ->
                PullRequestCommitsPage(route, args.currentLogin) { oid ->
                    openRoute(CommitDetailRoute(route.owner, route.name, oid))
                }
            is CommitDetailRoute -> CommitDetailPage(route, args.currentLogin)
            is ReleasesRoute -> ReleasesPage(route, args.currentLogin)
            is DiscussionDetailRoute ->
                DiscussionDetailPage(route, args.currentLogin) { id ->
                    openRoute(route.copy(parentId = id))
                }
            is CommitHistoryRoute ->
                CommitHistoryPage(owner = route.owner, name = route.name, ref = route.ref)
            is WorkListRoute ->
                WorkListPage(
                    owner = route.owner,
                    name = route.name,
                    kind = route.kind,
                    login = args.currentLogin,
                    createInitially = route.createInitially,
                    onOpenDetail = { number, title ->
                        openRoute(
                            WorkDetailRoute(route.owner, route.name, route.kind, number, title),
                        )
                    },
                )
            is WorkDetailRoute ->
                WorkDetailPage(
                    owner = route.owner,
                    name = route.name,
                    kind = route.kind,
                    number = route.number,
                    title = route.title,
                    login = args.currentLogin,
                    onOpenFiles = {
                        openRoute(PullRequestDiffRoute(route.owner, route.name, route.number))
                    },
                    onOpenCommits = {
                        openRoute(PullRequestCommitsRoute(route.owner, route.name, route.number))
                    },
                    onOpenCommitGroup = { commits ->
                        openRoute(
                            PullRequestCommitsRoute(
                                route.owner,
                                route.name,
                                route.number,
                                activityCommits = commits,
                            ),
                        )
                    },
                    onOpenCommit = { oid ->
                        openRoute(CommitDetailRoute(route.owner, route.name, oid))
                    },
                    onOpenWork = { owner, name, kind, number ->
                        openRoute(WorkDetailRoute(owner, name, kind, number))
                    },
                )
            else -> Unit
        }
    }
}

/** 顶栏标签切换：只在标签根出现，切换标签不再清空各自的返回栈。 */
@Composable
private fun PageTabSwitcher(
    topTabs: Boolean,
    currentTab: GithubTab,
    onSelectTab: (GithubTab) -> Unit,
) {
    if (topTabs) {
        GlasmioSegmentedControl(
            items = mainNavigationTabs.map { it to it.title },
            selected = currentTab,
            onSelected = onSelectTab,
        )
    }
}

private fun RepositoryDto.toRoute(): RepositoryRoute? {
    val fullName = fullName?.split('/', limit = 3)
    val owner = owner?.login ?: fullName?.getOrNull(0)
    val name = fullName?.getOrNull(1)
    val resolvedName = this.name ?: name
    return if (!owner.isNullOrBlank() && !resolvedName.isNullOrBlank()) {
        RepositoryRoute(owner, resolvedName)
    } else {
        null
    }
}
