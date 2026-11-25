package moe.yumeyuka.yumehub.feature.explore

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.yumeyuka.yumehub.data.api.dto.FeedEventDto
import moe.yumeyuka.yumehub.data.repository.DashboardFeedPage
import moe.yumeyuka.yumehub.data.repository.DashboardFeedRepository
import moe.yumeyuka.yumehub.data.repository.PagingContext
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.PagingSnapshot
import moe.yumeyuka.yumehub.data.repository.defaultPagingConfig
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage

internal fun feedPagingKey(filters: Set<FeedFilter>, sort: FeedSort): String =
    "dashboard-feed:v5:${sort.name}:${filters.toSet().map { it.name }.sorted().joinToString(",")}:size=25"

internal fun feedPager(
    api: DashboardFeedRepository,
    login: String,
    filters: Set<FeedFilter>,
    sort: FeedSort,
    repository: PagingRepository,
): Flow<PagingData<FeedEventDto>> {
    val selected = filters.toSet()
    val key = feedPagingKey(selected, sort)
    val feedTags =
        setOf(CacheTag(DataDomain.Relationships, null), CacheTag(DataDomain.Repositories, null))
    return repository.pages(login, key, defaultPagingConfig(FeedPageSize), feedTags) { context ->
        FeedPagingSource(
            api,
            login,
            selected,
            sort,
            repository.snapshot(login, key, FeedEventDto.serializer(), context, feedTags),
            context,
        )
    }
}

