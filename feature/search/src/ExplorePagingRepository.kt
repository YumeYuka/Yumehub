package moe.yumeyuka.yumehub.feature.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.repository.GraphQlSearchRepository
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.PagingContext
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.PagingSnapshot
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile
import moe.yumeyuka.yumehub.data.repository.SearchSlice
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage

internal class ExplorePagingRepository(
    private val api: GraphQlSearchRepository,
    private val paging: PagingRepository,
    private val cache: CachedDataRepository,
) {
    fun invalidate(account: String, query: String, category: ExploreCategory) {
        paging.invalidate(account, key(query, category))
    }

    fun revalidation(account: String, query: String, category: ExploreCategory) =
        paging.revalidation(account, key(query, category))

    fun retryRevalidation(account: String, query: String, category: ExploreCategory) =
        paging.retryRevalidation(account, key(query, category))

    fun search(account: String, query: String, category: ExploreCategory): ExploreSearch {
        val queryKey = key(query, category)
        val tags = setOf(CacheTag(when (category) {
            ExploreCategory.Issues, ExploreCategory.PullRequests -> DataDomain.Work
            ExploreCategory.People, ExploreCategory.Organizations -> DataDomain.Relationships
            ExploreCategory.Code -> DataDomain.Content
            ExploreCategory.Repositories -> DataDomain.Repositories
        }))
        val summary =
            cache.sessionResource(account, "explore-summaries-v1") { Summaries() }.get(queryKey)
        fun <T : Any> pages(
            serializer: KSerializer<T>,
            key: (T) -> Any?,
            map: (T) -> ExploreResult?,
            fetch: suspend (String?) -> SearchSlice<T>,
        ) =
            paging.pages(
                account,
                queryKey,
                tags = tags,
            ) { context ->
                SearchPagingSource(
                    context,
                    paging.snapshot(
                        account,
                        queryKey,
                        serializer,
                        context,
                        tags = tags,
                    ),
                    summary,
                    key,
                    map,
                ) { page, after ->
                    val result = fetch(after)
                    PagingSnapshotPage(
                        items = result.items,
                        prevKey = (page - 1).takeIf { it > 0 },
                        nextKey = result.nextCursor?.let { page + 1 },
                        pageSize = 30,
                        updatedAt = System.currentTimeMillis(),
                        page = page,
                        totalCount = result.total,
                        requestCursor = after,
                        nextCursor = result.nextCursor,
                    )
                }
            }
        val items =
            when (category) {
                ExploreCategory.Repositories ->
                    pages(
                        RepositoryDto.serializer(),
                        { it.id ?: it.fullName },
                        { ExploreResult.Repository(it) },
                    ) { after ->
                        val body = api.page(query, category, after)
                        SearchSlice(body.repositories, body.nextCursor, body.totalCount)
                    }
                ExploreCategory.People,
                ExploreCategory.Organizations ->
                    pages(UserDto.serializer(), { it.login }, { ExploreResult.Actor(it) }) { after
                        ->
                        api.actors(query, category == ExploreCategory.Organizations, after)
                    }
                ExploreCategory.Code ->
                    pages(
                        SearchCodeFile.serializer(),
                        { listOf(it.owner, it.repository, it.ref, it.path) },
                        { ExploreResult.Code(it) },
                    ) { after ->
                        api.code(query, after)
                    }
                ExploreCategory.Issues,
                ExploreCategory.PullRequests -> {
                    val kind =
                        if (category == ExploreCategory.Issues) CollaborationKind.Issue
                        else CollaborationKind.PullRequest
                    pages(
                        CollaborationItemDto.serializer(),
                        { it.nodeId ?: it.htmlUrl },
                        { item ->
                            item
                                .workTarget()
                                ?.takeIf { it.kind == kind }
                                ?.let { target ->
                                    if (cache.changes(account).isMerged(target)) {
                                        ExploreResult.Work(
                                            item.copy(merged = true, state = "closed"),
                                            target,
                                        )
                                    } else ExploreResult.Work(item, target)
                                }
                        },
                    ) { after ->
                        val body = api.page(query, category, after)
                        SearchSlice(body.work, body.nextCursor, body.totalCount)
                    }
                }
            }
        return ExploreSearch(category, items, summary.asStateFlow())
    }

    private fun key(query: String, category: ExploreCategory): String =
        if (category == ExploreCategory.Code) "global-search:v3:Code:$query"
        else "global-search:v2:${category.name}:$query"

    /** 弱索引保留仍被 flow/VM 使用的摘要，闲置摘要仅保留 32 份，无独立 scope。 */
    private class Summaries {
        private val values =
            mutableMapOf<String, WeakReference<MutableStateFlow<ExploreSummary?>>>()
        private val recent =
            LinkedHashMap<String, MutableStateFlow<ExploreSummary?>>(16, 0.75f, true)

        @Synchronized
        fun get(key: String): MutableStateFlow<ExploreSummary?> {
            values.entries.removeAll { it.value.get() == null }
            val value =
                values[key]?.get()
                    ?: MutableStateFlow<ExploreSummary?>(null).also {
                        values[key] = WeakReference(it)
                    }
            recent[key] = value
            while (recent.size > 32) recent.remove(recent.keys.first())
            return value
        }
    }
}

