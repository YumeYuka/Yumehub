package moe.yumeyuka.yumehub.feature.worklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.dto.WorkCreationDraft
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.cache.workListCacheTag
import moe.yumeyuka.yumehub.data.repository.WorkListRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore

internal data class WorkCreationState(
    val draft: WorkCreationDraft = WorkCreationDraft(),
    val formRevision: Long = 0L,
    val draftRestored: Boolean = false,
    val visible: Boolean = false,
    val submitting: Boolean = false,
    val failed: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
    val errorMessage: String? = null,
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
    private val draftStore: CommentDraftStore,
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
    val created = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private var submittingHere = false
    private var navigateOnSuccess = false
    private var initialized = false

    fun initializeCreation(visible: Boolean) {
        if (initialized) return
        initialized = true
        setCreationVisible(visible)
    }

    fun itemsFor(filter: CollaborationListFilter) =
        source.repositoryItems(login, owner, repo, kind, filter)

    fun card(item: moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto) = source.card(login, item)

    fun revalidationFor(filter: CollaborationListFilter) =
        source.revalidation(login, source.repositoryItemsKey(owner, repo, kind, filter))

    fun retryRevalidation(filter: CollaborationListFilter) =
        source.retryRevalidation(login, source.repositoryItemsKey(owner, repo, kind, filter))

    fun setCreationVisible(visible: Boolean) {
        if (!visible) navigateOnSuccess = false
        mutableCreationState.update { it.copy(visible = visible) }
        if (visible) loadFilterOptions()
        if (visible && !mutableCreationState.value.draftRestored) {
            viewModelScope.launch {
                try {
                    val encoded = draftStore.loadOrNull(login, createDraftKey)
                    val restored = if (encoded != null) Json.decodeFromString<WorkCreationDraft>(encoded) else WorkCreationDraft(
                        title = draftStore.load(login, "$createDraftKey/title"), body = draftStore.load(login, "$createDraftKey/body"),
                    )
                    mutableCreationState.update { if (it.draftRestored) it else it.copy(draft = restored, draftRestored = true) }
                } catch (error: kotlinx.serialization.SerializationException) {
                    mutableCreationState.update { it.copy(failed = true) }
                }
            }
        }
        ensureCreationObserver()
    }

    fun updateCreationDraft(draft: WorkCreationDraft) {
        val current = mutableCreationState.value
        if (!current.draftRestored || current.submitting || current.uncertain || current.draft == draft) return
        mutableCreationState.value = current.copy(draft = draft, failed = false, errorMessage = null)
        draftStore.saveEncoded(login, createDraftKey) { Json.encodeToString(draft) }
    }

    fun submitCreation(draft: WorkCreationDraft) {
        val current = mutableCreationState.value
        if (!current.draftRestored || current.submitting || current.uncertain || draft.title.isBlank()) return
        submittingHere = true
        navigateOnSuccess = current.visible
        mutableCreationState.value = current.copy(draft = draft, submitting = true, failed = false, errorMessage = null)
        viewModelScope.launch {
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Json.encodeToString(draft) }
            val store = draftStore
            val account = login
            val draftKey = createDraftKey
            if (!store.saveConfirmed(account, draftKey, encoded)) {
                submittingHere = false
                mutableCreationState.update { it.copy(submitting = false, failed = true) }
                return@launch
            }
            val coord = source.mutations(login)
            ensureCreationObserver(coord)
            val tags =
                setOf(
                    workListCacheTag(owner, repo),
                    repositoryCacheTag(owner, repo),
                )
            val work = source
            val owner = this@CollaborationListViewModel.owner
            val name = repo
            val kind = this@CollaborationListViewModel.kind
            val receipt =
                coord.submitCommand(
                    key = creationKey,
                    tags = tags,
                    onConfirmed = {
                        store.removeIfUnchanged(account, draftKey, encoded)
                        store.removeIfUnchanged(account, "$draftKey/title", draft.title)
                        store.removeIfUnchanged(account, "$draftKey/body", draft.body)
                    },
                    send = { work.create(account, owner, name, kind, draft) },
                    readBack = { null },
                )
            val command = receipt?.await() ?: coord.currentCommand(creationKey)
            submittingHere = false
            val number = command.result
            if (receipt != null && command.phase == WritePhase.IDLE && number != null) {
                val navigate = navigateOnSuccess && mutableCreationState.value.visible
                mutableCreationState.value = WorkCreationState(draftRestored = true, formRevision = mutableCreationState.value.formRevision + 1,
                    visible = mutableCreationState.value.visible && !navigate)
                if (navigate) created.tryEmit(number)
            } else {
                mutableCreationState.update { it.copy(submitting = command.phase == WritePhase.PENDING,
                    uncertain = command.phase == WritePhase.UNCERTAIN, uncertainOperationId = command.submissionId,
                    failed = command.phase != WritePhase.PENDING, errorMessage = command.message) }
            }
        }
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
        }
    }

    private fun ensureCreationObserver(existing: moe.yumeyuka.yumehub.data.cache.MutationCoordinator? = null) {
        if (creationObserverStarted) return
        creationObserverStarted = true
        viewModelScope.launch {
            val coord = existing ?: source.mutations(login)
            coord.observeCommand(creationKey).collect { cmd ->
                if (!submittingHere) mutableCreationState.update { state ->
                    state.copy(submitting = cmd.phase == WritePhase.PENDING, uncertain = cmd.phase == WritePhase.UNCERTAIN,
                        uncertainOperationId = cmd.submissionId.takeIf { cmd.phase == WritePhase.UNCERTAIN },
                        failed = state.failed && !state.uncertain,
                        errorMessage = if (state.uncertain) null else state.errorMessage)
                }
            }
        }
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
