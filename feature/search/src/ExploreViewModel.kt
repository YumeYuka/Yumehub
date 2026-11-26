package moe.yumeyuka.yumehub.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.auth.AuthSession
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile

internal sealed interface ExploreResult {
    data class Repository(val item: RepositoryDto) : ExploreResult

    data class Work(val item: CollaborationItemDto, val target: WorkTarget) : ExploreResult

    data class Actor(val item: UserDto) : ExploreResult

    data class Code(val file: SearchCodeFile) : ExploreResult
}

internal data class ExploreSummary(val totalCount: Int)

internal data class ExploreSearch(
    val category: ExploreCategory,
    val items: Flow<PagingData<ExploreResult>>,
    val summary: StateFlow<ExploreSummary?>,
    val revalidation: StateFlow<RevalidationState> = MutableStateFlow(RevalidationState.Idle),
    val needsQuery: Boolean = false,
    val query: String = "",
    val session: AuthSession? = null,
)

internal class ExploreViewModel(
    auth: AuthRepository,
    private val repository: ExplorePagingRepository,
) : ViewModel() {
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    private var session: AuthSession? = null
    private var category = ExploreCategory.Repositories
    private var searchJob: Job? = null
    private var adapterJob: Job? = null
    private var presented: Triple<AuthSession, String, ExploreCategory>? = null
    private val mutableSearch = MutableStateFlow(emptySearch())
    val searchState = mutableSearch.asStateFlow()

    init {
        viewModelScope.launch {
            auth.session.collect { current ->
                session = current
                startSearch(0)
            }
        }
    }

    fun onQueryChange(value: String) {
        if (value == mutableQuery.value) return
        mutableQuery.value = value
        startSearch(350L)
    }

    fun selectCategory(value: ExploreCategory) {
        if (value == category) return
        category = value
        startSearch(0)
    }

    fun search() {
        val login = session?.login
        val query = mutableQuery.value.trim()
        if (login != null && query.isNotEmpty()) repository.invalidate(login, query, category)
        startSearch(0)
    }

    private fun startSearch(debounceMillis: Long) {
        searchJob?.cancel()
        val query = mutableQuery.value.trim()
        val session = session
        val selected = category
        if (session == null || query.isEmpty()) {
            adapterJob?.cancel()
            presented = null
            mutableSearch.value = emptySearch()
        } else if (presented == Triple(session, query, selected)) {
            return
        } else if (debounceMillis == 0L) {
            present(session, query, selected)
        } else {
            adapterJob?.cancel()
            presented = null
            mutableSearch.value = ExploreSearch(
                selected, emptyFlow(), MutableStateFlow(null), query = query, session = session,
            )
            // 防抖在创建共享 Pager 之前执行，不为输入过程中的每个前缀启动会话请求。
            searchJob = viewModelScope.launch {
                delay(debounceMillis)
                present(session, query, selected)
            }
        }
    }

    fun retryRevalidation(search: ExploreSearch) {
        val login = search.session?.login ?: return
        if (search.session == session && search.query.isNotBlank()) {
            repository.retryRevalidation(login, search.query, search.category)
        }
    }

    private fun present(session: AuthSession, query: String, category: ExploreCategory) {
        val account = session.login
        val key = Triple(session, query, category)
        if (presented == key) return
        adapterJob?.cancel()
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        adapterJob = job
        val search = repository.search(account, query, category)
        mutableSearch.value =
            search.copy(
                query = query,
                session = session,
                revalidation = repository.revalidation(account, query, category),
                items =
                    search.items.cachedIn(CoroutineScope(viewModelScope.coroutineContext + job)),
            )
        presented = key
    }

    private fun emptySearch(): ExploreSearch =
        ExploreSearch(
            category,
            flowOf(PagingData.empty()),
            MutableStateFlow(null),
            needsQuery = true,
        )
}
