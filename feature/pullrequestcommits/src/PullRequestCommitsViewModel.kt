package moe.yumeyuka.yumehub.feature.pullrequestcommits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

internal data class PullRequestCommitEntry(val head: String, val commit: RepositoryActivityEntry) {
    val id: String
        get() = commit.id
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class PullRequestCommitsViewModel(
    repository: WorkDetailRepository,
    route: PullRequestCommitsRoute,
    account: String,
) : ViewModel() {
    private val detail =
        repository.detail(
            account,
            route.owner,
            route.name,
            CollaborationKind.PullRequest,
            route.number,
        )
    val state = detail.state
    val commits =
        state
            .map { it.data?.head?.sha?.takeIf(String::isNotBlank) }
            .distinctUntilChanged()
            .flatMapLatest { head ->
                if (head == null) flowOf(PagingData.empty<PullRequestCommitEntry>())
                else
                    detail.commits(head).map { page ->
                        page.map { PullRequestCommitEntry(head, it) }
                    }
            }
            .cachedIn(viewModelScope)
    private var refreshJob: Job? = null
    private var recoveredHead: String? = null

    fun recoverRevision(head: String?) {
        if (head == null || recoveredHead == head || state.value.data?.head?.sha != head) return
        recoveredHead = head
        refresh()
    }

    fun canOpenCommit(head: String?, oid: String): Boolean =
        head != null && state.value.data?.head?.sha == head && oid.isNotBlank()

    init {
        detail.refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            detail.refresh(force = true).join()
            val snapshot = state.value
            if (snapshot.data != null && snapshot.error == null) detail.refreshEntries()
        }
    }
}
