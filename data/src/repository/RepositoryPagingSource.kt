package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage

class RepositoryPagingSource(
    private val repositories: MyRepositoriesRepository,
    private val account: String,
    private val pageSize: Int,
    private val snapshot: PagingSnapshot<RepositoryDto>? = null,
    private val type: String = "all",
    private val sort: String = "updated",
    private val query: String = "",
    private val language: String? = null,
) : PagingSource<Int, RepositoryDto>() {
    private val repositoryPages = mutableMapOf<Any, Int>()
    private var matchingRepositories: List<RepositoryDto>? = null
    private val cursors = mutableMapOf<Int, String?>(1 to null)
    private var session: GithubGraphQlSession? = null
    private val usesLocalSort = sort != "updated" || query.isNotBlank() || language != null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RepositoryDto> {
        val page = params.key ?: 1
        return try {
            val initial =
                if (params is LoadParams.Refresh && page == 1) snapshot?.initialPage(pageSize)
                else null
            if (initial != null) {
                recordCursor(initial)
                if (!initial.isFresh()) {
                    snapshot?.context?.revalidate(this) {
                        val updated = requestPage(1, force = true, record = false)
                        snapshot.save(updated)
                        usesLocalSort || initial != updated.copy(updatedAt = initial.updatedAt)
                    }
                }
            }
            val result =
                initial
                    ?: pageRead {
                        requestPage(
                            page,
                            force =
                                params !is LoadParams.Refresh ||
                                    snapshot?.context?.forceRefresh != false,
                        )
                    }
            currentCoroutineContext().ensureActive()
            val uniqueRepositories =
                synchronized(repositoryPages) {
                    val pageKeys = mutableSetOf<Any>()
                    result.items.filter { repository ->
                        val key =
                            repository.nodeId
                                ?: repository.id
                                ?: repository.fullName
                                    ?.takeIf { it.isNotBlank() }
                                    ?.lowercase(Locale.ROOT)
                        key == null ||
                            (pageKeys.add(key) && repositoryPages.getOrPut(key) { page } == page)
                    }
                }
            LoadResult.Page(
                data = uniqueRepositories,
                prevKey = if (params is LoadParams.Refresh) null else result.prevKey,
                nextKey = result.nextKey,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    private suspend fun requestPage(
        page: Int,
        force: Boolean,
        record: Boolean = true,
    ): PagingSnapshotPage<RepositoryDto> {
        val after =
            if (usesLocalSort) null
            else
                synchronized(cursors) {
                    if (page !in cursors || page !in 1..1000) throw GithubPageException()
                    cursors[page]
                }
        val load: suspend () -> PagingSnapshotPage<RepositoryDto> = {
            // 新筛选复用同一批原始页；显式刷新才穿透缓存。
            val refresh = force
            var hasMoreMatches = false
            val batch =
                if (usesLocalSort) {
                    val existing = matchingRepositories.takeIf { record && !(refresh && page == 1) }
                    val matches =
                        existing
                            ?: repositories
                                .matching(
                                    account,
                                    type,
                                    sort,
                                    query,
                                    language,
                                    // 首屏来自快照时本 Source 还没有 matches；追加不强刷整份原始列表。
                                    force = refresh && page == 1,
                                )
                                .also { if (record) matchingRepositories = it }
                    hasMoreMatches = page.toLong() * pageSize < matches.size
                    MyRepositoriesPage(matches.repositoryPage(page, pageSize), null)
                } else {
                    val activeSession =
                        session ?: repositories.session(account).also { session = it }
                    repositories.page(activeSession, account, type, after, refresh)
                }
            currentCoroutineContext().ensureActive()
            PagingSnapshotPage(
                items = batch.items,
                prevKey = (page - 1).takeIf { it >= 1 },
                nextKey =
                    (page + 1).takeIf {
                        if (usesLocalSort) hasMoreMatches else batch.nextCursor != null
                    },
                pageSize = pageSize,
                updatedAt = System.currentTimeMillis(),
                page = page,
                requestCursor = after,
                nextCursor = batch.nextCursor,
            )
        }
        val context = snapshot?.context
        val result =
            try {
                if (context == null) load()
                else
                    context.await(
                        key = "page:$page:size:$pageSize:after=${after.orEmpty()}",
                        serializer = PagingSnapshotPage.serializer(RepositoryDto.serializer()),
                        force = force,
                        loader = load,
                    )
            } catch (error: Exception) {
                snapshot?.discardDenied(error)
                throw error
            }
        if (record) recordCursor(result)
        if (page == 1 && record) snapshot?.saveInBackground(result)
        return result
    }

    private fun recordCursor(result: PagingSnapshotPage<RepositoryDto>) {
        if (usesLocalSort) return
        synchronized(cursors) {
            val page = result.page
            val next = result.nextCursor
            if (
                result.requestCursor != cursors[page] ||
                    (next == null) != (result.nextKey == null) ||
                    next != null &&
                        (page >= 1000 || cursors.any { it.key != page + 1 && it.value == next })
            )
                throw GithubPageException()
            if (page + 1 in cursors && cursors[page + 1] != next) throw GithubPageException()
            if (next != null) cursors[page + 1] = next
        }
    }

    override fun getRefreshKey(state: PagingState<Int, RepositoryDto>): Int? = null
}

internal fun <T> List<T>.repositoryPage(page: Int, pageSize: Int): List<T> {
    val start = ((page.toLong() - 1) * pageSize).coerceAtMost(size.toLong())
    val end = (start + pageSize).coerceAtMost(size.toLong())
    // 快照可能比本次排序结果存活更久，不能让 subList 把整份列表留在缓存中。
    return List((end - start).toInt()) { this[start.toInt() + it] }
}
