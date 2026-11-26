package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.navigation.GithubListTransition
import moe.yumeyuka.yumehub.ui.navigation.GithubPagingRevalidationBinding
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubListFrameActive
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPagingRevalidation
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady
import moe.yumeyuka.yumehub.ui.navigation.readingItems

private val LocalPagingContentKey = staticCompositionLocalOf<Any?> { null }
val IdleGithubPagingRevalidation: StateFlow<RevalidationState> =
    MutableStateFlow<RevalidationState>(RevalidationState.Idle).asStateFlow()

class GithubPagingPresentation<T : Any>
internal constructor(
    val items: LazyPagingItems<T>,
    val listState: LazyListState,
    val transition: GithubListTransition,
    val initialLoading: Boolean,
)

/** 首次及新分类加载显示骨架，数据到达后由同一行槽内的卡片交接正文；从详情返回恢复原位置。 */
@Composable
fun <T : Any> GithubPagingContent(
    source: Flow<PagingData<T>>,
    contentKey: Any,
    revalidation: StateFlow<RevalidationState>,
    onRetryRevalidation: () -> Unit,
    order: Int = 0,
    content: @Composable (GithubPagingPresentation<T>) -> Unit,
) {
    val pageState = LocalGithubPageState.current
    val stateKey = "paging:$contentKey"
    val savedListState =
        rememberSaveable(contentKey, saver = LazyListState.Saver) { LazyListState() }
    val listState = pageState?.listState(stateKey) ?: savedListState
    val anchor = remember(pageState, stateKey) { pageState?.scrollAnchor(stateKey) }
    // 恢复请求提交前再次离开组合时保留原锚点，不能用被空首帧夹到 0 的位置覆盖。
    val restorePending = remember(stateKey, listState) { mutableStateOf(anchor != null) }
    // 页面被覆盖后回来，分页重新收集首帧为空，LazyListState 会被夹到 0；离开时先记下真实位置。
    DisposableEffect(pageState, stateKey, listState) {
        onDispose {
            if (!restorePending.value) pageState?.rememberScrollAnchor(stateKey, listState)
        }
    }
    val items = source.collectAsLazyPagingItems()
    val failure =
        (items.loadState.refresh as? LoadState.Error)
            ?: (items.loadState.append as? LoadState.Error)
    GithubLoadFailureToast(failure, LocalGithubStrings.current.Activity.LoadFailed)
    val retained = pageState?.pagingFrame<T>("content:$contentKey")
    val loading =
        retained == null && items.itemCount == 0 && items.loadState.refresh is LoadState.Loading
    SideEffect {
        if (items.itemCount > 0 || items.loadState.refresh is LoadState.NotLoading) {
            pageState?.retainPagingFrame("content:$contentKey", items.itemSnapshotList)
        }
    }
    SideEffect {
        val pending = anchor
        if (restorePending.value && pending != null && items.itemCount > 0) {
            // 在测量前改写位置，缓存数据到达的那一帧就落在原处，不先闪一下顶部。
            listState.requestScrollToItem(pending.index, pending.offset)
            // 只有分页条目确实覆盖锚点才丢弃；缓存只回放首页时保留，下次进入还能再试。
            if (items.itemCount > pending.index) pageState?.clearScrollAnchor(stateKey, pending)
            restorePending.value = false
        }
    }
    CompositionLocalProvider(
        LocalPagingContentKey provides contentKey,
        LocalGithubPagingRevalidation provides
            GithubPagingRevalidationBinding(revalidation, onRetryRevalidation),
    ) {
        content(
            GithubPagingPresentation(
                items,
                listState,
                GithubListTransition(contentKey, loading, order),
                loading,
            ),
        )
    }
}

internal fun <T> retainedPagingSnapshot(
    previous: List<T>?,
    current: List<T>,
    refresh: LoadState,
    contentReady: Boolean,
): List<T> =
    if (!contentReady || current.isEmpty() && refresh !is LoadState.NotLoading) {
        previous ?: current
    } else current

class GithubPagingSlots<T : Any>
internal constructor(
    internal val items: LazyPagingItems<T>,
    internal val snapshot: List<T?>,
    internal val keys: List<String>,
) {
    val size: Int
        get() = keys.size

    private val keyIndices by
        lazy(LazyThreadSafetyMode.NONE) {
            keys.withIndex().associate { (index, key) -> key to index }
        }

    /** 按真实布局键读取快照；跳过页头、骨架与过渡旧槽，不向 Paging 提交预取提示。 */
    fun visibleItems(state: LazyListState): List<T> =
        state.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            keyIndices[info.key]?.let(snapshot::getOrNull)
        }
}

