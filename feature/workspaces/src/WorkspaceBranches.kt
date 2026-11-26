package moe.yumeyuka.yumehub.feature.workspaces

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

internal data class WorkspaceBranchesState(
    val branches: WorkspacesLoad<List<String>> = WorkspacesLoad.Loading,
    val query: String = "",
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

/** 两个分支入口共用按需分页；不等待其他页面，也不为搜索下载全仓库分支。 */
internal class WorkspaceBranches(
    private val scope: CoroutineScope,
    private val source: WorkspacesRepository,
    private val tokens: TokenStore,
    private val owner: String,
    private val name: String,
    private val errorMessage: (Throwable) -> String,
) {
    private val mutableUiState = MutableStateFlow(WorkspaceBranchesState())
    val uiState = mutableUiState.asStateFlow()
    private var requestJob: Job? = null
    private val searches = LinkedHashMap<String, WorkspaceBranchesState>(8, 0.75f, true)
    private var requestGeneration = 0L
    private var sessionAccount = currentAccount()
    private var sessionToken = tokens.accessToken.value

    fun open() {
        resetIfSessionChanged()
        if (mutableUiState.value.query.isNotEmpty()) {
            search("")
        } else if (mutableUiState.value.branches !is WorkspacesLoad.Ready) {
            if (requestJob?.isActive != true) loadPage()
        }
    }

    fun search(query: String) {
        resetIfSessionChanged()
        val normalizedQuery = query.trim()
        if (normalizedQuery == mutableUiState.value.query) {
            if (
                mutableUiState.value.branches is WorkspacesLoad.Loading &&
                    requestJob?.isActive != true
            ) {
                loadPage()
            }
            return
        }
        requestJob?.cancel()
        requestGeneration++
        val current = mutableUiState.value
        if (current.branches is WorkspacesLoad.Ready) {
            searches[current.query] = current.copy(loadingMore = false)
            if (searches.size > 8) searches.remove(searches.keys.first())
        }
        val previous = searches.remove(normalizedQuery)
        mutableUiState.value = previous ?: WorkspaceBranchesState(query = normalizedQuery)
        if (previous == null) loadPage(debounce = normalizedQuery.isNotEmpty())
    }

    fun loadMore() {
        if (resetIfSessionChanged()) {
            open()
            return
        }
        val state = mutableUiState.value
        if (state.branches !is WorkspacesLoad.Ready || state.loadingMore || !state.hasMore) return
        loadPage(after = state.nextCursor ?: return)
    }

    fun retry() {
        if (resetIfSessionChanged()) {
            open()
            return
        }
        if (mutableUiState.value.branches is WorkspacesLoad.Ready) loadMore()
        else if (requestJob?.isActive != true) loadPage()
    }

    fun reset() {
        requestGeneration++
        requestJob?.cancel()
        searches.clear()
        mutableUiState.value = WorkspaceBranchesState()
        sessionAccount = currentAccount()
        sessionToken = tokens.accessToken.value
    }

    fun contains(branchRef: String): Boolean {
        if (resetIfSessionChanged()) return false
        val choices = (mutableUiState.value.branches as? WorkspacesLoad.Ready)?.value.orEmpty()
        return branchRef in choices
    }

    private fun currentAccount(): String = tokens.accountLogin.value.trim().lowercase(Locale.ROOT)

    private fun resetIfSessionChanged(): Boolean {
        val changed = sessionAccount != currentAccount() || sessionToken != tokens.accessToken.value
        if (changed) reset()
        return changed
    }

    private fun loadPage(after: String? = null, debounce: Boolean = false) {
        requestJob?.cancel()
        val generation = ++requestGeneration
        val account = sessionAccount
        val accessToken = sessionToken
        val query = mutableUiState.value.query
        fun isCurrentRequest(): Boolean =
            generation == requestGeneration &&
                account.isNotBlank() &&
                currentAccount() == account &&
                tokens.accessToken.value == accessToken

        mutableUiState.value =
            if (after == null) WorkspaceBranchesState(query = query)
            else mutableUiState.value.copy(loadingMore = true, loadMoreError = null)
        requestJob = scope.launch {
            try {
                if (debounce) delay(250)
                if (!isCurrentRequest()) return@launch
                val page = source.loadBranches(account, owner, name, after, query)
                currentCoroutineContext().ensureActive()
                if (!isCurrentRequest()) return@launch
                val previous =
                    if (after == null) emptyList()
                    else (mutableUiState.value.branches as? WorkspacesLoad.Ready)?.value.orEmpty()
                // 默认分支可不在首个 refs 页中，直接由同次查询置顶；搜索时不插入无关结果。
                val defaultBranch =
                    page.defaultBranch?.takeIf { branch ->
                        query.isEmpty() ||
                            branch.removePrefix("refs/heads/").contains(query, ignoreCase = true)
                    }
                val branches = (listOfNotNull(defaultBranch) + previous + page.branches).distinct()
                mutableUiState.value =
                    WorkspaceBranchesState(
                        branches = WorkspacesLoad.Ready(branches),
                        query = query,
                        hasMore = page.hasNextPage,
                        nextCursor = page.endCursor,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCurrentRequest()) return@launch
                val discard = error.invalidatesCachedContent()
                if (discard) searches.clear()
                mutableUiState.value =
                    if (after == null || discard) {
                        WorkspaceBranchesState(
                            branches = WorkspacesLoad.Error(errorMessage(error)),
                            query = query,
                        )
                    } else {
                        mutableUiState.value.copy(
                            loadingMore = false,
                            loadMoreError = errorMessage(error),
                        )
                    }
            }
        }
    }
}
