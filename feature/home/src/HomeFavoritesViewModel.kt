package moe.yumeyuka.yumehub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.repository.GraphQlSearchRepository
import moe.yumeyuka.yumehub.data.repository.HomeFavoritesRepository
import moe.yumeyuka.yumehub.data.repository.MyRepositoriesRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryFavoritesLimitException
import moe.yumeyuka.yumehub.data.repository.RepositoryOverviewRepository
import moe.yumeyuka.yumehub.data.repository.SearchSlice

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
    private var loadDraftJob: Job? = null

    init {
        viewModelScope.launch { resource.state.collect { state.value = it.toUiState() } }
    }

    fun refresh() {
        repository.refresh(account)
    }

    fun retry() {
        repository.refresh(account, force = true)
    }

    fun resetEditor() {
        if (edit.value.saving) return
        loadDraftJob?.cancel()
        edit.value = HomeFavoritesEditState()
    }

    fun loadDraft() {
        val current = edit.value
        if (current.draft != null || current.loading || current.saving) return
        edit.value = current.copy(loading = true, error = null)
        loadDraftJob = viewModelScope.launch {
            try {
                val favorites = overview.dashboardFavorites(account)
                edit.value = HomeFavoritesEditState(draft = HomeFavoritesDraft(favorites))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
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
        val draft = current.draft
        if (draft == null || !draft.changed) {
            edit.value = current.copy(saved = true, error = null)
            return
        }
        edit.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                overview.saveDashboardFavorites(account, draft.originalIds, draft.selected)
                edit.value = current.copy(saved = true, error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure =
                    if (error is RepositoryFavoritesLimitException) HomeFavoritesEditError.Limit
                    else HomeFavoritesEditError.Save
                edit.value = current.copy(error = failure)
            }
        }
    }

    private fun CachedDataState<List<RepositoryDto>>.toUiState(): HomeFavoritesState {
        return data?.let { HomeFavoritesState.Content(it, error != null) }
            ?: if (error != null) HomeFavoritesState.Error else HomeFavoritesState.Loading
    }
}
