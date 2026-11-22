package moe.yumeyuka.yumehub.navigation

import moe.yumeyuka.yumehub.ui.navigation.GithubRoute

/** 三个主页只在 MainTabsRoute 内左右切换，详情在外层栈打开。 */
internal data object HomeTabRoute : GithubRoute

internal data object NotificationsTabRoute : GithubRoute

internal data object ExploreTabRoute : GithubRoute

/** 根配置。Decompose 按配置复用子组件，账号会话每次更换 [session]，新会话才不会接手旧根的 ViewModel 与滚动状态。 */
internal data class MainTabsRoute(val session: Int) : GithubRoute

internal fun GithubTab.rootRoute(): GithubRoute =
    when (this) {
        GithubTab.Home -> HomeTabRoute
        GithubTab.Notifications -> NotificationsTabRoute
        GithubTab.Explore -> ExploreTabRoute
    }

/** 标签根所属的主标签；详情页返回 null。 */
internal fun GithubRoute.mainTab(): GithubTab? =
    when (this) {
        HomeTabRoute -> GithubTab.Home
        NotificationsTabRoute -> GithubTab.Notifications
        ExploreTabRoute -> GithubTab.Explore
        else -> null
    }
