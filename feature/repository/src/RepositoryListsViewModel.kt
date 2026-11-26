package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import kotlinx.coroutines.flow.combine
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.repository.repositoryListsKey
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.UserRepositoryList

internal data class RepositoryListsState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val lists: List<UserRepositoryList> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val phase: WritePhase = WritePhase.IDLE,
    val uncertainOperationId: String? = null,
)

internal class RepositoryListsViewModel(
    private val collections: ProfileCollectionsRepository,
) : ViewModel() {
    private val account = collections.account
    private val mutableState = MutableStateFlow(RepositoryListsState())
    val uiState = mutableState.asStateFlow()
    private var repositoryId: String? = null
    private var repository: RepositoryDto? = null
    private var coordinator: MutationCoordinator? = null
    private var slotObserver: Job? = null
    private var loadJob: Job? = null
    private var confirmedIds: Set<String>? = null

    init {
        viewModelScope.launch {
            val loaded =
                try {
                    collections.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
            if (active()) coordinator = loaded
        }
    }


    fun open(row: RepositoryDto) {
        val id = row.nodeId ?: return
        if (!active() || id.isBlank()) return
        repository = row
        loadJob?.cancel()
        slotObserver?.cancel()
        repositoryId = id
        confirmedIds = null
        mutableState.value = RepositoryListsState(visible = true, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val coord =
                    coordinator
                        ?: collections.mutations(account).also { if (active()) coordinator = it }
                val key = repositoryListsKey(id)
                val stamp = coord.beginRead(key)
                var catalogReady = false
                var catalogFailed = false
                var membershipFailed = false
                fun renderRead() {
                    if (active() && repositoryId == id) mutableState.value = mutableState.value.copy(
                        loading = !catalogFailed && !membershipFailed && (!catalogReady || coord.current(key).confirmed == null), failed = catalogFailed || membershipFailed)
                }
                observeSlot(coord, id)
                confirmedIds = coord.current(key).confirmed?.toSet()
                launch {
                    collections.observeUserLists(account, account).collect { cached ->
                        if (!active() || repositoryId != id) return@collect
                        cached.data?.let { mutableState.value = mutableState.value.copy(lists = it) }
                        catalogReady = cached.data != null
                        catalogFailed = cached.error != null && !catalogReady
                        renderRead()
                    }
                }
                try {
                    val selected = collections.repositoryListIds(account, id)
                    if (!active() || repositoryId != id) return@launch
                    coord.publishConfirmed(key, stamp, selected.sorted())
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { membershipFailed = coord.current(key).confirmed == null }
                renderRead()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (active() && repositoryId == id) {
                    mutableState.value = RepositoryListsState(visible = true, failed = true)
                }
            }
        }
    }

    private fun observeSlot(coord: MutationCoordinator, id: String) {
        slotObserver?.cancel()
        slotObserver =
            viewModelScope.launch {
                val key = repositoryListsKey(id)
                combine(coord.observe(key), coord.pending) { write, pending -> write to
                    pending.firstOrNull { it.kind == key.kind && it.target == id }?.operationId }.collect { (write, marker) ->
                    if (!active() || repositoryId != id) return@collect
                    val current = mutableState.value
                    val shown = write.visible?.toSet() ?: current.selectedIds
                    val before = confirmedIds
                    val selected = if (before != null && write.phase == WritePhase.IDLE && current.phase == WritePhase.IDLE && !current.loading && current.selectedIds != before)
                        (shown - (before - current.selectedIds)) + (current.selectedIds - before) else shown
                    confirmedIds = write.confirmed?.toSet()
                    mutableState.value = current.copy(saving = write.phase == WritePhase.PENDING, selectedIds = selected, phase = write.phase, uncertainOperationId = marker)
                }
            }
    }

    fun acknowledge(operationId: String) {
        if (mutableState.value.uncertainOperationId == operationId) coordinator?.acknowledge(operationId)
    }

    fun toggle(listId: String) {
        val state = mutableState.value
        if (!state.visible || state.loading || state.saving || state.failed || state.phase == WritePhase.UNCERTAIN) return
        val selected =
            if (listId in state.selectedIds) state.selectedIds - listId
            else state.selectedIds + listId
        mutableState.value = state.copy(selectedIds = selected)
    }

    fun save(onComplete: (WritePhase) -> Unit) {
        val id = repositoryId ?: return
        val state = mutableState.value
        val row = repository ?: return
        val original = confirmedIds ?: return
        if (!active() || !state.visible || state.loading || state.saving || state.failed || state.phase == WritePhase.UNCERTAIN) return
        viewModelScope.launch {
            val coord =
                coordinator
                    ?: try {
                        collections.mutations(account).also { coordinator = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        onComplete(WritePhase.REJECTED)
                        return@launch
                    }
            val key = repositoryListsKey(id)
            val accepted = collections.submitRepositoryLists(account, row, original, state.selectedIds)
            if (!accepted) {
                onComplete(coord.current(key).phase.takeIf { it == WritePhase.UNCERTAIN } ?: WritePhase.REJECTED)
                return@launch
            }
            dismiss()
            val phase = coord.observe(key).first { it.phase != WritePhase.PENDING }.phase
            if (active()) onComplete(phase)
        }
    }

    fun dismiss() {
        repositoryId = null
        loadJob?.cancel()
        slotObserver?.cancel()
        repository = null
        mutableState.value = RepositoryListsState()
    }

    private fun active() = collections.account.equals(account, true)
}
