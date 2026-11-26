package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** 数据条件由页面提供，正文布局条件只属于当前挂载，不写入 ViewModel。 */
@Stable
class GithubDetailPresentation
internal constructor(
    val kind: GithubDetailKind,
    private val chrome: GithubDetailPresentation? = null,
    internal val previouslyPresented: Boolean = false,
    initialDataReady: Boolean = false,
) {
    internal var progress by mutableStateOf(Animatable(if (previouslyPresented) 1f else 0f))
        private set

    /** 返回已展示过的页面时，数据在此之前就已就绪，不能等重组后再回落成 false。 */
    internal var dataReady by mutableStateOf(previouslyPresented && initialDataReady)
    internal var dataFailure by mutableStateOf(false)
    internal var laidOut by mutableStateOf(false)
    var viewport by mutableStateOf(Rect.Zero)
        private set

    internal var revealed by mutableStateOf(previouslyPresented)
    private val documents = mutableStateMapOf<Any, Pair<Boolean, Boolean>>()
    private val documentRetries = mutableStateMapOf<Any, () -> Unit>()

    internal val failed: Boolean
        get() = dataFailure || hasVisibleDocumentFailure

    /** 只有首次进入时才由本状态决定揭开；返回的页面已经展示过，不再重新接管首屏。 */
    internal val ownsInitialReveal: Boolean
        get() = !previouslyPresented

    internal val canReveal: Boolean
        get() = dataReady && laidOut && !failed && documents.values.none { it.first && !it.second }

    private val progressCompleteState = derivedStateOf { progress.value == 1f }
    internal val progressComplete: Boolean
        get() = progressCompleteState.value

    val interactive: Boolean
        get() = revealed && progressComplete

    internal val chromeState: GithubDetailPresentation
        get() = chrome?.takeIf { it.revealed } ?: this

    fun onLayout(bounds: Rect) {
        viewport = bounds
        laidOut = bounds.width > 0f && bounds.height > 0f
    }

    fun documentVisible(token: Any, visible: Boolean) {
        val old = documents[token]
        val next = visible to (old?.second == true)
        if (old != next) documents[token] = next
    }

    fun documentReady(token: Any) {
        val old = documents[token] ?: return
        documents[token] = old.first to true
    }

    fun documentFailed(token: Any, retry: () -> Unit) {
        if (token !in documents) return
        documentRetries[token] = retry
    }

    val hasVisibleDocumentFailure: Boolean
        get() = documentRetries.keys.any { documents[it]?.first == true }

    fun retryDocuments() {
        val retries = documentRetries.values.toList()
        documentRetries.clear()
        retries.forEach { it() }
    }

    /** 重新进入组合时归位：页面可能在交接动画中途被覆盖，回来不能停在半途。 */
    internal fun restore() {
        if (revealed) progress = Animatable(1f)
    }

    fun removeDocument(token: Any) {
        documents.remove(token)
        documentRetries.remove(token)
    }
}

enum class GithubDetailKind {
    Issue,
    PullRequest,
    Discussion,
    CommitFiles,
    CommitInformation,
    Release,
}

val LocalGithubDetailPresentation = staticCompositionLocalOf<GithubDetailPresentation?> { null }

/** While a route is entering, existing skeletons can postpone their first content handoff. */
val LocalGithubRouteEntryReady = staticCompositionLocalOf { true }

@Composable
fun rememberGithubDetailPresentation(
    key: Any,
    kind: GithubDetailKind,
    dataReady: Boolean,
    failed: Boolean,
    chrome: GithubDetailPresentation? = null,
): GithubDetailPresentation {
    val pageState = LocalGithubPageState.current
    // 没有路由状态（预览、测试宿主）时退化成页面级记忆；上限由路由状态按 key 管理，这里不能淘汰，
    // 淘汰会把仍在组合中的状态换掉，占位就有重新出现的入口。
    val states = remember { linkedMapOf<Any, GithubDetailPresentation>() }
    val state =
        states.getOrPut(key) {
            // 状态存在路由子项里：返回组合拿回同一个对象，进度、文档登记和首帧可交互性都不重建，
            // 占位没有重新出现的入口；只有这条路由第一次进入时才从零开始。
            val create = {
                GithubDetailPresentation(
                    kind = kind,
                    chrome = chrome,
                    previouslyPresented = pageState?.detailPresented(key) == true,
                    initialDataReady = dataReady,
                )
            }
            pageState?.detailPresentation(key, create) ?: create()
        }
    state.dataReady = dataReady
    state.dataFailure = failed
    val ready = LocalGithubRouteEntryReady.current && state.canReveal
    LaunchedEffect(state, ready) {
        if (state.revealed || !ready) return@LaunchedEffect
        // 让 LazyColumn 的可见条目完成放置和文档登记，再检查一次；不等待屏外项。
        withFrameNanos {}
        withFrameNanos {}
        if (state.canReveal) state.revealed = true
    }
    LaunchedEffect(state, state.revealed) {
        if (state.revealed) {
            if (state.previouslyPresented) state.progress.snapTo(1f)
            else state.progress.animateTo(1f, tween(300))
        }
    }
    return state
}

/**
 * 已经展示过的页面再次进入组合（返回、切换分节、预测返回取消）时只认已有内容：此时刷新或重排 分页都不能再让占位盖回来，否则正文位置还在、内容却被骨架替换。首次进入仍要求第一页真正
 * 完成，返回的页面只要求目标本身还在。
 */
fun <T : Any> githubDetailPageSettled(
    items: LazyPagingItems<T>,
    targetReady: Boolean,
    previouslyPresented: Boolean,
): Boolean =
    githubDetailPageSettled(items.loadState.source.refresh, targetReady, previouslyPresented)

fun githubDetailPageSettled(
    refresh: LoadState,
    targetReady: Boolean,
    previouslyPresented: Boolean,
): Boolean = targetReady && (previouslyPresented || refresh is LoadState.NotLoading)

/** 骨架在加载时决定占位尺寸，交接后由唯一正文树决定尺寸并平滑调整。 */
@Composable
internal fun GithubDetailRegion(
    state: GithubDetailPresentation?,
    placeholder: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (state == null) {
        Box(modifier) { content() }
        return
    }
    Box(modifier.animateContentSize(tween(300))) {
        Box(
            (if (state.revealed) Modifier else Modifier.matchParentSize())
                .graphicsLayer { alpha = state.progress.value }
                .then(if (state.interactive) Modifier else Modifier.clearAndSetSemantics {}),
        ) {
            content()
        }
        if (!state.progressComplete) {
            Box(
                (if (state.revealed) Modifier.matchParentSize() else Modifier)
                    .graphicsLayer { alpha = 1f - state.progress.value }
                    .clearAndSetSemantics {},
            ) {
                placeholder()
            }
        }
        if (!state.interactive) {
            Box(
                Modifier.matchParentSize().pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                it.consume()
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun GithubDetailBody(
    state: GithubDetailPresentation?,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { state?.onLayout(it.boundsInWindow()) }) {
        GithubDetailRegion(state, placeholder, Modifier.fillMaxSize(), content)
        if (state != null && state.failed && !state.revealed) {
            Box(Modifier.fillMaxSize().background(GlasmioTheme.colors.groupedBackground))
        }
    }
}
