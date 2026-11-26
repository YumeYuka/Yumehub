package moe.yumeyuka.yumehub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.PendingMutationSummary
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.GraphQlSearchRepository
import moe.yumeyuka.yumehub.data.repository.HomeFavoritesRepository
import moe.yumeyuka.yumehub.data.repository.MyRepositoriesRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryFavoritesLimitException
import moe.yumeyuka.yumehub.data.repository.RepositoryOverviewRepository
import moe.yumeyuka.yumehub.data.repository.SearchSlice
import moe.yumeyuka.yumehub.data.repository.mergeDashboardFavorites

internal sealed interface HomeFavoritesState {
    data object Loading : HomeFavoritesState

    data class Content(val repositories: List<RepositoryDto>, val refreshFailed: Boolean = false) :
        HomeFavoritesState

    data object Error : HomeFavoritesState
}

internal data class HomeFavoritesDraft(
    val original: List<RepositoryDto>,
    val selected: List<RepositoryDto> = original,
) {
    val selectedIds = selected.mapNotNull { it.nodeId }.toSet()
    val originalIds = original.map { requireNotNull(it.nodeId) }
    val changed = selected.map { it.nodeId } != originalIds
}

internal enum class HomeFavoritesEditError {
    Load,
    Save,
    Limit,
}

internal data class HomeFavoritesEditState(
    val draft: HomeFavoritesDraft? = null,
    val searchDraft: HomeFavoritesDraft? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val accepted: Boolean = false,
    val error: HomeFavoritesEditError? = null,
)

