package moe.yumeyuka.yumehub.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.UserRepositoryList

class StarredRepositoriesViewModel(
    private val collections: ProfileCollectionsRepository,
    login: String,
) : ViewModel() {
    private val account = collections.account
    private val login = login
    private val query = MutableStateFlow("")
    val searchQuery = query.asStateFlow()
    private val mutableSelectedList = MutableStateFlow<UserRepositoryList?>(null)
    val selectedList = mutableSelectedList.asStateFlow()
    private val mutableLists = MutableStateFlow<List<UserRepositoryList>>(emptyList())
    val lists = mutableLists.asStateFlow()
    private val mutableListsLoading = MutableStateFlow(true)
    val listsLoading = mutableListsLoading.asStateFlow()
    private val mutableListsFailed = MutableStateFlow(false)
    val listsFailed = mutableListsFailed.asStateFlow()
    private val mutableCreating = MutableStateFlow(false)
    val creating = mutableCreating.asStateFlow()
    private val mutableCreateUncertain = MutableStateFlow(false)
    val createUncertain = mutableCreateUncertain.asStateFlow()
    private val createKey =
        MutationKey(
            MutationKind.LIST_CREATE,
            login.lowercase(Locale.ROOT),
            UserRepositoryList.serializer(),
        )
    private var createObserverStarted = false
    private var pendingCreateCallback: ((Boolean) -> Unit)? = null
    private var uncertainOperationId: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val repositories =
        combine(query, mutableSelectedList) { search, list -> search to list }
            .flatMapLatest { (search, list) ->
                if (list == null) collections.starred(account, login, search)
                else collections.listItems(account, login, list.slug, search)
            }
            .cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val revalidation: StateFlow<RevalidationState> =
        combine(query, mutableSelectedList) { search, list -> search to list }
            .flatMapLatest { (search, list) ->
                collections.revalidation(account, activeKey(search, list))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        collections.retryRevalidation(account, activeKey(query.value, mutableSelectedList.value))
    }

    private fun activeKey(search: String, list: UserRepositoryList?): String =
        if (list == null) collections.starredKey(account, login, search)
        else collections.listItemsKey(login, list.slug, search)

    init {
        refreshLists()
    }

    fun search(value: String) {
        query.value = value
    }

    fun selectList(list: UserRepositoryList?) {
        mutableSelectedList.value = list
    }

    fun refreshLists() {
        if (!active()) return
        viewModelScope.launch {
            mutableListsLoading.value = true
            mutableListsFailed.value = false
            try {
                mutableLists.value = collections.userLists(account, login)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableListsFailed.value = true
            } finally {
                mutableListsLoading.value = false
            }
        }
    }

    fun createList(name: String, onResult: (Boolean) -> Unit) {
        if (!active() || mutableCreating.value || mutableCreateUncertain.value || name.isBlank()) {
            return
        }
        pendingCreateCallback = onResult
        mutableCreating.value = true
        viewModelScope.launch {
            val coord = collections.mutations(account)
            if (!createObserverStarted) {
                createObserverStarted = true
                launch {
                    coord.observeCommand(createKey).collect { cmd ->
                        when (cmd.phase) {
                            WritePhase.PENDING -> mutableCreating.value = true
                            WritePhase.IDLE -> {
                                val created = cmd.result
                                mutableCreating.value = false
                                mutableCreateUncertain.value = false
                                uncertainOperationId = null
                                if (created != null && active()) {
                                    mutableLists.value = (mutableLists.value + created).distinctBy { it.id }
                                    pendingCreateCallback?.invoke(true)
                                    pendingCreateCallback = null
                                }
                            }
                            WritePhase.REJECTED -> {
                                mutableCreating.value = false
                                mutableCreateUncertain.value = false
                                if (active()) {
                                    pendingCreateCallback?.invoke(false)
                                    pendingCreateCallback = null
                                }
                            }
                            WritePhase.UNCERTAIN -> {
                                mutableCreating.value = false
                                mutableCreateUncertain.value = true
                                uncertainOperationId =
                                    coord.pending.value
                                        .firstOrNull {
                                            it.kind == createKey.kind && it.target == createKey.target
                                        }
                                        ?.operationId
                                if (active()) {
                                    pendingCreateCallback?.invoke(false)
                                    pendingCreateCallback = null
                                }
                            }
                        }
                    }
                }
            }
            val accepted =
                coord.submitCommand(
                    key = createKey,
                    tags = setOf(CacheTag(DataDomain.Profile, null)),
                    send = { collections.createUserList(account, name) },
                    readBack = { null },
                )
            if (!accepted) {
                mutableCreating.value = false
                mutableCreateUncertain.value = true
                uncertainOperationId =
                    coord.pending.value
                        .firstOrNull {
                            it.kind == createKey.kind && it.target == createKey.target
                        }
                        ?.operationId
                if (active()) {
                    pendingCreateCallback?.invoke(false)
                    pendingCreateCallback = null
                }
            }
        }
    }

    fun acknowledgeCreateUncertain() {
        val opId = uncertainOperationId
        viewModelScope.launch {
            val coord = collections.mutations(account)
            val id =
                opId
                    ?: coord.pending.value
                        .firstOrNull { it.kind == createKey.kind && it.target == createKey.target }
                        ?.operationId
            if (id != null) coord.acknowledge(id)
            mutableCreateUncertain.value = false
            uncertainOperationId = null
        }
    }

    private fun active() = collections.account.equals(account, true)
}