private class FeedPagingSource(
    private val api: DashboardFeedRepository,
    private val login: String,
    private val filters: Set<FeedFilter>,
    private val sort: FeedSort,
    private val snapshot: PagingSnapshot<FeedEventDto>,
    private val context: PagingContext,
) : PagingSource<Int, FeedEventDto>() {
    private val loads = Mutex()
    private val seen = mutableMapOf<String, Int>()
    private val cursors = mutableMapOf<Int, String?>(1 to null)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeedEventDto> =
        loads.withLock { loadPage(params) }

    private suspend fun loadPage(params: LoadParams<Int>): LoadResult<Int, FeedEventDto> =
        try {
            val initial =
                if (params is LoadParams.Refresh) snapshot.initialPage(FeedPageSize) else null
            val result =
                if (initial != null) {
                    if (!initial.isFresh()) {
                        context.revalidate(this) {
                            // 校验首屏不能改写当前列表正用于追加的游标。
                            val updated = loadFirst(force = true, cursors = mutableMapOf(1 to null))
                            snapshot.save(updated)
                            initial != updated.copy(updatedAt = initial.updatedAt)
                        }
                    }
                    initial
                } else {
                    loadNetwork(
                        params.key,
                        force = params !is LoadParams.Refresh || context.forceRefresh,
                    )
                }
            cursors[result.page] = result.requestCursor
            cursors[result.page + 1] = result.nextCursor
            currentCoroutineContext().ensureActive()
            context.ensureCurrent()
            val items =
                synchronized(seen) {
                    val local = mutableSetOf<String>()
                    result.items.filter {
                        local.add(it.id) && seen.getOrPut(it.id) { result.page } == result.page
                    }
                }
            LoadResult.Page(items, result.prevKey, result.nextKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LoadResult.Error(error)
        }

    private suspend fun loadNetwork(key: Int?, force: Boolean): PagingSnapshotPage<FeedEventDto> {
        if (key == null) {
            return loadFirst(force, cursors).also { snapshot.saveInBackground(it) }
        }
        return if (sort == FeedSort.Oldest) loadReverse(key, force, cursors)
        else loadForward(key, force, cursors)
    }

    private suspend fun loadFirst(force: Boolean, cursors: MutableMap<Int, String?>) =
        if (sort == FeedSort.Oldest)
            loadReverseTail(requestPage(1, force, cursors), force, cursors)
        else loadForward(null, force, cursors)

    private suspend fun loadForward(
        key: Int?,
        force: Boolean,
        cursors: MutableMap<Int, String?>,
    ): PagingSnapshotPage<FeedEventDto> {
        val start = key ?: 1
        var page = start
        while (true) {
            val response = requestPage(page, force, cursors)
            val items = response.filtered(ascending = false)
            val next = response.nextKey
            if (items.isNotEmpty() || next == null) {
                return response.copy(
                    items = items,
                    prevKey =
                        if (page == start && key != null) (start - 1).takeIf { it >= 1 } else null,
                )
            }
            check(next > page) { "GitHub 返回了循环分页链接" }
            page = next
        }
    }

    private suspend fun loadReverse(
        key: Int,
        force: Boolean,
        cursors: MutableMap<Int, String?>,
    ): PagingSnapshotPage<FeedEventDto> {
        var currentPage = key
        while (true) {
            val response = requestPage(currentPage, force, cursors)
            val items = response.filtered(ascending = true)
            val next = (currentPage - 1).takeIf { it >= 1 }
            if (items.isNotEmpty() || next == null) {
                return response.copy(items = items, prevKey = null, nextKey = next)
            }
            currentPage = next
        }
    }

    /** 官方连接没有末页游标，沿真实 next 游标到末页后，以已记录的页边界反向追加。 */
    private suspend fun loadReverseTail(
        first: PagingSnapshotPage<FeedEventDto>,
        force: Boolean,
        cursors: MutableMap<Int, String?>,
    ): PagingSnapshotPage<FeedEventDto> {
        var response = first
        while (true) {
            val next = response.nextKey ?: break
            check(next > response.page) { "GitHub 返回了循环分页链接" }
            response = requestPage(next, force, cursors)
        }
        val oldest =
            response.copy(
                items = response.filtered(true),
                prevKey = null,
                nextKey = (response.page - 1).takeIf { it >= 1 },
            )
        val next = oldest.nextKey
        return if (oldest.items.isNotEmpty() || next == null) oldest
        else loadReverse(next, force, cursors)
    }

    /** UI 仍使用既有整页缓存，官方接口实际以 endCursor 追加；快照恢复时按缓存页重建游标链。 */
    private suspend fun requestPage(
        page: Int,
        force: Boolean,
        cursors: MutableMap<Int, String?>,
    ): PagingSnapshotPage<FeedEventDto> {
        val first = if (cursors.containsKey(page)) page else 1
        for (current in first..page) {
            if (current < page && cursors.containsKey(current + 1)) continue
            check(cursors.containsKey(current)) { "Dashboard feed cursor was missing" }
            val result =
                context.await(
                    key = "official-page:$current:after:${cursors[current]}:size:$FeedPageSize",
                    serializer = DashboardFeedPage.serializer(),
                    force = force && current == page,
                ) {
                    api.page(
                        login,
                        cursors[current],
                        filters.mapTo(linkedSetOf()) { it.serverGroup },
                    )
                }
            val next = result.nextCursor
            if (next != null) {
                check(cursors.filterKeys { it <= current }.values.none { it == next }) {
                    "GitHub returned a repeated feed cursor"
                }
                cursors[current + 1] = next
            }
            if (current == page)
                return PagingSnapshotPage(
                    items = result.items,
                    prevKey = (page - 1).takeIf { it >= 1 },
                    nextKey = if (next != null) page + 1 else null,
                    pageSize = FeedPageSize,
                    updatedAt = System.currentTimeMillis(),
                    page = page,
                    requestCursor = cursors[current],
                    nextCursor = next,
                )
            check(next != null) { "Dashboard feed ended before the requested page" }
        }
        error("Invalid dashboard feed page")
    }

    private fun PagingSnapshotPage<FeedEventDto>.filtered(ascending: Boolean): List<FeedEventDto> {
        val events = items.filter { it.matches(filters) }
        return if (ascending) events.sortedBy { it.createdAt.orEmpty() }
        else events.sortedByDescending { it.createdAt.orEmpty() }
    }

    override fun getRefreshKey(state: PagingState<Int, FeedEventDto>): Int? = null
}

private const val FeedPageSize = 25
