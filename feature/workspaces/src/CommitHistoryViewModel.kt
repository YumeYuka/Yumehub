package moe.yumeyuka.yumehub.feature.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommit
import moe.yumeyuka.yumehub.data.repository.WorkspacesGraphQlException
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesTimeoutException
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.GithubTxt
import retrofit2.HttpException

internal data class CommitHistoryUiState(
    val ref: String,
    val commits: List<WorkspaceCommit> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null,
    val loadMoreError: String? = null,
)

internal class CommitHistoryViewModel(
    private val source: WorkspacesRepository,
    private val tokenStore: TokenStore,
    private val route: CommitHistoryRoute,
) : ViewModel() {
    private var activeAccount = currentAccount()
    private var accountGeneration = 0L
    private var loadJob: Job? = null
    private val mutableUiState = MutableStateFlow(CommitHistoryUiState(ref = route.ref))
    val uiState = mutableUiState.asStateFlow()
    val branches =
        WorkspaceBranches(
            scope = viewModelScope,
            source = source,
            tokens = tokenStore,
            owner = route.owner,
            name = route.name,
            errorMessage = { it.readErrorMessage() },
        )

    init {
        viewModelScope.launch {
            tokenStore.accountLogin.state
                .map { it.trim().lowercase(Locale.ROOT) }
                .distinctUntilChanged()
                .collect { account -> if (account != activeAccount) bindAccount(account) }
        }
        loadFirstPage()
    }

    fun loadMore() {
        val state = mutableUiState.value
        val cursor = state.nextCursor ?: return
        if (state.loading || state.loadingMore || !state.hasMore || state.error != null) return
        loadPage(after = cursor, append = true)
    }

    fun retry() {
        if (mutableUiState.value.error != null || mutableUiState.value.commits.isEmpty()) {
            loadFirstPage()
        } else {
            loadMore()
        }
    }

    fun loadBranches() {
        branches.open()
    }

    fun switchBranch(branchRef: String) {
        val state = mutableUiState.value
        if (!branches.contains(branchRef) || branchRef == state.ref) return
        loadJob?.cancel()
        mutableUiState.value = CommitHistoryUiState(ref = branchRef)
        loadFirstPage()
    }

    fun refresh() {
        if (loadJob?.isActive != true) loadFirstPage()
    }

    private fun loadFirstPage() {
        loadJob?.cancel()
        mutableUiState.value =
            mutableUiState.value.copy(loading = true, error = null, loadingMore = false)
        loadPage(after = null, append = false)
    }

    private fun loadPage(after: String?, append: Boolean) {
        val account = activeAccount
        val generation = accountGeneration
        val ref = mutableUiState.value.ref
        mutableUiState.value =
            if (append) mutableUiState.value.copy(loadingMore = true, loadMoreError = null)
            else mutableUiState.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val page =
                    source.loadCommitHistory(
                        account = account,
                        owner = route.owner,
                        name = route.name,
                        ref = ref,
                        after = after,
                    )
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount(account) || generation != accountGeneration) return@launch
                if (ref != mutableUiState.value.ref) return@launch
                val current = mutableUiState.value
                mutableUiState.value =
                    current.copy(
                        commits = if (append) current.commits + page.commits else page.commits,
                        loading = false,
                        loadingMore = false,
                        hasMore = page.hasNextPage && page.endCursor != null,
                        nextCursor = page.endCursor,
                        error = null,
                        loadMoreError = null,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCurrentAccount(account) || generation != accountGeneration) return@launch
                if (ref != mutableUiState.value.ref) return@launch
                val message = error.readErrorMessage()
                mutableUiState.value =
                    if (append) {
                        mutableUiState.value.copy(loadingMore = false, loadMoreError = message)
                    } else {
                        mutableUiState.value.copy(loading = false, error = message)
                    }
            }
        }
    }

    private fun bindAccount(account: String) {
        activeAccount = account
        accountGeneration++
        loadJob?.cancel()
        branches.reset()
        mutableUiState.value = CommitHistoryUiState(ref = route.ref)
        loadFirstPage()
    }

    private fun Throwable.readErrorMessage(): String {
        val strings = GithubTxt.current
        return when (this) {
            is WorkspacesTimeoutException -> strings.Repository.RequestTimeout
            is WorkspacesGraphQlException,
            is SerializationException -> strings.Repository.ParseFailed
            is HttpException ->
                when (code()) {
                    401 -> strings.Repository.SessionExpired
                    403 -> strings.Repository.ReadForbidden
                    404 -> strings.Repository.ReadNotFound
                    else -> strings.Repository.HttpFailed(code())
                }
            is IOException -> strings.Repository.NetworkFailed
            else -> strings.Workspaces.CommitHistoryLoadFailed
        }
    }

    private fun currentAccount(): String =
        tokenStore.accountLogin.value.trim().lowercase(Locale.ROOT)

    private fun isCurrentAccount(account: String): Boolean =
        account.isNotBlank() && currentAccount() == account
}
