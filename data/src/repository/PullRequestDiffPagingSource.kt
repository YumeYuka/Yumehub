package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto

/** 页码只用于现有 Paging registry；请求边界始终来自服务端 cursor。 */
internal class PullRequestDiffPagingSource(
    private val context: PagingContext,
    private val requestPage: suspend (String?) -> GithubDiffPage,
) : PagingSource<Int, PullRequestFileDto>() {
    private val loads = Mutex()
    private val cursors = mutableMapOf<Int, String?>(1 to null)
    private val paths = mutableMapOf<String, Int>()
    private val identities = mutableMapOf<String, Int>()
    private var revision: Pair<String, String>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PullRequestFileDto> =
        loads.withLock {
            try {
                context.ensureCurrent()
                val pageNumber = params.key ?: 1
                if (pageNumber !in cursors || pageNumber > MaxDiffPages) {
                    throw GithubDiffException()
                }
                val page = requestPage(cursors[pageNumber])
                currentCoroutineContext().ensureActive()
                context.ensureCurrent()
                val currentRevision = page.baseCommitOid to page.headCommitOid
                if (revision != null && revision != currentRevision) {
                    throw WorkDetailReviewChangedException()
                }
                val next = page.nextCursor
                if (
                    next != null &&
                        (pageNumber >= MaxDiffPages ||
                            cursors.any { (number, cursor) ->
                                number != pageNumber + 1 && cursor == next
                            })
                )
                    throw GithubDiffException()
                if (pageNumber + 1 in cursors && cursors[pageNumber + 1] != next) {
                    throw GithubDiffException()
                }
                page.files.forEach { file ->
                    val previousPage = paths[file.filename]
                    val identity = file.structuredDiff?.id ?: throw GithubDiffException()
                    val identityPage = identities[identity]
                    if (
                        (previousPage != null && previousPage != pageNumber) ||
                            (identityPage != null && identityPage != pageNumber)
                    ) {
                        throw GithubDiffException()
                    }
                }
                revision = currentRevision
                page.files.forEach { file ->
                    paths[file.filename] = pageNumber
                    file.structuredDiff?.id?.let { identities[it] = pageNumber }
                }
                if (next != null) cursors[pageNumber + 1] = next
                LoadResult.Page(
                    data = page.files,
                    prevKey = (pageNumber - 1).takeIf { it >= 1 },
                    nextKey = if (next == null) null else pageNumber + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<Int, PullRequestFileDto>): Int? = null
}
