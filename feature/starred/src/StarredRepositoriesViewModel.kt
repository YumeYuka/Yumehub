package moe.yumeyuka.yumehub.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.filter
import kotlinx.coroutines.flow.flowOf
import moe.yumeyuka.yumehub.data.repository.StarredRowChange
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
import moe.yumeyuka.yumehub.data.repository.UserRepositoryList
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore

internal data class StarredListCreation(val name: String = "", val restored: Boolean = false,
    val visible: Boolean = false, val submitting: Boolean = false, val uncertain: Boolean = false, val failed: Boolean = false)

class StarredRepositoriesViewModel(
    private val collections: ProfileCollectionsRepository,
    login: String,
    private val drafts: CommentDraftStore,
) : ViewModel() {
    internal val account = collections.account
    private val login = login
    private val query = MutableStateFlow("")
    val searchQuery = query.asStateFlow()
    private val mutableSelectedList = MutableStateFlow<UserRepositoryList?>(null)
    val selectedList = mutableSelectedList.asStateFlow()
    internal fun membershipFor(list: UserRepositoryList?) = when {
        !login.equals(account, true) -> flowOf(emptyMap<String, StarredRowChange>())
        list == null -> collections.starredChanges(account)
        else -> collections.listChanges(account, list.id)
    }
    private val mutableLists = MutableStateFlow<List<UserRepositoryList>>(emptyList())
    val lists = mutableLists.asStateFlow()
    private val mutableListsLoading = MutableStateFlow(true)
    val listsLoading = mutableListsLoading.asStateFlow()
    private val mutableListsFailed = MutableStateFlow(false)
    val listsFailed = mutableListsFailed.asStateFlow()
    private val mutableCreation = MutableStateFlow(StarredListCreation())
    internal val creation = mutableCreation.asStateFlow()
    private val draftKey = "create-list/${login.lowercase(Locale.ROOT)}"
    private val createKey =
        MutationKey(
            MutationKind.LIST_CREATE,
            login.lowercase(Locale.ROOT),
            UserRepositoryList.serializer(),
        )
    private var submittingHere = false
    private var closeOnSuccess = false

    internal fun repositoriesFor(search: String, list: UserRepositoryList?) =
        (if (list == null) collections.starred(account, login, search)
         else collections.listItems(account, login, list.slug, search))
            .combine(membershipFor(list)) { page, changes ->
                if (changes.isEmpty()) page else page.filter {
                    changes[it.fullName?.lowercase(Locale.ROOT)]?.member != false
                }
            }

    internal fun revalidationFor(search: String, list: UserRepositoryList?) =
        collections.revalidation(account, activeKey(search, list))

    internal fun retryRevalidation(search: String, list: UserRepositoryList?) =
        collections.retryRevalidation(account, activeKey(search, list))

    private fun activeKey(search: String, list: UserRepositoryList?): String =
        if (list == null) collections.starredKey(account, login, search)
        else collections.listItemsKey(login, list.slug, search)

    init {
        viewModelScope.launch {
            collections.observeUserLists(account, login).collect { state ->
                state.data?.let { mutableLists.value = it }
                mutableListsLoading.value = state.data == null && state.error == null
                mutableListsFailed.value = state.error != null
            }
        }
        viewModelScope.launch {
            val coord = collections.mutations(account)
            coord.observeCommand(createKey).collect { cmd ->
                if (!submittingHere) mutableCreation.value = mutableCreation.value.copy(
                    submitting = cmd.phase == WritePhase.PENDING, uncertain = cmd.phase == WritePhase.UNCERTAIN)
            }
        }
    }

    fun search(value: String) {
        query.value = value.trim()
    }

    fun selectList(list: UserRepositoryList?) {
        mutableSelectedList.value = list
    }

    fun refreshLists() { if (active()) collections.refreshUserLists(account, login) }

    fun showCreation(visible: Boolean) {
        if (!visible) closeOnSuccess = false
        mutableCreation.value = mutableCreation.value.copy(visible = visible)
        if (visible && !mutableCreation.value.restored) viewModelScope.launch {
            val name = drafts.load(account, draftKey)
            if (!mutableCreation.value.restored) mutableCreation.value = mutableCreation.value.copy(name = name, restored = true)
        }
    }

    fun changeName(name: String) {
        val form = mutableCreation.value
        if (!form.restored || form.submitting || form.uncertain) return
        mutableCreation.value = form.copy(name = name, failed = false)
        drafts.save(account, draftKey, name)
    }

    fun createList() {
        val form = mutableCreation.value
        if (!active() || !form.restored || form.submitting || form.uncertain || form.name.isBlank()) return
        val name = form.name
        submittingHere = true
        closeOnSuccess = form.visible
        mutableCreation.value = form.copy(submitting = true, failed = false)
        viewModelScope.launch {
            val store = drafts
            val key = draftKey
            val login = account
            if (!store.saveConfirmed(login, key, name)) {
                submittingHere = false
                mutableCreation.value = mutableCreation.value.copy(submitting = false, failed = true)
                return@launch
            }
            val repo = collections
            val coord = repo.mutations(login)
            val receipt = coord.submitCommand(createKey, setOf(repo.userListsTag(login)),
                onConfirmed = { store.removeIfUnchanged(login, key, name) },
                send = { repo.createUserList(login, name) }, readBack = { null })
            val cmd = receipt?.await() ?: coord.currentCommand(createKey)
            submittingHere = false
            if (receipt != null && cmd.phase == WritePhase.IDLE && cmd.result != null) {
                mutableCreation.value = StarredListCreation(restored = true, visible = mutableCreation.value.visible && !closeOnSuccess)
            } else mutableCreation.value = mutableCreation.value.copy(submitting = cmd.phase == WritePhase.PENDING,
                uncertain = cmd.phase == WritePhase.UNCERTAIN, failed = cmd.phase != WritePhase.PENDING)
        }
    }

    fun acknowledgeCreateUncertain() {
        viewModelScope.launch {
            val coord = collections.mutations(account)
            val cmd = coord.currentCommand(createKey)
            if (cmd.phase == WritePhase.UNCERTAIN) cmd.submissionId?.let(coord::acknowledge)
        }
    }

    private fun active() = collections.account.equals(account, true)
}
