package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.ChevronLeft
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.ui.component.GithubIcon

/**
 * 外壳向页面注入的环境：底部留白、返回动作、标签切换与列表状态上报。
 *
 * 返回与页面内跳转分开授权：[backEnabled] 表示「返回键可用」，要求有返回、在栈顶且未处于提交锁； [navigationEnabled]
 * 只表示「页面自身发起的跳转可用」，要求仍在栈顶但不看提交锁——事件回调 往往晚于锁释放的那次重组，两者共用开关会把这次跳转静默丢掉。
 */
@Stable
class GithubPageHost(
    val bottomPadding: Dp,
    backEnabled: Boolean,
    val onBack: () -> Unit,
    val onListState: (LazyListState) -> Unit,
    val shellActions: @Composable () -> Unit = {},
    val onLocked: (Boolean) -> Unit = {},
    val onOpenEditor: (GithubEditorSession) -> Unit = {},
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

val LocalGithubPageHost = staticCompositionLocalOf<GithubPageHost?> { null }

/** 页面容器：标签根与详情页共用。返回放在左槽且只显示图标，`actions` 只放操作； 外壳通过 [GithubPageHost] 注入底部留白、返回可用性与标签切换。 */
@Composable
fun GithubPage(
    title: String,
    back: Boolean = true,
    showTitle: Boolean = true,
    smallTitle: String? = null,
    titleLeading: (@Composable () -> Unit)? = null,
    titleSubtitle: String? = null,
    titleMaxLines: Int = 1,
    titleStyle: TextStyle? = null,
    titleSuffix: String? = null,
    titleEyebrow: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState? = null,
    locked: Boolean = false,
    leading: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    editorContent: (@Composable () -> Unit)? = null,
    editorTitle: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 12.dp,
    itemSpacing: Dp = 10.dp,
    backgroundColor: Color? = null,
    listTransition: GithubListTransition? = null,
    contentHeader: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    detailPresentation: GithubDetailPresentation? = null,
    onRetryDetail: () -> Unit = {},
    detailHeaderPlaceholder: @Composable () -> Unit = {},
    detailBottomPlaceholder: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val host = requireNotNull(LocalGithubPageHost.current) { "GithubPage 必须由 GithubPageHost 提供" }
    val pageStateHolder = LocalGithubPageState.current
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
    val navigation: @Composable () -> Unit = {
        if (back) {
            GlasmioIconButton(
                onClick = { if (!locked && host.backEnabled) onBack?.invoke() ?: host.onBack() },
                enabled = !locked && host.backButtonEnabled,
            ) {
                GithubIcon(Lucide.ChevronLeft, contentDescription = "返回")
            }
        } else {
            leading()
        }
    }
    if (editorContent != null) {
        GithubEditorViewport(
            title = title,
            navigation = navigation,
            actions = actions,
            content = editorContent,
            titleContent = editorTitle,
        )
    } else {
        GithubListPage(
            title = title,
            showTitle = showTitle,
            smallTitle = smallTitle,
            titleLeading = titleLeading,
            titleSubtitle = titleSubtitle,
            titleMaxLines = titleMaxLines,
            titleStyle = titleStyle,
            titleSuffix = titleSuffix,
            titleEyebrow = titleEyebrow,
            titleTrailing = titleTrailing,
            listState = pageState,
            topBarState = if (showTitle) pageStateHolder?.topBarState(pageState) else null,
            bottomPadding = host.bottomPadding,
            navigation = navigation,
            actions = {
                host.shellActions()
                actions()
            },
            content = content,
            horizontalPadding = horizontalPadding,
            itemSpacing = itemSpacing,
            backgroundColor = backgroundColor,
            listTransition = listTransition,
            contentHeader = contentHeader,
            bottomContent = bottomContent,
            detailPresentation = detailPresentation,
            onRetryDetail = onRetryDetail,
            detailHeaderPlaceholder = detailHeaderPlaceholder,
            detailBottomPlaceholder = detailBottomPlaceholder,
        )
    }
}
