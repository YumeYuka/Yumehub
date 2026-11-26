package moe.yumeyuka.yumehub.feature.pullrequestcommits

import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import kotlinx.coroutines.flow.flowOf
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

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
    fun commits(head: String?) = if (head != null) detail.commits(head) else flowOf(PagingData.empty<RepositoryActivityEntry>())

    fun revalidation(head: String) = detail.revalidation(detail.commitsPagingKey(head))

    fun retryRevalidation(head: String) = detail.retryRevalidation(detail.commitsPagingKey(head))

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
        detail.refresh(force = true)
    }
}
