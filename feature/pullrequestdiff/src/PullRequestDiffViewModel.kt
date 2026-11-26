package moe.yumeyuka.yumehub.feature.pullrequestdiff

import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import kotlinx.coroutines.flow.flowOf
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

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
    fun files(head: String?) = if (head != null) detail.files(head) else flowOf(PagingData.empty<PullRequestFileDto>())

    fun revalidation(head: String) = detail.revalidation(detail.filesPagingKey(head))

    fun retryRevalidation(head: String) = detail.retryRevalidation(detail.filesPagingKey(head))

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
        detail.refresh(force = true)
    }
}
