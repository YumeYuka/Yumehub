package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.CardEnterSpec
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.navigation.PagingRevalidationBinding
import moe.yumeyuka.yumehub.ui.navigation.PageRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.SkeletonEntrance
import moe.yumeyuka.yumehub.ui.navigation.LocalCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalPagingRevalidation
import moe.yumeyuka.yumehub.ui.navigation.LocalRouteEntryReady
import moe.yumeyuka.yumehub.ui.navigation.LocalSkeletonEntrance
import moe.yumeyuka.yumehub.ui.navigation.readingItems

private val LocalPagingContentKey = staticCompositionLocalOf<Any?> { null }
private val LocalPrimaryPagingItems = staticCompositionLocalOf<LazyPagingItems<*>?> { null }
private val LocalPagingAccessDenied = staticCompositionLocalOf { false }

private fun CombinedLoadStates.accessDenied(): Boolean =
    (refresh as? LoadState.Error)?.error?.invalidatesCachedContent() == true ||
        (append as? LoadState.Error)?.error?.invalidatesCachedContent() == true ||
        (prepend as? LoadState.Error)?.error?.invalidatesCachedContent() == true

val IdlePagingRevalidation: StateFlow<RevalidationState> =
    MutableStateFlow<RevalidationState>(RevalidationState.Idle).asStateFlow()

class PagingPresentation<T : Any>
internal constructor(
    val items: LazyPagingItems<T>,
    val listState: LazyListState,
    val initialLoading: Boolean,
)

/** 首次及新分类加载显示骨架，数据到达后由同一行槽内的卡片交接正文；从详情返回恢复原位置。 */
@Composable
fun <T : Any> PagingContent(
    source: Flow<PagingData<T>>,
    contentKey: Any,
    revalidation: StateFlow<RevalidationState> = IdlePagingRevalidation,
    onRetryRevalidation: (() -> Unit)? = null,
    showLoadErrors: Boolean = true,
    content: @Composable (PagingPresentation<T>) -> Unit,
) {
    val pageState = LocalAppPageState.current
    val stateKey = "paging:$contentKey"
    val savedListState =
        rememberSaveable(contentKey, saver = LazyListState.Saver) { LazyListState() }
    val listState = pageState?.listState(stateKey) ?: savedListState
    val retained = remember(pageState, stateKey) { pageState?.pagingFrame<T>(stateKey) != null }
    val anchor =
        remember(pageState, stateKey) {
            // With retained rows, LazyListState already has both the entity key and the pixel
            // offset.
            if (!retained) pageState?.scrollAnchor(stateKey) else null
        }
    // 恢复请求提交前再次离开组合时保留原锚点，不能用被空首帧夹到 0 的位置覆盖。
    val restorePending = remember(stateKey, listState) { mutableStateOf(anchor != null) }
    val awaitingFirstPage = remember(stateKey, listState) { mutableStateOf(false) }
    val previousFirst = remember(pageState, stateKey) {
        mutableStateOf(pageState?.pagingFrame<T>(stateKey)?.firstOrNull())
    }
    // 页面被覆盖后回来，分页重新收集首帧为空，LazyListState 会被夹到 0；离开时先记下真实位置。
    DisposableEffect(pageState, stateKey, listState) {
        onDispose {
            if (!restorePending.value && !awaitingFirstPage.value) {
                pageState?.rememberScrollAnchor(stateKey, listState)
            }
        }
    }
    val active = LocalAppPageHost.current?.isCurrent != false
    val observable = if (active) source else remember { emptyFlow<PagingData<T>>() }
    val items = key(contentKey) { observable.collectAsLazyPagingItems() }
    val validation = revalidation.collectAsState().value
    val denied =
        items.loadState.accessDenied() ||
            (validation as? RevalidationState.Failed)?.error?.invalidatesCachedContent() == true
    if (showLoadErrors) {
        val failure =
            (items.loadState.refresh as? LoadState.Error)
                ?: (items.loadState.append as? LoadState.Error)
        LoadFailureToast(failure, LocalAppStrings.current.Activity.LoadFailed)
    }
    val loading =
        !denied && !retained && items.itemCount == 0 && items.loadState.refresh is LoadState.Loading
    SideEffect {
        val pending = anchor
        val first = items.itemSnapshotList.firstOrNull()
        val headChanged = previousFirst.value != null && first != previousFirst.value
        if (first != null) previousFirst.value = first
        if (restorePending.value && pending != null && !denied && items.itemCount > 0) {
            // 在测量前改写位置，缓存数据到达的那一帧就落在原处，不先闪一下顶部。
            listState.requestScrollToItem(pending.index, pending.offset)
            restorePending.value = false
            pageState?.clearScrollAnchor(stateKey, pending)
        } else if (
            active && !denied && first != null && headChanged &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        ) {
            // Keep the viewport at the top, not on the entity that used to be first.
            listState.requestScrollToItem(0)
            awaitingFirstPage.value = false
        } else if (!retained && pending == null) {
            if (loading) awaitingFirstPage.value = true
            else if (awaitingFirstPage.value && items.itemCount > 0) {
                // 骨架可以滚动，但它的位置不是首屏正文的阅读位置。
                listState.requestScrollToItem(0)
                awaitingFirstPage.value = false
            }
        }
    }
    CompositionLocalProvider(
        LocalPagingContentKey provides contentKey,
        LocalPrimaryPagingItems provides items,
        LocalPagingAccessDenied provides denied,
        LocalPagingRevalidation provides
            onRetryRevalidation?.let {
                PagingRevalidationBinding(revalidation, it)
            },
    ) {
        content(PagingPresentation(items, listState, loading))
    }
}

