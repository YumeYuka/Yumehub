package moe.yumeyuka.yumehub.feature.worklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.repository.WorkListRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository

internal data class BranchPickerState(
    val branches: List<String> = emptyList(),
    val defaultBranch: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val cursor: String? = null,
    val failed: Boolean = false,
    val loaded: Boolean = false,
)

internal data class AssigneePickerState(
    val assignees: List<AssigneeOptionDto> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val cursor: String? = null,
    val failed: Boolean = false,
    val loaded: Boolean = false,
)

internal class CollaborationListViewModel(
    private val source: WorkListRepository,
    private val workspaces: WorkspacesRepository,
    private val owner: String,
    private val repo: String,
    private val kind: CollaborationKind,
    private val login: String,
) : ViewModel() {
    val scopes =
        if (kind == CollaborationKind.Issue) {
            listOf(
                CollaborationScopeFilter.All,
                CollaborationScopeFilter.CreatedByMe,
                CollaborationScopeFilter.AssignedToMe,
                CollaborationScopeFilter.Mentioned,
                CollaborationScopeFilter.Involved,
            )
        } else {
            listOf(
                CollaborationScopeFilter.All,
                CollaborationScopeFilter.CreatedByMe,
                CollaborationScopeFilter.AssignedToMe,
                CollaborationScopeFilter.Mentioned,
                CollaborationScopeFilter.ReviewRequested,
                CollaborationScopeFilter.Involved,
            )
        }

    private val mutableFilter =
        MutableStateFlow(CollaborationListFilter(state = CollaborationStateFilter.All))
    val filter = mutableFilter.asStateFlow()
    private val mutableOptions = MutableStateFlow(CollaborationFilterOptions())
    val filterOptions = mutableOptions.asStateFlow()
    private val mutableOptionsFailed = MutableStateFlow(false)
    val optionsFailed = mutableOptionsFailed.asStateFlow()
    private val mutableBranchPicker = MutableStateFlow(BranchPickerState())
    internal val branchPicker = mutableBranchPicker.asStateFlow()
    private val mutableAssigneePicker = MutableStateFlow(AssigneePickerState())
    internal val assigneePicker = mutableAssigneePicker.asStateFlow()
    private var optionsRequested = false
    private var branchRequest: Job? = null
    private var branchGeneration = 0L
    private var assigneeRequest: Job? = null
    private var assigneeGeneration = 0L
    private val supportsDraft = kind == CollaborationKind.PullRequest

    fun itemsFor(filter: CollaborationListFilter) =
        source.repositoryItems(login, owner, repo, kind, filter)

    suspend fun create(
        title: String,
        body: String,
        base: String,
        head: String,
        issueTypeId: String?,
        milestoneId: String?,
        labelIds: List<String>,
        assigneeIds: List<String>,
        issueTemplate: String?,
    ): Int =
        if (kind == CollaborationKind.Issue) {
            source.createIssue(
                login,
                owner,
                repo,
                title,
                body,
                issueTypeId,
                milestoneId,
                labelIds,
                assigneeIds,
                issueTemplate,
            )
        } else {
            source.createPullRequest(login, owner, repo, base, head, title, body)
        }

    fun searchBranches(query: String) {
        val normalized = query.trim()
        val state = mutableBranchPicker.value
        if (normalized == state.query && (state.loading || state.loaded && !state.failed)) {
            return
        }
        loadBranches(normalized)
    }

    fun loadMoreBranches() {
        val state = mutableBranchPicker.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        loadBranches(state.query, state.cursor ?: return)
    }

    fun retryBranches() {
        val state = mutableBranchPicker.value
        if (state.branches.isNotEmpty() && state.hasMore) loadMoreBranches()
        else loadBranches(state.query)
    }

    fun searchAssignees(query: String) {
        val normalized = query.trim()
        val state = mutableAssigneePicker.value
        if (normalized == state.query && (state.loading || state.loaded && !state.failed)) return
        loadAssignees(normalized)
    }

    fun loadMoreAssignees() {
        val state = mutableAssigneePicker.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        loadAssignees(state.query, state.cursor ?: return)
    }

    fun retryAssignees() {
        val state = mutableAssigneePicker.value
        if (state.assignees.isNotEmpty() && state.hasMore) loadMoreAssignees()
        else loadAssignees(state.query)
    }

    init {
        // 预取筛选选项，避免菜单展开后条目数量变化导致级联菜单状态不稳定。
        loadFilterOptions()
    }

    fun retryFilterOptions() {
        optionsRequested = false
        loadFilterOptions()
    }

    fun loadFilterOptions() {
        if (optionsRequested) return
        optionsRequested = true
        viewModelScope.launch {
            try {
                mutableOptions.value = source.filterOptions(login, owner, repo)
                mutableOptionsFailed.value = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the collaboration list usable and expose a retry for filter options.
                optionsRequested = false
                mutableOptionsFailed.value = true
            }
        }
    }

    private fun loadBranches(query: String, after: String? = null) {
        branchRequest?.cancel()
        val generation = ++branchGeneration
        val previous = if (after == null) emptyList() else mutableBranchPicker.value.branches
        mutableBranchPicker.value =
            if (after == null) {
                BranchPickerState(query = query, loading = true)
            } else {
                mutableBranchPicker.value.copy(loadingMore = true, failed = false)
            }
        branchRequest = viewModelScope.launch {
            try {
                if (after == null && query.isNotEmpty()) delay(250)
                val page = workspaces.loadBranches(login, owner, repo, after, query)
                if (generation != branchGeneration) return@launch
                val defaultBranch = page.defaultBranch?.removePrefix("refs/heads/")
                val matchingDefaultBranch =
                    defaultBranch
                        ?.takeIf { query.isEmpty() || it.contains(query, ignoreCase = true) }
                        ?.let { "refs/heads/$it" }
                mutableBranchPicker.value =
                    BranchPickerState(
                        branches =
                            (listOfNotNull(matchingDefaultBranch) + previous + page.branches)
                                .distinct(),
                        defaultBranch = defaultBranch,
                        query = query,
                        hasMore = page.hasNextPage,
                        cursor = page.endCursor,
                        loaded = true,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != branchGeneration) return@launch
                mutableBranchPicker.value =
                    if (after == null) {
                        BranchPickerState(query = query, failed = true)
                    } else {
                        mutableBranchPicker.value.copy(loadingMore = false, failed = true)
                    }
            }
        }
    }

    private fun loadAssignees(query: String, after: String? = null) {
        assigneeRequest?.cancel()
        val generation = ++assigneeGeneration
        val previous = if (after == null) emptyList() else mutableAssigneePicker.value.assignees
        mutableAssigneePicker.value =
            if (after == null) {
                AssigneePickerState(query = query, loading = true)
            } else {
                mutableAssigneePicker.value.copy(loadingMore = true, failed = false)
            }
        assigneeRequest = viewModelScope.launch {
            try {
                if (after == null && query.isNotEmpty()) delay(250)
                val page = source.searchAssignableUsers(login, owner, repo, query, after)
                if (generation != assigneeGeneration) return@launch
                mutableAssigneePicker.value =
                    AssigneePickerState(
                        assignees = (previous + page.items).distinctBy(AssigneeOptionDto::id),
                        query = query,
                        hasMore = page.nextCursor != null,
                        cursor = page.nextCursor,
                        loaded = true,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != assigneeGeneration) return@launch
                mutableAssigneePicker.value =
                    if (after == null) {
                        AssigneePickerState(query = query, failed = true)
                    } else {
                        mutableAssigneePicker.value.copy(
                            loadingMore = false,
                            failed = true,
                        )
                    }
            }
        }
    }

    fun selectFilter(filter: CollaborationListFilter) {
        if (
            filter.scope in scopes && (supportsDraft || !filter.draftOnly) && !filter.unansweredOnly
        ) {
            mutableFilter.value = filter.copy(searchQuery = mutableFilter.value.searchQuery)
        }
    }

    fun setQuery(query: String) {
        mutableFilter.value = mutableFilter.value.copy(searchQuery = query.trim())
    }
}
