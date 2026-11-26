package moe.yumeyuka.yumehub.feature.commitdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.repository.CommitRepository
import moe.yumeyuka.yumehub.data.repository.GithubCommitDetail
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent

internal class CommitSource<T : Any>(
    val key: String,
    val pages: Flow<PagingData<T>>,
    val revalidation: StateFlow<RevalidationState>,
    val retryRevalidation: () -> Unit,
)

/** A full SHA names all three resources even if the author/message lookup is still pending. */
internal class CommitRevision(
    val oid: String,
    private val repository: CommitRepository,
    private val account: String,
    private val route: CommitDetailRoute,
    parent: CoroutineScope,
    private val generation: Int,
) {
    private val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    fun close() = scope.cancel()

    val files: CommitSource<PullRequestFileDto> by lazy {
        source(
            repository.filesKey(route.owner, route.name, oid),
            repository.files(account, route.owner, route.name, oid),
        )
    }
    val parents by lazy { links(parents = true) }
    val pullRequests by lazy { links(parents = false) }

    private fun links(parents: Boolean): CommitSource<RepositoryActivityEntry> =
        source(
            repository.linksKey(route.owner, route.name, oid, parents),
            repository.links(account, route.owner, route.name, oid, parents),
        )

    private fun <T : Any> source(key: String, pages: Flow<PagingData<T>>) =
        CommitSource(
            key = "$account:$generation:$key",
            pages = pages.cachedIn(scope),
            revalidation = repository.revalidation(account, key),
            retryRevalidation = { repository.retryRevalidation(account, key) },
        )
}

internal data class CommitDetailState(
    val detail: GithubCommitDetail? = null,
    val revision: CommitRevision? = null,
    val failed: Boolean = false,
)

internal class CommitDetailViewModel(
    private val repository: CommitRepository,
    private val route: CommitDetailRoute,
    private val account: String,
) : ViewModel() {
    private var generation = 0

    private fun revision(oid: String) =
        CommitRevision(oid, repository, account, route, viewModelScope, generation++)

    private val mutableState =
        MutableStateFlow(
            CommitDetailState(
                revision = route.oid.takeIf { it.length == 40 }?.lowercase()?.let(::revision),
            ),
        )
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(failed = false) }
            try {
                val detail = repository.load(account, route.owner, route.name, route.oid)
                val previous = mutableState.value.revision
                val current = previous?.takeIf { it.oid == detail.oid } ?: revision(detail.oid)
                if (current !== previous) previous?.close()
                mutableState.value = CommitDetailState(detail, current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error.invalidatesCachedContent()) {
                    mutableState.value.revision?.close()
                    mutableState.value = CommitDetailState(failed = true)
                } else {
                    mutableState.update { it.copy(failed = true) }
                }
            }
        }
    }
}
