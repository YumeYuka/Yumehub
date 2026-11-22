package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.UserRepositoryList

internal data class RepositoryListsState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val lists: List<UserRepositoryList> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
)

internal class RepositoryListsViewModel(
    private val collections: ProfileCollectionsRepository,
) : ViewModel() {
    private val account = collections.account
    private val mutableState = MutableStateFlow(RepositoryListsState())
    val uiState = mutableState.asStateFlow()
    private var repositoryId: String? = null

    fun open(id: String) {
        if (!active() || id.isBlank()) return
        repositoryId = id
        mutableState.value = RepositoryListsState(visible = true, loading = true)
        viewModelScope.launch {
            try {
                val lists = collections.userLists(account, account)
                val selected = collections.repositoryListIds(account, id)
                if (active() && repositoryId == id) {
                    mutableState.value =
                        RepositoryListsState(visible = true, lists = lists, selectedIds = selected)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (active() && repositoryId == id) {
                    mutableState.value = RepositoryListsState(visible = true, failed = true)
                }
            }
        }
    }

    fun toggle(listId: String) {
        val state = mutableState.value
        if (!state.visible || state.loading || state.saving) return
        val selected =
            if (listId in state.selectedIds) state.selectedIds - listId
            else state.selectedIds + listId
        mutableState.value = state.copy(selectedIds = selected)
    }

    fun save(onComplete: (Boolean) -> Unit) {
        val id = repositoryId ?: return
        val state = mutableState.value
        if (!active() || !state.visible || state.loading || state.saving || state.failed) return
        viewModelScope.launch {
            mutableState.value = state.copy(saving = true)
            try {
                collections.updateRepositoryLists(account, id, state.selectedIds)
                if (active() && repositoryId == id) {
                    mutableState.value = RepositoryListsState()
                    onComplete(true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (active() && repositoryId == id) {
                    mutableState.value = mutableState.value.copy(saving = false)
                    onComplete(false)
                }
            }
        }
    }

    fun dismiss() {
        repositoryId = null
        mutableState.value = RepositoryListsState()
    }

    private fun active() = collections.account.equals(account, true)
}
