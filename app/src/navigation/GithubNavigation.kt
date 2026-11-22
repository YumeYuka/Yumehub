package moe.yumeyuka.yumehub.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.glass.GlasmioBackdropProvider
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.rememberSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioAdaptiveNavigationScaffold
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationBarWithTrailingItem
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationLayout
import io.github.neoruaa.glasmio.scaffold.glasmioNavigationLayout
import io.github.neoruaa.glasmio.scaffold.glasmioNavigationScrollHint
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioAdaptiveNavigationState
import io.github.neoruaa.glasmio.ui.CompactBottomControlSize
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import io.github.neoruaa.glasmio.ui.NavigationBarSurfaceHeight
import io.github.yumeyucca.lucide.*
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar
import moe.yumeyuka.yumehub.feature.profile.ProfileRoute
import moe.yumeyuka.yumehub.feature.profile.SettingsViewModel
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAccountAvatars
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute

internal enum class GithubTab(
    val title: String,
    val icon: ImageVector,
) {
    Home("首页", Lucide.House),
    Notifications("通知", Lucide.Inbox),
    Explore("探索", Lucide.Telescope),
}

internal val mainNavigationTabs = GithubTab.entries

/** MeiloX 底栏同款：62dp 主胶囊、20dp 外侧边距与 8dp 底部悬浮。 */
private val BottomBarHorizontalPadding = 20.dp
private val BottomBarBottomMargin = 8.dp

/**
 * List inset above the system navigation inset, as in MeiloX: capsule + its bottom margin.
 * GlasmioPinnedListPage adds its own 24dp gap on top of this.
 */
internal val NavigationBarClearance = NavigationBarSurfaceHeight + BottomBarBottomMargin

/**
 * 已登录外壳：只负责脚手架、玻璃采样源和路由环境。
 *
 * 外壳的组合体不读路由栈，也不持有底栏。底栏属于主根路由（见 [GithubMainScreen]），在该页控件层独立淡入淡出。
 */
@Composable
internal fun GithubAppShell(
    navigationComponent: GithubAppNavigationComponent,
    currentLogin: String,
    accounts: StateFlow<List<AccountAvatar>>,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val keepBottomBarExpanded by
        settingsViewModel.keepBottomBarExpanded.collectAsStateWithLifecycle()
    val predictiveBackPercent by
        settingsViewModel.predictiveBackMaxProgress.collectAsStateWithLifecycle()

    // The amplitude is applied per gesture, so the slider takes effect without a restart.
    LaunchedEffect(predictiveBackPercent) {
        navigationComponent.updatePredictiveBackPercent(predictiveBackPercent)
    }

    // MeiloX wiring: page content is recorded into its own layer and shared with the host's
    // background backdrop; the bar is a sibling so it never samples itself.
    val home = rememberLayerBackdrop()
    val notifications = rememberLayerBackdrop()
    val explore = rememberLayerBackdrop()
    val pageBackdrops =
        remember(home, notifications, explore) {
            mapOf(
                GithubTab.Home to home,
                GithubTab.Notifications to notifications,
                GithubTab.Explore to explore,
            )
        }
    var bottomBarBackdrop = LocalGlassBackdrop.current
    for (source in pageBackdrops.values) {
        bottomBarBackdrop = rememberSharedGlassBackdrop(bottomBarBackdrop, source)
    }
    val adaptiveNavigation = rememberGlasmioAdaptiveNavigationState()
    val navigationLayout = glasmioNavigationLayout()
    val topTabs = navigationLayout == GlasmioNavigationLayout.TopTabs
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pageReports = remember(navigationComponent) { GithubPageReports(navigationComponent.state) }
    val latestOnSignOut by rememberUpdatedState(onSignOut)
    val signOut: () -> Unit = remember { { latestOnSignOut() } }
    // 只在真正会变的入参变化时换对象；路由内容经 CompositionLocal 读取，谁读到变化谁重组。
    val shellArgs =
        remember(
            navigationComponent,
            pageReports,
            topTabs,
            navigationInset,
            currentLogin,
            accounts,
            onAddAccount,
            onSwitchAccount,
            keepBottomBarExpanded,
            pageBackdrops,
            bottomBarBackdrop,
        ) {
            GithubShellArgs(
                component = navigationComponent,
                pageReports = pageReports,
                topTabs = topTabs,
                navigationInset = navigationInset,
                currentLogin = currentLogin,
                accounts = accounts,
                onAddAccount = onAddAccount,
                onSwitchAccount = onSwitchAccount,
                keepBottomBarExpanded = keepBottomBarExpanded,
                pageBackdrops = pageBackdrops,
                bottomBarBackdrop = bottomBarBackdrop,
                onSignOut = signOut,
            )
        }

    GlasmioAdaptiveNavigationScaffold(
        state = adaptiveNavigation,
        layout = navigationLayout,
        sidebarEnabled = false,
        // 主页已提供内容采样，避免脚手架再录制包含底栏的整个外壳。
        contentBackdrop = home,
        modifier = modifier,
        sidebar = {},
    ) {
        CompositionLocalProvider(
            LocalBlurBackdrop provides bottomBarBackdrop,
            LocalGithubShellArgs provides shellArgs,
        ) {
            GithubNavContainer(
                component = navigationComponent,
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
        }
    }
}

@Composable
internal fun GithubBottomBar(
    selectedTab: GithubTab,
    onSelected: (GithubTab) -> Unit,
    backdrop: Backdrop,
    accounts: StateFlow<List<AccountAvatar>>,
    onOpenProfile: () -> Unit,
    compactProgress: State<Float>,
    scrollHintProgress: State<Float>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainTabItems = remember {
        mainNavigationTabs.map { tab ->
            GlasmioTabItem<GithubRoute>(
                key = tab.rootRoute(),
                label = tab.title,
                iconContent = { iconModifier, iconSize, tint ->
                    GithubIcon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        size = iconSize,
                        tint = tint,
                        modifier = iconModifier,
                    )
                },
            )
        }
    }
    val label = LocalGithubStrings.current.Profile.Title
    val profile =
        remember(label, accounts) {
            GlasmioTabItem<GithubRoute>(
                ProfileRoute,
                label = label,
                iconContent = { iconModifier, size, _ ->
                    val avatars by accounts.collectAsStateWithLifecycle()
                    GithubAccountAvatars(
                        avatars,
                        activeAvatarUrl = null,
                        size = size,
                        modifier = iconModifier,
                    )
                },
            )
        }
    Box(modifier) {
        GlasmioBackdropProvider(backdrop) {
            GlasmioNavigationBarWithTrailingItem(
                items = mainTabItems,
                trailingItem = profile,
                selectedKey = selectedTab.rootRoute(),
                onSelected = { route ->
                    if (route == ProfileRoute) onOpenProfile() else route.mainTab()?.let(onSelected)
                },
                onExpand = onExpand,
                compactProgress = compactProgress,
                compactSize = CompactBottomControlSize,
                backdrop = backdrop,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = BottomBarHorizontalPadding)
                        .padding(bottom = BottomBarBottomMargin)
                        .glasmioNavigationScrollHint(scrollHintProgress)
                        .navigationBarsPadding(),
            )
        }
    }
}