/** 首屏提供可见的骨架区域；追加使用单个尾槽，已显示的正文按业务 ID 维护身份。 */
@Composable
fun <T : Any> rememberGithubPagingSlots(
    items: LazyPagingItems<T>,
    keyPrefix: String,
    initialLoading: Boolean = items.itemCount == 0 && items.loadState.refresh is LoadState.Loading,
    contentReady: Boolean = true,
    autoRefresh: Boolean = true,
    pollWhileVisible: Boolean = false,
    refreshDomains: Set<DataDomain> =
        setOf(
            DataDomain.Repositories,
            DataDomain.Relationships,
            DataDomain.Work,
            DataDomain.Content,
        ),
    itemKey: (T) -> String?,
): GithubPagingSlots<T> {
    val page = LocalGithubPageState.current
    val frameKey = "$keyPrefix:${LocalPagingContentKey.current}"
    val allocator =
        remember(page, frameKey) { page?.pagingKeys(frameKey) ?: GithubPagingSlotKeys(frameKey) }
    val previous =
        remember(items, frameKey) { mutableStateOf(page?.pagingFrame<T>("slots:$frameKey")) }
    val entryReady = LocalGithubRouteEntryReady.current
    val firstPresentation = remember(items, frameKey) { mutableStateOf(previous.value != null) }
    if (!firstPresentation.value && entryReady) {
        LaunchedEffect(items, frameKey) {
            withFrameNanos {}
            withFrameNanos {}
            firstPresentation.value = true
        }
    }
    val showEntrySkeleton = !firstPresentation.value
    val current = items.itemSnapshotList
    val retain =
        !contentReady || current.isEmpty() && items.loadState.refresh !is LoadState.NotLoading
    val snapshot =
        if (showEntrySkeleton) emptyList()
        else retainedPagingSnapshot(previous.value, current, items.loadState.refresh, contentReady)
    SideEffect {
        if (!retain && (current.isNotEmpty() || items.loadState.refresh is LoadState.NotLoading)) {
            previous.value = current
            page?.retainPagingFrame("slots:$frameKey", current)
        }
    }
    val refreshOwner = remember(page, frameKey) { "paging-refresh:$frameKey" }
    if (autoRefresh) {
        GithubRefreshEffect(
            refreshOwner,
            *refreshDomains.toTypedArray(),
            refreshOnFirstActivation = false,
            pollWhileVisible = pollWhileVisible,
        ) {
            items.refresh()
        }
    }
    val append = items.loadState.append
    val awaitingFirstPage = initialLoading && previous.value == null
    val showInitialSlots =
        remember(items, frameKey, awaitingFirstPage) { mutableStateOf(false) }
    if (awaitingFirstPage) {
        LaunchedEffect(items, frameKey) {
            // 给会话回放和磁盘快照一个短窗口，避免在正文前只闪一帧骨架。
            delay(120L)
            showInitialSlots.value = true
        }
    }
    val showSkeleton = showEntrySkeleton || showInitialSlots.value
    val appending = append is LoadState.Loading
    val ids = remember(allocator, snapshot) { snapshot.map { it?.let(itemKey) } }
    val keys =
        remember(allocator, snapshot, appending, showSkeleton) {
            allocator.update(
                ids,
                appending,
                showSkeleton,
            )
        }
    return remember(items, snapshot, keys) { GithubPagingSlots(items, snapshot, keys) }
}

fun <T : Any> LazyListScope.githubPagingItems(
    slots: GithubPagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    itemContent: @Composable (T?) -> Unit,
) {
    githubPagingItemsIndexed(slots, contentType, reading) { item, _, _ -> itemContent(item) }
}

/** 为未自行做占位交接的条目，在同一个分页槽位内过渡。 */
fun <T : Any> LazyListScope.githubPagingItems(
    slots: GithubPagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    placeholder: @Composable () -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    githubPagingItems(slots, contentType, reading) { item ->
        GithubLoadingContent(loading = item == null, placeholder = placeholder) {
            if (item != null) itemContent(item)
        }
    }
}

/** 与 [githubPagingItems] 相同，同时向内容提供页内序号和当前总数。 */
fun <T : Any> LazyListScope.githubPagingItemsIndexed(
    slots: GithubPagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    itemContent: @Composable (T?, Int, Int) -> Unit,
) {
    val renderItem: @Composable LazyItemScope.(Int) -> Unit = { index ->
        val active = LocalGithubListFrameActive.current
        if (active && index < slots.snapshot.size && index < slots.items.itemCount) {
            // 访问 Paging 以触发预取，但显示内容来自本帧快照，退场占位不能偷读新数据。
            slots.items[index]
        }
        Box(
            Modifier.animateItem(
                fadeInSpec = null,
                fadeOutSpec = tween(180),
                placementSpec = spring(dampingRatio = 1f, stiffness = 400f),
            ),
        ) {
            itemContent(slots.snapshot.getOrNull(index), index, slots.keys.size)
        }
    }
    if (reading) {
        readingItems(
            count = slots.keys.size,
            key = { slots.keys[it] },
            contentType = { contentType },
            itemContent = renderItem,
        )
    } else {
        items(
            count = slots.keys.size,
            key = { slots.keys[it] },
            contentType = { contentType },
            itemContent = renderItem,
        )
    }
}
