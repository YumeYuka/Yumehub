package moe.yumeyuka.yumehub.feature.pullrequestdiff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

@OptIn(ExperimentalCoroutinesApi::class)
internal class PullRequestDiffViewModel(
    repository: WorkDetailRepository,
    route: PullRequestDiffRoute,
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
    val files =
        state
            .map { it.data?.head?.sha?.takeIf(String::isNotBlank) }
            .distinctUntilChanged()
            .flatMapLatest { head ->
                if (head == null) flowOf(PagingData.empty<PullRequestFileDto>())
                else detail.files(head)
            }
            .cachedIn(viewModelScope)
    val revalidation: StateFlow<RevalidationState> =
        state
            .map { it.data?.head?.sha?.takeIf(String::isNotBlank) }
            .distinctUntilChanged()
            .flatMapLatest { head ->
                if (head == null) flowOf(RevalidationState.Idle)
                else detail.revalidation(detail.filesPagingKey(head))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        val head = state.value.data?.head?.sha?.takeIf(String::isNotBlank) ?: return
        detail.retryRevalidation(detail.filesPagingKey(head))
    }
    private var refreshJob: Job? = null
    private var recoveredHead: String? = null

    fun recoverRevision(head: String?) {
        if (head == null || recoveredHead == head || state.value.data?.head?.sha != head) return
        recoveredHead = head
        refresh()
    }

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