private class SearchPagingSource<T : Any>(
    private val context: PagingContext,
    private val snapshot: PagingSnapshot<T>,
    private val summary: MutableStateFlow<ExploreSummary?>,
    private val itemKey: (T) -> Any?,
    private val mapItem: (T) -> ExploreResult?,
    private val request: suspend (Int, String?) -> PagingSnapshotPage<T>,
) : PagingSource<Int, ExploreResult>() {
    private val seen = mutableMapOf<Any, Int>()
    private val cursors = mutableMapOf<Int, String?>(1 to null)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ExploreResult> =
        try {
            val initial = if (params is LoadParams.Refresh) snapshot.initialPage(30) else null
            val page =
                if (initial == null) {
                    moe.yumeyuka.yumehub.data.repository.pageRead {
                        network(params.key ?: 1, params !is LoadParams.Refresh || context.forceRefresh)
                    }
                } else {
                    if (!initial.isFresh()) {
                        context.revalidate(this) {
                            val updated = network(1, force = true, cursors = mutableMapOf(1 to null))
                            snapshot.save(updated)
                            initial != updated.copy(updatedAt = initial.updatedAt)
                        }
                    }
                    initial
                }
            cursors[page.page] = page.requestCursor
            cursors[page.page + 1] = page.nextCursor
            currentCoroutineContext().ensureActive()
            context.ensureCurrent()
            if (params is LoadParams.Refresh) {
                summary.value = page.totalCount?.let(::ExploreSummary)
            }
            val items =
                synchronized(seen) {
                    val local = mutableSetOf<Any>()
                    page.items.mapNotNull { item ->
                        val mapped = mapItem(item) ?: return@mapNotNull null
                        val key = itemKey(item)
                        if (
                            key == null ||
                                (local.add(key) && seen.getOrPut(key) { page.page } == page.page)
                        )
                            mapped
                        else null
                    }
                }
            LoadResult.Page(items, page.prevKey, page.nextKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LoadResult.Error(error)
        }

    private suspend fun network(
        start: Int,
        force: Boolean,
        cursors: MutableMap<Int, String?> = this.cursors,
    ): PagingSnapshotPage<T> {
        var page = start
        while (true) {
            check(cursors.containsKey(page)) { "Search cursor was missing" }
            val after = cursors[page]
            val response =
                context.await(
                    "page:$page:after:$after:size:30",
                    PagingSnapshotPage.serializer(snapshot.serializer),
                    force,
                ) {
                    withTimeoutOrNull(20_000L) { request(page, after) }
                        ?: throw IOException("搜索请求超时")
                }
            cursors[page + 1] = response.nextCursor
            val next = response.nextKey
            if (response.items.any { mapItem(it) != null } || next == null) {
                val result = if (start == 1) response.copy(prevKey = null) else response
                if (start == 1 && cursors === this.cursors) snapshot.saveInBackground(result)
                return result
            }
            check(next > page) { "GitHub 返回了循环分页链接" }
            page = next
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ExploreResult>): Int? = null
}
