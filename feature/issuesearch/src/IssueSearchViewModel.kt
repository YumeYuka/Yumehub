package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.lifecycle.ViewModel
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.data.repository.WorkListRepository

internal data class WorkItem(val item: CollaborationItemDto, val target: WorkTarget)

internal class IssueSearchViewModel(
    private val source: WorkListRepository,
    private val login: String,
    val kind: CollaborationKind,
) : ViewModel() {
    val scopes = buildList {
        add(CollaborationScopeFilter.All)
        add(CollaborationScopeFilter.CreatedByMe)
        add(CollaborationScopeFilter.AssignedToMe)
        add(CollaborationScopeFilter.Mentioned)
        if (kind == CollaborationKind.PullRequest) add(CollaborationScopeFilter.ReviewRequested)
    }
    private val mutableFilter =
        MutableStateFlow(CollaborationListFilter(state = CollaborationStateFilter.All))
    val filter = mutableFilter.asStateFlow()

    fun itemsFor(filter: CollaborationListFilter) =
        source.personalItems(login, kind, filter).map { page ->
            page
                .filter { it.workTarget()?.kind == kind }
                .map { WorkItem(it, requireNotNull(it.workTarget())) }
        }

    fun selectFilter(filter: CollaborationListFilter) {
        if (filter.scope in scopes) mutableFilter.value = filter.copy(searchQuery = mutableFilter.value.searchQuery)
    }

    fun setQuery(query: String) {
        mutableFilter.value = mutableFilter.value.copy(searchQuery = query.trim())
    }
}