internal class HomeFavoritesViewModel(
    private val repository: HomeFavoritesRepository,
    private val overview: RepositoryOverviewRepository,
    private val myRepositories: MyRepositoriesRepository,
    private val search: GraphQlSearchRepository,
    private val account: String,
) : ViewModel() {
    private val resource = repository.resource(account)
    private val state = MutableStateFlow(resource.state.value.toUiState())
    val uiState = state.asStateFlow()
    private val edit = MutableStateFlow(HomeFavoritesEditState())
    val editState = edit.asStateFlow()
    private val mutablePending = MutableStateFlow<List<PendingMutationSummary>>(emptyList())
    val pendingMutations: StateFlow<List<PendingMutationSummary>> = mutablePending.asStateFlow()
    private val mutableRecoveryFailed = MutableStateFlow(false)
    val recoveryFailed: StateFlow<Boolean> = mutableRecoveryFailed.asStateFlow()
    private var loadDraftJob: Job? = null
    private var coordinator: MutationCoordinator? = null
    private var lastSubmittedMetadata: List<RepositoryDto> = emptyList()
    private var closeOnAccept = false
    private val pinsKey =
        MutationKey(MutationKind.DASHBOARD_PINS, "viewer", ListSerializer(String.serializer()))

    init {
        viewModelScope.launch { resource.state.collect { state.value = it.toUiState() } }
        viewModelScope.launch { repository.observe(account).collect {} }
        viewModelScope.launch {
            val coord =
                try {
                    overview.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
            if (!overview.account.equals(account, ignoreCase = true)) return@launch
            coordinator = coord
            launch { coord.pending.collect { mutablePending.value = it } }
            launch { coord.recoveryFailed.collect { mutableRecoveryFailed.value = it } }
            launch {
                coord.observe(pinsKey).collect { write ->
                    if (!overview.account.equals(account, ignoreCase = true)) return@collect
                    state.value = resource.state.value.toUiState()
                    val currentEdit = edit.value
                    if (currentEdit.saving) {
                        when (write.phase) {
                            WritePhase.IDLE ->
                                edit.value = currentEdit.copy(saving = false, saved = true, error = null)
                            WritePhase.REJECTED,
                            WritePhase.UNCERTAIN ->
                                edit.value =
                                    currentEdit.copy(
                                        saving = false,
                                        error = HomeFavoritesEditError.Save,
                                    )
                            WritePhase.PENDING -> Unit
                        }
                    }
                }
            }
        }
    }

    fun acknowledgeMutation(operationId: String) {
        coordinator?.acknowledge(operationId)
    }

    fun retryMutationRecovery() {
        coordinator?.retryRecovery()
    }

    fun refresh() {
        repository.refresh(account)
    }

    fun retry() {
        repository.refresh(account, force = true)
    }

    fun resetEditor() {
        closeOnAccept = false
        if (edit.value.saving) return
        loadDraftJob?.cancel()
        edit.value = HomeFavoritesEditState()
    }

    fun dismissEditor() {
        closeOnAccept = false
        if (edit.value.accepted) edit.value = edit.value.copy(accepted = false)
        resetEditor()
    }

    fun loadDraft() {
        var current = edit.value
        if (current.saved && !current.accepted) {
            current = HomeFavoritesEditState()
            edit.value = current
        }
        if (current.draft != null || current.loading || current.saving) return
        edit.value = current.copy(loading = true, error = null)
        loadDraftJob = viewModelScope.launch {
            try {
                val coord = coordinator ?: overview.mutations(account).also { coordinator = it }
                val stamp = coord.beginRead(pinsKey)
                val pins = overview.dashboardPins(account)
                if (!overview.account.equals(account, ignoreCase = true)) return@launch
                coord.publishConfirmed(pinsKey, stamp, pins.ids)
                edit.value = HomeFavoritesEditState(draft = HomeFavoritesDraft(pins.repositories))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                edit.value = HomeFavoritesEditState(error = HomeFavoritesEditError.Load)
            }
        }
    }

    suspend fun candidates(query: String, after: String?): SearchSlice<RepositoryDto> {
        if (query.isBlank()) return myRepositories.pickerPage(account, after)
        val page = search.page(query, ExploreCategory.Repositories, after, account)
        return SearchSlice(page.repositories, page.nextCursor)
    }

    fun startSearch() {
        val current = edit.value
        if (!current.saving && !current.saved && current.draft != null) {
            edit.value = current.copy(searchDraft = current.draft, error = null)
        }
    }

    fun finishSearch(apply: Boolean) {
        val current = edit.value
        val selection = current.searchDraft ?: return
        edit.value = current.copy(draft = if (apply) selection else current.draft, searchDraft = null)
    }

    fun reorderFavorites(items: List<RepositoryDto>) {
        val current = edit.value
        val draft = current.draft ?: return
        if (current.saving || current.saved || current.searchDraft != null) return
        if (items.size != draft.selected.size || items.any { it.nodeId !in draft.selectedIds }) return
        edit.value = current.copy(draft = draft.copy(selected = items), error = null)
    }

    fun toggleFavorite(repository: RepositoryDto) {
        val current = edit.value
        val draft = current.searchDraft ?: current.draft ?: return
        val id = repository.nodeId ?: return
        if (current.saving || current.saved) return
        val selected =
            if (id in draft.selectedIds) draft.selected.filterNot { it.nodeId == id }
            else draft.selected + repository
        val updated = draft.copy(selected = selected)
        edit.value =
            if (current.searchDraft != null) current.copy(searchDraft = updated, error = null)
            else current.copy(draft = updated, error = null)
    }

    fun confirmEditor() {
        val current = edit.value
        if (current.saving || current.saved) return
        closeOnAccept = true
        val draft = current.draft
        if (draft == null || !draft.changed) {
            edit.value = current.copy(saved = true, accepted = closeOnAccept, error = null)
            return
        }
        lastSubmittedMetadata = draft.selected
        edit.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val selectedIds = draft.selected.mapNotNull { it.nodeId }
            if (selectedIds.size != draft.selected.size) {
                edit.value = edit.value.copy(saving = false, error = HomeFavoritesEditError.Save)
                return@launch
            }
            val coord =
                coordinator
                    ?: try {
                        overview.mutations(account).also { coordinator = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        edit.value = edit.value.copy(saving = false, error = HomeFavoritesEditError.Save)
                        return@launch
                    }
            val confirmed = coord.current(pinsKey).confirmed ?: draft.originalIds
            val desired =
                try {
                    mergeDashboardFavorites(confirmed, draft.originalIds, selectedIds)
                } catch (_: RepositoryFavoritesLimitException) {
                    edit.value = edit.value.copy(saving = false, error = HomeFavoritesEditError.Limit)
                    return@launch
                }
            val accepted =
                coord.submit(
                    pinsKey,
                    confirmed = confirmed,
                    desired = desired,
                    tags = setOf(CacheTag(DataDomain.Repositories, null)),
                    send = { overview.saveDashboardFavorites(account, draft.originalIds, draft.selected) },
                    readBack = { overview.dashboardFavoriteIds(account) },
                )
            if (accepted) {
                edit.value = edit.value.copy(accepted = closeOnAccept)
            } else {
                edit.value = edit.value.copy(saving = false, error = HomeFavoritesEditError.Save)
            }
        }
    }

    fun consumeAccepted() {
        if (edit.value.accepted) edit.value = edit.value.copy(accepted = false)
    }

    private fun CachedDataState<List<RepositoryDto>>.toUiState(): HomeFavoritesState {
        val base = data ?: return if (error != null) HomeFavoritesState.Error else HomeFavoritesState.Loading
        val visibleIds = coordinator?.current(pinsKey)?.visible
        val repositories =
            if (visibleIds == null) base
            else {
                val knownById = (base + lastSubmittedMetadata + edit.value.draft?.selected.orEmpty())
                    .mapNotNull { repo -> repo.nodeId?.let { it to repo } }
                    .toMap()
                visibleIds.mapNotNull { knownById[it] }
            }
        return HomeFavoritesState.Content(repositories, error != null)
    }
}
