/*
 * Layout adapted from Glasmio 0.1.2:
 * IosPinnedListPage.kt, GlasmioNavigationScaffold.kt and PageOverscrollHost.kt.
 * Apache-2.0; see third_party/glasmio/LICENSE.
 * Modified to use Miuix's native overscroll before the app-bar scroll behavior.
 */
package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.scaffold.glasmioAdaptiveContent
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.LocalGithubMarkdownRenderer
import moe.yumeyuka.yumehub.ui.component.LocalGithubMarkdownSelectionHosted
import moe.yumeyuka.yumehub.ui.component.LocalMarkdownCardRetention
import moe.yumeyuka.yumehub.ui.component.MarkdownCardRetention
import moe.yumeyuka.yumehub.ui.component.githubBackdropSource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.utils.overScrollVertical

data class GithubPagingRevalidationBinding(
    val state: StateFlow<RevalidationState>,
    val onRetry: () -> Unit,
)

val LocalGithubPagingRevalidation =
    staticCompositionLocalOf<GithubPagingRevalidationBinding?> { null }

/** 页面内容统一外边距；分组行仍保留 Glasmio 的 16dp 内部对齐。 */
private val PageHorizontalPadding = 12.dp

/** 主标签将正文采样同时提供给本页顶栏和主根底栏；详情使用自己的源。 */
val LocalGithubPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal fun githubListContentPadding(
    topBarPadding: PaddingValues,
    horizontalPadding: Dp,
    bottomPadding: Dp,
): PaddingValues =
    object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = horizontalPadding

        override fun calculateTopPadding(): Dp = topBarPadding.calculateTopPadding() + 12.dp

        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = horizontalPadding

        override fun calculateBottomPadding(): Dp = bottomPadding + 24.dp
    }

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun GithubListPage(
    title: String,
    listState: LazyListState,
    bottomPadding: Dp,
    topBarState: TopAppBarState? = null,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
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
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    horizontalPadding: Dp = PageHorizontalPadding,
    itemSpacing: Dp = 10.dp,
    backgroundColor: Color? = null,
    contentHeader: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val active = LocalGithubPageHost.current?.isCurrent != false
    val routeState = LocalGithubPageState.current
    val section = remember(routeState, listState) { routeState?.contentStateKey(listState) }
    val sections = rememberSaveableStateHolder()
    val savedSections = rememberSaveable { arrayListOf<String>() }
    DisposableEffect(section) {
        if (section != null) {
            savedSections.remove(section)
            savedSections.add(section)
            if (savedSections.size > MaxKeyedListStates)
                sections.removeState(savedSections.removeAt(0))
        }
        onDispose {}
    }
    val scrollBehavior =
        key(showTitle) {
            if (showTitle) {
                // 顶栏偏移按页面保留：页面被覆盖后回来，大标题不会在正文已滚动时重新展开。
                MiuixScrollBehavior(state = topBarState ?: rememberTopAppBarState())
            } else {
                val state =
                    rememberTopAppBarState(
                        initialHeightOffsetLimit = -Float.MAX_VALUE,
                        initialHeightOffset = -Float.MAX_VALUE,
                    )
                MiuixScrollBehavior(state = state, canScroll = { false })
            }
        }
    val colors = GlasmioTheme.colors
    val markdownRenderer = LocalGithubMarkdownRenderer.current
    val pageBackground = backgroundColor ?: colors.groupedBackground
    val backdrop = LocalGithubPageBackdrop.current ?: rememberLayerBackdrop()
    var bottomContentHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomClearance =
        if (bottomContent == null) 0.dp else with(density) { bottomContentHeight.toDp() }
    val barState = scrollBehavior.state
    val onOverscrollOffsetChanged: (Float) -> Unit =
        remember(barState) { { barState.overscrollOffset = it } }
    // 短列表可先折叠顶栏而不移动首项；此时仍需要顶栏遮罩。
    val isContentScrolled by
        remember(listState, barState) {
            derivedStateOf {
                listState.canScrollBackward ||
                    barState.heightOffset < 0f ||
                    barState.overscrollOffset < -1f
            }
        }
    CompositionLocalProvider(
        LocalContentColor provides colors.content,
        LocalMarkdownCardRetention provides remember(listState) { MarkdownCardRetention() },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = pageBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            popupHost = {},
            topBar = {
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    GithubTopBar(
                        title = smallTitle ?: if (showTitle) title else "",
                        largeTitle = if (showTitle) title else "",
                        largeTitleLeading = titleLeading,
                        largeTitleSubtitle = titleSubtitle,
                        largeTitleMaxLines = titleMaxLines,
                        largeTitleStyle = titleStyle,
                        largeTitleSuffix = if (showTitle) titleSuffix else null,
                        largeTitleEyebrow = titleEyebrow,
                        largeTitleTrailing = titleTrailing,
                        largeTitleFooter = titleFooter,
                        largeTitleOnClick = titleOnClick,
                        backgroundColor = pageBackground,
                        scrollBehavior = scrollBehavior,
                        isContentScrolled = isContentScrolled,
                        navigation = navigation,
                        actions = actions,
                        modifier = Modifier.fillMaxWidth(),
                        contentModifier =
                            if (showTitle) Modifier
                            else
                                Modifier.onSizeChanged {
                                    scrollBehavior.state.heightOffset =
                                        scrollBehavior.state.heightOffsetLimit
                                },
                    )
                }
            },
        ) { topBarPadding ->
            val padding =
                remember(topBarPadding, horizontalPadding, bottomPadding, bottomClearance) {
                    githubListContentPadding(
                        topBarPadding,
                        horizontalPadding,
                        bottomPadding + bottomClearance,
                    )
                }
            Box(Modifier.fillMaxSize()) {
                GithubScrollingListHeader(
                    listState = listState,
                    horizontalPadding = horizontalPadding,
                    header = contentHeader,
                    modifier =
                        Modifier.fillMaxSize()
                            .githubBackdropSource(backdrop)
                            .trackBackdropPosition(backdrop)
                            .background(pageBackground)
                            .glasmioAdaptiveContent()
                            .then(
                                if (contentHeader != null)
                                    Modifier.overScrollVertical(
                                            onOffsetChanged = onOverscrollOffsetChanged,
                                        )
                                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                                else Modifier,
                            ),
                ) { headerHeight ->
                    val body: @Composable () -> Unit = {
                        val drawList: @Composable (Modifier, LazyListScope.() -> Unit) -> Unit =
                            { listModifier, rows ->
                                val binding = LocalGithubPagingRevalidation.current
                                val syncState = binding?.state?.collectAsStateWithLifecycle()?.value
                                LazyColumn(
                                    state = listState,
                                    modifier =
                                        listModifier
                                            .fillMaxSize()
                                            .then(
                                                if (contentHeader == null)
                                                    Modifier.overScrollVertical(
                                                            onOffsetChanged =
                                                                onOverscrollOffsetChanged,
                                                        )
                                                        .nestedScroll(
                                                            scrollBehavior.nestedScrollConnection,
                                                        )
                                                else Modifier,
                                            ),
                                    contentPadding = padding,
                                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                                    userScrollEnabled = active,
                                    overscrollEffect = null,
                                ) {
                                    if (contentHeader != null) {
                                        item(
                                            key = GithubScrollingHeaderKey,
                                            contentType = GithubScrollingHeaderKey,
                                        ) {
                                            Spacer(Modifier.fillMaxWidth().height(headerHeight))
                                        }
                                    }
                                    if (syncState is RevalidationState.Failed && binding != null) {
                                        item(key = "paging-cache-sync-failed") {
                                            val common = LocalGithubStrings.current.Common
                                            GlasmioGroupedList {
                                                GlasmioListRow(
                                                    title = common.CacheSyncFailed,
                                                    detail = common.Retry,
                                                    showDisclosure = false,
                                                    onClick = binding.onRetry,
                                                )
                                            }
                                        }
                                    }
                                    rows()
                                }
                            }
                        if (markdownRenderer?.supportsHostedSelection == true) {
                            val upstream = LocalBringIntoViewSpec.current
                            val currentPadding = rememberUpdatedState(padding)
                            val currentDensity = rememberUpdatedState(LocalDensity.current)
                            val readingSpec =
                                remember(upstream) {
                                    GithubReadingBringIntoViewSpec(
                                        upstream = upstream,
                                        topPaddingPx = {
                                            with(currentDensity.value) {
                                                currentPadding.value.calculateTopPadding().toPx()
                                            }
                                        },
                                        bottomPaddingPx = {
                                            with(currentDensity.value) {
                                                currentPadding.value.calculateBottomPadding().toPx()
                                            }
                                        },
                                    )
                                }
                            CompositionLocalProvider(
                                LocalGithubMarkdownSelectionHosted provides true,
                                LocalBringIntoViewSpec provides readingSpec,
                            ) {
                                markdownRenderer.SelectionHost(
                                    active = active,
                                    modifier = Modifier.fillMaxSize(),
                                ) { child ->
                                    drawList(child) {
                                        content(ReadingLazyListScope(this, upstream))
                                    }
                                }
                            }
                        } else drawList(Modifier, content)
                    }
                    if (section != null) sections.SaveableStateProvider(section, body)
                    else key(listState) { body() }
                }
                if (bottomContent != null) {
                    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = bottomPadding)
                                .onSizeChanged { bottomContentHeight = it.height },
                        ) {
                            bottomContent()
                        }
                    }
                }
            }
        }
    }
}
