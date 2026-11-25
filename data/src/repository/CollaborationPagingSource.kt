package moe.yumeyuka.yumehub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import retrofit2.Response

fun <T : Any, R : Any> collaborationPager(
    requestPage: suspend (Int, Int) -> Response<List<T>>,
    mapItem: (T) -> R?,
    snapshot: PagingSnapshot<T>? = null,
    repository: PagingRepository? = null,
    itemKey: ((T) -> Any?)? = null,
): Flow<PagingData<R>> {
    if (repository != null && snapshot != null) {
        return repository.pages(snapshot.account, snapshot.key, tags = snapshot.tags) { context ->
            CollaborationPagingSource(requestPage, mapItem, snapshot.bind(context), itemKey)
        }
    }
    return Pager(defaultPagingConfig()) {
            CollaborationPagingSource(requestPage, mapItem, snapshot, itemKey)
        }
        .flow
}

private class CollaborationPagingSource<T : Any, R : Any>(
    private val requestPage: suspend (Int, Int) -> Response<List<T>>,
    private val mapItem: (T) -> R?,
    private val snapshot: PagingSnapshot<T>?,
    private val itemKey: ((T) -> Any?)?,
) : PagingSource<Int, R>() {
    private val seen = mutableMapOf<Any, Int>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, R> {
        return try {
            val startPage = params.key ?: 1
            if (params is LoadParams.Refresh && startPage == 1) {
                val initial = snapshot?.initialPage(CollaborationPageSize)
                if (initial != null) {
                    if (!initial.isFresh()) {
                        snapshot.context?.revalidate(this) {
                            val updated =
                                loadNetwork(1, backwards = false, force = true, saveFirst = false)
                            snapshot.save(updated)
                            initial != updated.copy(updatedAt = initial.updatedAt)
                        }
                    }
                    return pageResult(initial)
                }
            }
            val force = params !is LoadParams.Refresh || snapshot?.context?.forceRefresh != false
            pageResult(loadNetwork(startPage, params is LoadParams.Prepend, force))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LoadResult.Error(error)
        }
    }

    private suspend fun loadNetwork(
        startPage: Int,
        backwards: Boolean,
        force: Boolean,
        saveFirst: Boolean = true,
    ): PagingSnapshotPage<T> {
        var page = startPage
        while (true) {
            currentCoroutineContext().ensureActive()
            val context = snapshot?.context
            val result =
                if (context == null) request(page)
                else
                    context.await(
                        key = "page:$page:size:$CollaborationPageSize",
                        serializer = PagingSnapshotPage.serializer(snapshot.serializer),
                        force = force,
                    ) {
                        request(page)
                    }
            context?.ensureCurrent()
            val following = if (backwards) result.prevKey else result.nextKey
            if (result.items.any { mapItem(it) != null } || following == null) {
                // 保存跳过过滤空页之后的真实边界，不能截断后再猜 nextKey。
                val first =
                    if (startPage == 1 && !backwards) result.copy(prevKey = null) else result
                if (startPage == 1 && !backwards && saveFirst) snapshot?.saveInBackground(first)
                return first
            }
            if ((!backwards && following <= page) || (backwards && following >= page)) {
                throw IllegalStateException("GitHub 返回了循环分页链接")
            }
            page = following
        }
    }

    private suspend fun request(page: Int): PagingSnapshotPage<T> {
        val response = requestPage(page, CollaborationPageSize)
        if (!response.isSuccessful) throw HttpException(response)
        currentCoroutineContext().ensureActive()
        return PagingSnapshotPage(
            items = response.body().orEmpty(),
            prevKey = (page - 1).takeIf { it >= 1 },
            nextKey = response.headers().pagingRelation("next"),
            pageSize = CollaborationPageSize,
            updatedAt = System.currentTimeMillis(),
            page = page,
        )
    }

    private fun pageResult(page: PagingSnapshotPage<T>): LoadResult.Page<Int, R> {
        val items =
            synchronized(seen) {
                val local = mutableSetOf<Any>()
                page.items.mapNotNull { item ->
                    val mapped = mapItem(item) ?: return@mapNotNull null
                    val key = itemKey?.invoke(item)
                    if (
                        key == null ||
                            (local.add(key) && seen.getOrPut(key) { page.page } == page.page)
                    ) {
                        mapped
                    } else null
                }
            }
        return LoadResult.Page(items, page.prevKey, page.nextKey)
    }

    override fun getRefreshKey(state: PagingState<Int, R>): Int? = null
}

/** 页码来自 Link，不依据过滤或去重后的列表长度。 */
fun Headers.pagingRelation(relation: String): Int? =
    values("Link")
        .asSequence()
        .flatMap { it.split(',').asSequence() }
        .firstOrNull { link ->
            Relation.find(link)?.groupValues?.get(1)?.split(' ')?.contains(relation) == true
        }
        ?.substringAfter('<')
        ?.substringBefore('>')
        ?.toHttpUrlOrNull()
        ?.queryParameter("page")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

private val Relation = Regex(""";\s*rel\s*=\s*"([^"]+)"""")

private const val CollaborationPageSize = 30
