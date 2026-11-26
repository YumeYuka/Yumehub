package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.paging.ItemSnapshotList
import java.util.LinkedHashMap
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import top.yukonga.miuix.kmp.basic.TopAppBarState

internal const val MaxKeyedListStates = 16

/** 列表离开组合时的真实位置；分页重新收集数据首帧为空，不能指望 LazyListState 自己保住。 */
internal data class GithubScrollAnchor(val index: Int, val offset: Int)

/**
 * 滚动状态随路由子项存活，不因页面暂时移出组合而重置。
 *
 * 顶栏折叠偏移按列表身份保存：页面被覆盖后回来，顶栏与正文位置一起恢复，不会出现大标题 展开而正文已经在中间的状态。
 */
@Stable
class GithubPageState {
    val listState = LazyListState()
    private val refreshGates = LinkedHashMap<Any, PageRefreshGate>()

    internal fun forgetPagingFrame(key: String) { pagingFrames.remove(key) }
    private val pagingFrames = LinkedHashMap<String, List<Any?>>(16, 0.75f, true)
    private val pagingKeys = LinkedHashMap<String, GithubPagingSlotKeys>()

    internal fun pagingKeys(key: String) =
        pagingKeys
            .getOrPut(key) { GithubPagingSlotKeys(key) }
            .also {
                if (pagingKeys.size > MaxKeyedListStates) pagingKeys.remove(pagingKeys.keys.first())
            }

    internal fun refreshGate(owner: Any): PageRefreshGate =
        refreshGates
            .getOrPut(owner) { PageRefreshGate() }
            .also {
                if (refreshGates.size > MaxKeyedListStates)
                    refreshGates.remove(refreshGates.keys.first())
            }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> pagingFrame(key: String): List<T?>? = pagingFrames[key] as? List<T?>

    internal fun retainPagingFrame(key: String, items: ItemSnapshotList<*>) {
        pagingFrames[key] = items
        if (pagingFrames.size > MaxKeyedListStates) pagingFrames.remove(pagingFrames.keys.first())
    }

    private val keyedListStates = LinkedHashMap<String, LazyListState>(16, 0.75f, true)
    private val keyedTopBarStates = LinkedHashMap<LazyListState, TopAppBarState>(16, 0.75f, true)
    private val keyedScrollAnchors = LinkedHashMap<String, GithubScrollAnchor>(16, 0.75f, true)

    /** SaveableStateHolder keys must survive recreation; never use the LazyListState instance. */
    internal fun contentStateKey(state: LazyListState): String? =
        if (state === listState) null
        else keyedListStates.entries.firstOrNull { it.value === state }?.key

    /** 按数据源或页面模式复用状态；超出上限时淘汰最久未访问的 key，默认状态不参与淘汰。 */
    fun listState(key: String): LazyListState {
        val state = keyedListStates.getOrPut(key) { LazyListState() }
        if (keyedListStates.size > MaxKeyedListStates) {
            val oldest = keyedListStates.entries.iterator()
            val evicted = oldest.next()
            oldest.remove()
            keyedTopBarStates.remove(evicted.value)
            keyedScrollAnchors.remove(evicted.key)
        }
        return state
    }

    /** 顶栏折叠状态与列表一一对应，同一列表回到组合时沿用上次的偏移。 */
    internal fun topBarState(listState: LazyListState): TopAppBarState {
        val state =
            keyedTopBarStates.getOrPut(listState) {
                TopAppBarState(
                    initialHeightOffsetLimit = -Float.MAX_VALUE,
                    initialHeightOffset = 0f,
                    initialContentOffset = 0f,
                )
            }
        if (keyedTopBarStates.size > MaxKeyedListStates) {
            val oldest = keyedTopBarStates.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return state
    }

    /** 页面离开组合时记下位置；首项回到顶部后不再保留旧锚点。 */
    internal fun rememberScrollAnchor(key: String, listState: LazyListState) {
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        if (index == 0 && offset == 0) {
            keyedScrollAnchors.remove(key)
            return
        }
        keyedScrollAnchors[key] = GithubScrollAnchor(index, offset)
        if (keyedScrollAnchors.size > MaxKeyedListStates) {
            val oldest = keyedScrollAnchors.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    internal fun scrollAnchor(key: String): GithubScrollAnchor? = keyedScrollAnchors[key]

    /** 只在恢复请求已提交后清除，避免同一锚点被反复应用。 */
    internal fun clearScrollAnchor(key: String, anchor: GithubScrollAnchor) {
        if (keyedScrollAnchors[key] === anchor) keyedScrollAnchors.remove(key)
    }
}

val LocalGithubPageState = staticCompositionLocalOf<GithubPageState?> { null }