/** Insertion is eligible only after an active row of this list instance has actually drawn. */
internal class PagingDrawHistory {
    var hasDrawn = false
    var lastKeys: Set<String> = emptySet()
}

class PagingSlots<T : Any>
internal constructor(
    internal val items: LazyPagingItems<T>,
    val snapshot: List<T?>,
    internal val keys: List<String>,
    val isEmptyResult: Boolean,
    internal val live: Boolean,
    internal val drawHistory: PagingDrawHistory,
    internal val insertedKeys: Set<String>,
    internal val entrance: SkeletonEntrance,
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
fun <T : Any> rememberPagingSlots(
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
    accessDenied: Boolean = false,
    itemKey: (T) -> String?,
): PagingSlots<T> {
    val page = LocalAppPageState.current
    val contentKey = LocalPagingContentKey.current
    val frameKey = "$keyPrefix:$contentKey"
    val allocator =
        remember(page, frameKey) { page?.pagingKeys(frameKey) ?: PagingSlotKeys(frameKey) }
    val cacheKey =
        if (LocalPrimaryPagingItems.current === items) "paging:$contentKey" else "slots:$frameKey"
    val previous = remember(page, cacheKey) { mutableStateOf(page?.pagingFrame<T>(cacheKey)) }
    val current = items.itemSnapshotList
    val refresh = items.loadState.refresh
    val denied = accessDenied || LocalPagingAccessDenied.current || items.loadState.accessDenied()
    val blocked = denied || allocator.rejectedFrame?.get() === current
    val hasResult =
        contentReady && (current.isNotEmpty() || !initialLoading && refresh is LoadState.NotLoading)
    val snapshot =
        when {
            blocked -> emptyList()
            hasResult -> current
            else -> previous.value.orEmpty()
        }
    SideEffect {
        if (denied) {
            allocator.rejectFrame(current)
            previous.value = null
            page?.forgetPagingFrame(cacheKey)
        } else if (hasResult && !blocked) {
            previous.value = current
            page?.retainPagingFrame(cacheKey, current)
        }
    }
    val refreshOwner = remember(page, frameKey) { "paging-refresh:$frameKey" }
    if (autoRefresh) {
        PageRefreshEffect(
            refreshOwner,
            *refreshDomains.toTypedArray(),
            refreshOnFirstActivation = false,
            refreshOnExpiry = false,
            pollWhileVisible = pollWhileVisible,
        ) {
            items.refresh()
        }
    }
    val append = items.loadState.append
    val showSkeleton = !blocked && snapshot.isEmpty() && previous.value == null && initialLoading
    val appending = !blocked && append is LoadState.Loading && snapshot.isNotEmpty()
    val ids = remember(allocator, snapshot) { snapshot.map { it?.let(itemKey) } }
    val keys =
        remember(allocator, snapshot, appending, showSkeleton) {
            allocator.update(
                ids,
                appending,
                showSkeleton,
            )
        }
    val emptyResult =
        !blocked && !showSkeleton && snapshot.isEmpty() && (hasResult || previous.value != null)
    val history = remember(frameKey, items) { PagingDrawHistory() }
    val keySet = remember(keys) { keys.toSet() }
    val inserted =
        remember(history, keySet) {
            if (history.hasDrawn) keySet - history.lastKeys else emptySet()
        }
    SideEffect { history.lastKeys = keySet }
    val live = !blocked && hasResult
    return remember(items, snapshot, keys, emptyResult, live, history, inserted, allocator) {
        PagingSlots(
            items,
            snapshot,
            keys,
            emptyResult,
            live,
            history,
            inserted,
            allocator.entrance,
        )
    }
}

fun <T : Any> LazyListScope.pagingItems(
    slots: PagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    itemContent: @Composable (T?) -> Unit,
) {
    pagingItemsIndexed(slots, contentType, reading) { item, _, _ -> itemContent(item) }
}

/** 为未自行做占位交接的条目，在同一个分页槽位内过渡。 */
fun <T : Any> LazyListScope.pagingItems(
    slots: PagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    placeholder: @Composable () -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    pagingItems(slots, contentType, reading) { item ->
        LoadingContent(loading = item == null, placeholder = placeholder) {
            if (item != null) itemContent(item)
        }
    }
}

/** 与 [pagingItems] 相同，同时向内容提供页内序号和当前总数。 */
fun <T : Any> LazyListScope.pagingItemsIndexed(
    slots: PagingSlots<T>,
    contentType: String,
    reading: Boolean = false,
    itemContent: @Composable (T?, Int, Int) -> Unit,
) {
    val renderItem: @Composable LazyItemScope.(Int) -> Unit = { index ->
        val active = LocalAppPageHost.current?.isCurrent != false
        val entryReady = LocalRouteEntryReady.current
        if (active && slots.live && index < slots.items.itemCount) {
            // A retained frame can be longer or differently ordered than Paging's live generation.
            slots.items[index]
        }
        val item = slots.snapshot.getOrNull(index)
        // Ready cached rows and cold skeleton slots share the same entrance. Keep it with the
        // allocator so restoring this list does not replay the entrance.
        val history = slots.drawHistory
        val motion =
            if (item != null && slots.entrance.presented && slots.keys[index] in slots.insertedKeys)
                cardItemMotion()
            else Modifier
        val receipt =
            if (
                active &&
                    entryReady &&
                    item != null &&
                    (!history.hasDrawn || !slots.entrance.presented)
            )
                Modifier.drawWithContent {
                    drawContent()
                    if (size.width > 0f && size.height > 0f) {
                        history.hasDrawn = true
                        slots.entrance.presented = true
                    }
                }
            else Modifier
        val semantics =
            if (item != null) Modifier
            else
                Modifier.clearAndSetSemantics {
                    if (index == 0 || index == slots.snapshot.size) {
                        liveRegion = LiveRegionMode.Polite
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }
                }
        // 只有真正缺数据的骨架槽等页面入场。已有数据的行随页面滑入并立即可点，不因入场
        // 结束而延迟组合，也不再用骨架层吞掉这段时间的点击。
        CompositionLocalProvider(
            LocalSkeletonEntrance provides if (item == null) slots.entrance else null
        ) {
            Box(motion.then(receipt).then(semantics)) {
                itemContent(item, index, slots.keys.size)
            }
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

/** Animate insertion. Removed or access-revoked entities disappear immediately. */
@Composable
fun LazyItemScope.cardItemMotion(): Modifier =
    if (LocalCardMotionEnabled.current && LocalAppPageHost.current?.isCurrent != false) {
        Modifier.animateItem(
            fadeInSpec = CardEnterSpec,
            fadeOutSpec = null,
            placementSpec = null,
        )
    } else Modifier
