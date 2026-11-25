package moe.yumeyuka.yumehub.feature.worklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.WorkListRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore

internal data class WorkCreationState(
    val title: String = "",
    val body: String = "",
    val base: String = "",
    val head: String = "",
    val issueTypeId: String? = null,
    val milestoneId: String? = null,
    val labelIds: List<String> = emptyList(),
    val assignees: List<AssigneeOptionDto> = emptyList(),
    val issueTemplate: String? = null,
    val draftRestored: Boolean = false,
    val visible: Boolean = false,
    val submitting: Boolean = false,
    val failed: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
    val errorMessage: String? = null,
    val createdNumber: Int? = null,
)

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
    private val draftStore: CommentDraftStore? = null,
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
    private val createDraftKey =
        "create-work/${kind.name.lowercase(Locale.ROOT)}/${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}"
    private val creationKey =
        MutationKey(
            MutationKind.WORK_CREATE,
            "${kind.name.lowercase(Locale.ROOT)}:${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}",
            Int.serializer(),
        )
    private val mutableCreationState = MutableStateFlow(WorkCreationState())
    internal val creationState = mutableCreationState.asStateFlow()
    private var creationObserverStarted = false

    fun itemsFor(filter: CollaborationListFilter) =
        source.repositoryItems(login, owner, repo, kind, filter)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val revalidation: StateFlow<RevalidationState> =
        mutableFilter
            .flatMapLatest { f ->
                source.revalidation(login, source.repositoryItemsKey(owner, repo, kind, f))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        source.retryRevalidation(login, source.repositoryItemsKey(owner, repo, kind, mutableFilter.value))
    }

    fun setCreationVisible(visible: Boolean) {
        mutableCreationState.update { it.copy(visible = visible) }
        if (visible && !mutableCreationState.value.draftRestored) {
            viewModelScope.launch {
                val restoredBody = draftStore?.loadOrNull(login, "$createDraftKey/body").orEmpty()
                val restoredTitle = draftStore?.loadOrNull(login, "$createDraftKey/title").orEmpty()
                mutableCreationState.update { current ->
                    if (current.draftRestored) current
                    else
                        current.copy(
                            title = current.title.ifBlank { restoredTitle },
                            body = current.body.ifBlank { restoredBody },
                            draftRestored = true,
                        )
                }
            }
        }
        ensureCreationObserver()
    }

    fun updateCreationDraft(
        title: String? = null,
        body: String? = null,
        base: String? = null,
        head: String? = null,
        issueTypeId: String? = mutableCreationState.value.issueTypeId,
        milestoneId: String? = mutableCreationState.value.milestoneId,
        labelIds: List<String>? = null,
        assignees: List<AssigneeOptionDto>? = null,
        issueTemplate: String? = mutableCreationState.value.issueTemplate,
    ) {
        mutableCreationState.update {
            it.copy(
                title = title ?: it.title,
                body = body ?: it.body,
                base = base ?: it.base,
                head = head ?: it.head,
                issueTypeId = issueTypeId,
                milestoneId = milestoneId,
                labelIds = labelIds ?: it.labelIds,
                assignees = assignees ?: it.assignees,
                issueTemplate = issueTemplate,
                failed = false,
                errorMessage = null,
            )
        }
        if (title != null) draftStore?.save(login, "$createDraftKey/title", title)
        if (body != null) draftStore?.save(login, "$createDraftKey/body", body)
    }

    fun submitCreation(
        title: String,
        body: String,
        base: String,
        head: String,
        issueTypeId: String?,
        milestoneId: String?,
        labelIds: List<String>,
        assigneeIds: List<String>,
        issueTemplate: String?,
    ) {
        val current = mutableCreationState.value
        if (current.submitting || current.uncertain) return
        val trimmedTitle = title.trim()
        val trimmedBase = base.trim()
        val trimmedHead = head.trim()
        mutableCreationState.update {
            it.copy(
                title = title,
                body = body,
                base = base,
                head = head,
                issueTypeId = issueTypeId,
                milestoneId = milestoneId,
                labelIds = labelIds,
                issueTemplate = issueTemplate,
                submitting = true,
                failed = false,
                errorMessage = null,
                createdNumber = null,
            )
        }
        viewModelScope.launch {
            val savedTitle = draftStore?.saveConfirmed(login, "$createDraftKey/title", title) ?: true
            val savedBody = draftStore?.saveConfirmed(login, "$createDraftKey/body", body) ?: true
            if (!savedTitle || !savedBody) {
                mutableCreationState.update { it.copy(submitting = false, failed = true) }
                return@launch
            }
            val coord = source.mutations(login)
            ensureCreationObserver(coord)
            val tags =
                setOf(
                    CacheTag(DataDomain.Work, null),
                    repositoryCacheTag(owner, repo),
                )
            val accepted =
                coord.submitCommand(
                    key = creationKey,
                    tags = tags,
                    send = {
                        create(
                            trimmedTitle,
                            body,
                            trimmedBase,
                            trimmedHead,
                            issueTypeId,
                            milestoneId,
                            labelIds,
                            assigneeIds,
                            issueTemplate,
                        )
                    },
                    readBack = { null },
                )
            if (!accepted) {
                val opId =
                    coord.pending.value.firstOrNull {
                        it.kind == creationKey.kind && it.target == creationKey.target
                    }?.operationId
                mutableCreationState.update {
                    it.copy(
                        submitting = false,
                        failed = true,
                        uncertain = true,
                        uncertainOperationId = opId,
                    )
                }
            }
        }
    }

    fun consumeCreatedNumber() {
        mutableCreationState.update { it.copy(createdNumber = null) }
    }

    fun acknowledgeCreationUncertain() {
        val opId = mutableCreationState.value.uncertainOperationId
        viewModelScope.launch {
            val coord = source.mutations(login)
            val id =
                opId
                    ?: coord.pending.value
                        .firstOrNull { it.kind == creationKey.kind && it.target == creationKey.target }
                        ?.operationId
            if (id != null) coord.acknowledge(id)
            mutableCreationState.update {
                it.copy(uncertain = false, uncertainOperationId = null, failed = false, errorMessage = null)
            }
        }
    }

    private fun ensureCreationObserver(existing: moe.yumeyuka.yumehub.data.cache.MutationCoordinator? = null) {
        if (creationObserverStarted) return
        creationObserverStarted = true
        viewModelScope.launch {
            val coord = existing ?: source.mutations(login)
            coord.observeCommand(creationKey).collect { cmd ->
                when (cmd.phase) {
                    WritePhase.PENDING ->
                        mutableCreationState.update { it.copy(submitting = true, failed = false) }
                    WritePhase.IDLE -> {
                        val number = cmd.result
                        if (number != null) {
                            draftStore?.remove(login, "$createDraftKey/title")
                            draftStore?.remove(login, "$createDraftKey/body")
                            val showCreated = mutableCreationState.value.visible
                            mutableCreationState.value =
                                WorkCreationState(
                                    draftRestored = true,
                                    visible = false,
                                    createdNumber = number.takeIf { showCreated },
                                )
                        } else {
                            mutableCreationState.update {
                                it.copy(submitting = false, uncertain = false, uncertainOperationId = null)
                            }
                        }
                    }
                    WritePhase.REJECTED ->
                        mutableCreationState.update {
                            it.copy(
                                submitting = false,
                                failed = true,
                                uncertain = false,
                                errorMessage = cmd.message,
                            )
                        }
                    WritePhase.UNCERTAIN -> {
                        val opId =
                            coord.pending.value.firstOrNull {
                                it.kind == creationKey.kind && it.target == creationKey.target
                            }?.operationId
                        mutableCreationState.update {
                            it.copy(
                                submitting = false,
                                failed = true,
                                uncertain = true,
                                uncertainOperationId = opId,
                                errorMessage = cmd.message,
                            )
                        }
                    }
                }
            }
        }
    }

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
