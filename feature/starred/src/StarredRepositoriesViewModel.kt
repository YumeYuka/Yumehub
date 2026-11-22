package moe.yumeyuka.yumehub.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.ProfileCollectionsRepository
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val repositories =
        combine(query, mutableSelectedList) { search, list -> search to list }
            .flatMapLatest { (search, list) ->
                if (list == null) collections.starred(account, login, search)
                else collections.listItems(account, login, list.slug, search)
            }
            .cachedIn(viewModelScope)

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
        if (!active() || mutableCreating.value || name.isBlank()) return
        viewModelScope.launch {
            mutableCreating.value = true
            try {
                val created = collections.createUserList(account, name)
                if (active()) {
                    mutableLists.value = mutableLists.value + created
                    onResult(true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (active()) onResult(false)
            } finally {
                mutableCreating.value = false
            }
        }
    }

    private fun active() = collections.account.equals(account, true)
}
