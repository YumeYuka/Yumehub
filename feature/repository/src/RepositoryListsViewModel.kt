package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
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
)

internal class RepositoryListsViewModel(
    private val collections: ProfileCollectionsRepository,
) : ViewModel() {
    private val account = collections.account
    private val mutableState = MutableStateFlow(RepositoryListsState())
    val uiState = mutableState.asStateFlow()
    private var repositoryId: String? = null
    private var coordinator: MutationCoordinator? = null
    private var slotObserver: Job? = null
    private var confirmedIds: Set<String>? = null
    private var pendingCompletion: ((Boolean) -> Unit)? = null

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

    private fun listsKey(id: String) =
        MutationKey(MutationKind.REPO_LISTS, id, ListSerializer(String.serializer()))

    fun open(id: String) {
        if (!active() || id.isBlank()) return
        repositoryId = id
        confirmedIds = null
        mutableState.value = RepositoryListsState(visible = true, loading = true)
        viewModelScope.launch {
            try {
                val coord =
                    coordinator
                        ?: collections.mutations(account).also { if (active()) coordinator = it }
                val key = listsKey(id)
                val stamp = coord.beginRead(key)
                val lists = collections.userLists(account, account)
                val selected = collections.repositoryListIds(account, id)
                coord.publishConfirmed(key, stamp, selected.sorted())
                confirmedIds = selected
                observeSlot(coord, id)
                if (active() && repositoryId == id) {
                    val slot = coord.current(key)
                    val shown = (slot.desired ?: slot.confirmed)?.toSet() ?: selected
                    mutableState.value =
                        RepositoryListsState(
                            visible = true,
                            lists = lists,
                            selectedIds = shown,
                            saving = slot.phase == WritePhase.PENDING,
                            phase = slot.phase,
                        )
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

    private fun observeSlot(coord: MutationCoordinator, id: String) {
        slotObserver?.cancel()
        slotObserver =
            viewModelScope.launch {
                coord.observe(listsKey(id)).collect { write ->
                    if (!active() || repositoryId != id) return@collect
                    val current = mutableState.value
                    val shown = (write.desired ?: write.confirmed)?.toSet() ?: current.selectedIds
                    when (write.phase) {
                        WritePhase.IDLE -> {
                            val cb = pendingCompletion
                            pendingCompletion = null
                            if (cb != null) {
                                mutableState.value = RepositoryListsState()
                                cb(true)
                            } else {
                                mutableState.value =
                                    current.copy(
                                        saving = false,
                                        selectedIds = shown,
                                        phase = WritePhase.IDLE,
                                    )
                            }
                        }
                        WritePhase.PENDING ->
                            mutableState.value =
                                current.copy(
                                    saving = true,
                                    selectedIds = shown,
                                    phase = WritePhase.PENDING,
                                )
                        WritePhase.REJECTED,
                        WritePhase.UNCERTAIN -> {
                            val cb = pendingCompletion
                            pendingCompletion = null
                            mutableState.value =
                                current.copy(
                                    saving = false,
                                    selectedIds = shown,
                                    phase = write.phase,
                                )
                            cb?.invoke(false)
                        }
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
            val coord =
                coordinator
                    ?: try {
                        collections.mutations(account).also { coordinator = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        onComplete(false)
                        return@launch
                    }
            observeSlot(coord, id)
            pendingCompletion = onComplete
            val accepted =
                coord.submit(
                    listsKey(id),
                    confirmed = confirmedIds?.sorted(),
                    desired = state.selectedIds.sorted(),
                    tags = setOf(CacheTag(DataDomain.Repositories, null)),
                    send = { desiredIds ->
                        collections.updateRepositoryLists(account, id, desiredIds.toSet())
                        collections.repositoryListIds(account, id).sorted()
                    },
                    readBack = { collections.repositoryListIds(account, id).sorted() },
                )
            if (!accepted) {
                pendingCompletion = null
                onComplete(false)
            }
        }
    }

    fun dismiss() {
        repositoryId = null
        slotObserver?.cancel()
        pendingCompletion = null
        mutableState.value = RepositoryListsState()
    }

    private fun active() = collections.account.equals(account, true)
}
