package moe.yumeyuka.yumehub.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioNavigationScrollState
import moe.yumeyuka.yumehub.feature.profile.ProfileRoute
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubHeaderEntryKey
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageBackdrop
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubSkeletonEntrance
import moe.yumeyuka.yumehub.ui.navigation.recordGithubSkeletonEntrance

/** 主标签同属主根路由，按标签保存列表偏移。 */
private class GithubMainPageStateViewModel : ViewModel() {
    val pageState = GithubPageState()
}

/**
 * 主根路由的全部内容：三个主标签的 Pager 和悬浮底栏。
 *
 * 返回时页号取自保留组件，底栏与当前页面一起转场。
 */
@Composable
internal fun GithubMainScreen(navigator: GithubNavigator) {
    val args = LocalGithubShellArgs.current
    val component = args.component
    val child = LocalGithubRouteChild.current
    val topTabs = args.topTabs
    val currentTab = component.mainTab
    // 绑定引用每次重组都是新对象，记住它才能让底栏跳过。
    val selectTab = remember(component) { component::selectTab }

    // 不走 saveable：页号以保留组件里的主标签为准，进程重建后不会与回到首页的标签状态打架。
    val pagerState =
        remember(component) {
            PagerState(
                currentPage = component.mainTab.ordinal,
                pageCount = { mainNavigationTabs.size },
            )
        }
    val mainPagerState = rememberGithubMainPagerState(pagerState)
    LaunchedEffect(currentTab) { mainPagerState.animateToPage(currentTab.ordinal) }
    LaunchedEffect(pagerState, mainPagerState, component) {
        snapshotFlow {
            Triple(
                pagerState.currentPage,
                pagerState.isScrollInProgress,
                mainPagerState.isNavigating,
            )
        }
            .collect {
                mainPagerState.syncPage()
                if (!mainPagerState.isNavigating) {
                    component.selectTab(mainNavigationTabs[pagerState.currentPage])
                }
            }
    }

    // 非首页主标签先回首页，首页交还系统默认返回。它先于各标签页注册，页面自己的拦截优先。
    GithubBackHandler(enabled = currentTab != GithubTab.Home) {
        component.selectTab(GithubTab.Home)
    }

    val fallbackListState = rememberLazyListState()
    val navigationScrollState =
        rememberGlasmioNavigationScrollState(
            listState = component.state.mainListStates[currentTab] ?: fallbackListState,
            enabled = !topTabs && !args.keepBottomBarExpanded,
        )
    val expandedProgress = remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            key = { mainNavigationTabs[it].name },
            userScrollEnabled = !component.state.locked,
            overscrollEffect = null,
            modifier =
                Modifier.fillMaxSize().nestedScroll(navigationScrollState.nestedScrollConnection),
        ) { index ->
            GithubMainTabPage(tab = mainNavigationTabs[index], navigator = navigator)
        }
        if (!topTabs) {
            GithubBottomBar(
                selectedTab = currentTab,
                onSelected = selectTab,
                backdrop = args.bottomBarBackdrop,
                onOpenProfile = {
                    if (child.isTop && !component.state.locked) navigator.push(ProfileRoute)
                },
                accounts = args.accounts,
                compactProgress =
                    if (args.keepBottomBarExpanded) expandedProgress
                    else navigationScrollState.compactProgress,
                scrollHintProgress =
                    if (args.keepBottomBarExpanded) expandedProgress
                    else navigationScrollState.scrollHintProgress,
                onExpand = navigationScrollState::expand,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 后台标签降低生命周期并关闭自己的返回 dispatcher；每个标签只订阅自己是否被选中。 */
@Composable
private fun GithubMainTabPage(tab: GithubTab, navigator: GithubNavigator) {
    val component = LocalGithubShellArgs.current.component
    val isActive = component.mainTab == tab
    val lifecycleOwner =
        rememberLifecycleOwner(
            maxLifecycle =
                if (isActive && LocalGithubRouteChild.current.isTop) Lifecycle.State.RESUMED
                else Lifecycle.State.CREATED,
        )
    val dispatcherOwner =
        rememberChildDispatcherOwner(
            parent = checkNotNull(LocalNavigationEventDispatcherOwner.current),
            enabled = isActive,
        )
    val pageBackdrop = LocalGithubShellArgs.current.pageBackdrops.getValue(tab)
    val state =
        viewModel<GithubMainPageStateViewModel>(key = "main-tab-${tab.name}") {
            GithubMainPageStateViewModel()
        }
    val entrance = state.pageState.skeletonEntrance
    val animate = isActive && LocalGithubCardMotionEnabled.current
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalNavigationEventDispatcherOwner provides dispatcherOwner,
        LocalGithubCardMotionEnabled provides animate,
        LocalGithubPageState provides state.pageState,
        LocalGithubSkeletonEntrance provides entrance,
        LocalGithubPageBackdrop provides pageBackdrop,
        LocalBlurBackdrop provides pageBackdrop,
        LocalGithubHeaderEntryKey provides tab,
    ) {
        Box(if (animate) Modifier.recordGithubSkeletonEntrance(entrance) else Modifier) {
            GithubRouteContent(route = tab.rootRoute(), navigator = navigator)
        }
    }
}
