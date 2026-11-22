package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.paging.ItemSnapshotList
import java.util.LinkedHashMap
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import top.yukonga.miuix.kmp.basic.TopAppBarState

private const val MaxKeyedListStates = 16

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
    private val detailPresentations = LinkedHashMap<Any, GithubDetailPresentation>(16, 0.75f, true)
    private val refreshGates = LinkedHashMap<Any, PageRefreshGate>()
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

    /**
     * 该路由身份的详情是否已经展示过。页面用它决定返回组合后跳过加载位，必须与 [rememberGithubDetailPresentation] 传入的 key
     * 完全一致；已展示就意味着这份呈现状态还在 [detailPresentations] 里（超出上限被淘汰的按首次进入处理）。
     */
    fun detailPresented(key: Any): Boolean = key in detailPresentations

    /**
     * 详情首屏状态跟着路由子项存活，而不是跟着页面组合存活。
     *
     * 页面被覆盖后重新组合时，只靠一个「已展示」布尔值重建不出已经走完的进度、已经就绪的文档 登记和首帧的
     * `interactive`，骨架因此有重新盖住正文的机会。这里返回同一个对象，返回路径 不再需要任何重建条件成立。
     */
    internal fun detailPresentation(
        key: Any,
        create: () -> GithubDetailPresentation,
    ): GithubDetailPresentation {
        val retained = detailPresentations[key]
        if (retained != null) {
            retained.restore()
            return retained
        }
        val created = create()
        detailPresentations[key] = created
        if (detailPresentations.size > MaxKeyedListStates) {
            detailPresentations.remove(detailPresentations.keys.first())
        }
        return created
    }

    private val keyedListStates = LinkedHashMap<String, LazyListState>(16, 0.75f, true)
    private val keyedTopBarStates = LinkedHashMap<LazyListState, TopAppBarState>(16, 0.75f, true)
    private val keyedScrollAnchors = LinkedHashMap<String, GithubScrollAnchor>(16, 0.75f, true)

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
