package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderGroup
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.ChevronLeft
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.toolbarIcon

/**
 * 外壳向页面注入的环境：底部留白、返回动作、标签切换与列表状态上报。 返回与页面内跳转分开授权：[backEnabled] 表示「返回键可用」，要求有返回、在栈顶且未处于提交锁；
 * [navigationEnabled] 只表示「页面自身发起的跳转可用」，要求仍在栈顶但不看提交锁——事件回调 往往晚于锁释放的那次重组，两者共用开关会把这次跳转静默丢掉。
 */
@Stable
class AppPageHost(
    val bottomPadding: Dp,
    backEnabled: Boolean,
    val onBack: () -> Unit,
    val onListState: (LazyListState) -> Unit,
    val shellActions: @Composable () -> Unit = {},
    val onLocked: (Boolean) -> Unit = {},
    val onOpenEditor: (EditorSession) -> Unit = {},
    private val isCurrentPage: () -> Boolean = { true },
    private val canNavigateBack: () -> Boolean = { true },
    private val canNavigate: () -> Boolean = { true },
    val onDispose: () -> Unit = {},
) {
    private val hasBack = backEnabled

    val isCurrent: Boolean
        get() = isCurrentPage()

    /** 返回按钮的显示状态只看返回入口与提交锁，不随栈顶切换灰化。 */
    val backButtonEnabled: Boolean
        get() = hasBack && canNavigateBack()

    /** 返回动作可用：有返回、在栈顶且未处于提交锁。 */
    val backEnabled: Boolean
        get() = isCurrent && backButtonEnabled

    /** 页面自身发起的跳转可用：只要求仍在栈顶，不受提交锁影响。仅用于动作校验，不用于控件灰化。 */
    val navigationEnabled: Boolean
        get() = canNavigate()
}

val LocalAppPageHost = staticCompositionLocalOf<AppPageHost?> { null }

/** 页面容器：标签根与详情页共用。返回放在左槽且只显示图标，`actions` 只放操作； 外壳通过 [AppPageHost] 注入底部留白、返回可用性与标签切换。 */
@Composable
fun AppPage(
    title: String,
    back: Boolean = true,
    showTitle: Boolean = true,
    titleLoading: Boolean = false,
    smallTitle: String? = null,
    titleLeading: (@Composable () -> Unit)? = null,
    titleSubtitle: String? = null,
    titleMaxLines: Int = 1,
    titleStyle: TextStyle? = null,
    titleSuffix: String? = null,
    titleEyebrow: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    titleFooter: (@Composable () -> Unit)? = null,
    titleOnClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState? = null,
    locked: Boolean = false,
    leading: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    headerActions: List<GlasmioHeaderAction>? = null,
    editorContent: (@Composable () -> Unit)? = null,
    editorTitle: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 12.dp,
    itemSpacing: Dp = 10.dp,
    backgroundColor: Color? = null,
    contentHeader: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val host = requireNotNull(LocalAppPageHost.current) { "AppPage 必须由 AppPageHost 提供" }
    val pageStateHolder = LocalAppPageState.current
    val fallbackState = rememberLazyListState()
    val pageState = listState ?: pageStateHolder?.listState ?: fallbackState
    // 在页面组合中订阅栈顶归属；切回常驻页面时即使列表未变，也重新上报滚动状态与锁。
    val isCurrent = host.isCurrent
    SideEffect {
        if (isCurrent) {
            host.onListState(pageState)
            host.onLocked(locked)
        }
    }
    DisposableEffect(host.onDispose) { onDispose { host.onDispose() } }

    val headerMotion = LocalHeaderMotion.current
    val headerEntryKey = LocalHeaderEntryKey.current
    val sharedBackdrop =
        if (headerMotion != null && headerEntryKey != null) {
            LocalPageBackdrop.current ?: rememberLayerBackdrop()
        } else {
            null
        }
    val latestLocked = rememberUpdatedState(locked)
    val latestOnBack = rememberUpdatedState(onBack)
    val latestHost = rememberUpdatedState(host)
    val backLabel = LocalAppStrings.current.Common.Back

    val leadingGroup =
        if (back && headerMotion != null && headerEntryKey != null) {
            val backEnabled = !locked && host.backButtonEnabled
            listOf(
                GlasmioHeaderGroup(
                    key = "navigation",
                    actions =
                        listOf(
                            toolbarIcon(
                                icon = Lucide.ChevronLeft,
                                label = backLabel,
                                enabled = backEnabled,
                                key = "navigation.back",
                            ) {
                                if (!latestLocked.value && latestHost.value.backEnabled) {
                                    latestOnBack.value?.invoke() ?: latestHost.value.onBack()
                                }
                            },
                        ),
                ),
            )
        } else emptyList()

    var collectedActions: List<GlasmioHeaderAction>? = null
    if (headerMotion != null && headerEntryKey != null && headerActions == null) {
        val sink: (List<GlasmioHeaderAction>) -> Unit = { collected ->
            collectedActions = collected
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalHeaderActionsSink provides sink,
        ) {
            Box(Modifier.size(0.dp)) {
                actions()
            }
        }
    }

    val resolvedTrailingActions = headerActions ?: collectedActions.orEmpty()
    val trailingGroups =
        if (
            headerMotion != null && headerEntryKey != null && resolvedTrailingActions.isNotEmpty()
        ) {
            listOf(GlasmioHeaderGroup(key = "primary", actions = resolvedTrailingActions))
        } else emptyList()

    if (headerMotion != null && headerEntryKey != null) {
        val pageColors = GlasmioTheme.colors
        val spec =
            NavigationHeaderSpec(
                leading = leadingGroup,
                trailing = trailingGroups,
                backdrop = sharedBackdrop,
                colors = pageColors,
            )
        SideEffect { headerMotion.updateSpec(headerEntryKey, spec) }
    }

    val navigation: @Composable () -> Unit = {
        if (back) {
            GlasmioIconButton(
                onClick = { if (!locked && host.backEnabled) onBack?.invoke() ?: host.onBack() },
                enabled = !locked && host.backButtonEnabled,
            ) {
                AppIcon(Lucide.ChevronLeft, contentDescription = backLabel)
            }
        } else {
            leading()
        }
    }
    val provideBackdrop = sharedBackdrop ?: LocalPageBackdrop.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPageBackdrop provides provideBackdrop,
    ) {
        if (editorContent != null) {
            EditorViewport(
                title = title,
                navigation = navigation,
                actions = actions,
                content = editorContent,
                titleContent = editorTitle,
            )
        } else {
            AppListPage(
                title = title,
                showTitle = showTitle,
                titleLoading = titleLoading,
                smallTitle = smallTitle,
                titleLeading = titleLeading,
                titleSubtitle = titleSubtitle,
                titleMaxLines = titleMaxLines,
                titleStyle = titleStyle,
                titleSuffix = titleSuffix,
                titleEyebrow = titleEyebrow,
                titleTrailing = titleTrailing,
                titleFooter = titleFooter,
                titleOnClick = titleOnClick,
                listState = pageState,
                topBarState = if (showTitle) pageStateHolder?.topBarState(pageState) else null,
                bottomPadding = host.bottomPadding,
                navigation = navigation,
                actions = {
                    host.shellActions()
                    if (headerMotion == null || headerEntryKey == null) {
                        actions()
                    }
                },
                horizontalPadding = horizontalPadding,
                itemSpacing = itemSpacing,
                backgroundColor = backgroundColor,
                contentHeader = contentHeader,
                bottomContent = bottomContent,
                content = content,
            )
        }
    }
}
