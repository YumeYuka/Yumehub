package moe.yumeyuka.yumehub.navigation

import moe.yumeyuka.yumehub.ui.navigation.AppRoute

/** 三个主页只在 MainTabsRoute 内左右切换，详情在外层栈打开。 */
internal data object HomeTabRoute : AppRoute

internal data object NotificationsTabRoute : AppRoute

internal data object ExploreTabRoute : AppRoute

/** 根配置。Decompose 按配置复用子组件，账号会话每次更换 [session]，新会话才不会接手旧根的 ViewModel 与滚动状态。 */
internal data class MainTabsRoute(val session: Int) : AppRoute

internal fun MainTab.rootRoute(): AppRoute =
    when (this) {
        MainTab.Home -> HomeTabRoute
        MainTab.Notifications -> NotificationsTabRoute
        MainTab.Explore -> ExploreTabRoute
    }

/** 标签根所属的主标签；详情页返回 null。 */
internal fun AppRoute.mainTab(): MainTab? =
    when (this) {
        HomeTabRoute -> MainTab.Home
        NotificationsTabRoute -> MainTab.Notifications
        ExploreTabRoute -> MainTab.Explore
        else -> null
    }
