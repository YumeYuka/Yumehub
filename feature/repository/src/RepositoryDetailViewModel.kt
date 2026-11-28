package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentTimeoutException
import moe.yumeyuka.yumehub.data.repository.RepositoryTextContent
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesTimeoutException
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.CurrentAppStrings
import retrofit2.HttpException

sealed interface RepositoryLoad<out T> {
    data object Loading : RepositoryLoad<Nothing>

    data class Ready<T>(
        val value: T,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
    ) : RepositoryLoad<T>

    data class Error(val message: String) : RepositoryLoad<Nothing>
}

typealias RepositoryTextPreview = RepositoryTextContent

sealed interface RepositoryDetailPage {
    data object Overview : RepositoryDetailPage

    data object Readme : RepositoryDetailPage
}

data class RepositoryDetailUiState(
    val repository: RepositoryLoad<RepositoryDto> = RepositoryLoad.Loading,
    val page: RepositoryDetailPage = RepositoryDetailPage.Overview,
    val readme: RepositoryLoad<RepositoryTextPreview> = RepositoryLoad.Loading,
    val selectedRef: String? = null,
    val refs: RepositoryLoad<List<String>> = RepositoryLoad.Loading,
)

class RepositoryDetailViewModel(
    private val contentRepository: RepositoryContentRepository,
    private val tokenStore: TokenStore,
    private val workspaces: WorkspacesRepository,
    private val owner: String,
    private val repository: String,
    private val initialPage: RepositoryDetailPage,
) : ViewModel() {
    private var activeAccount = currentAccount()
    private val mutableUiState =
        MutableStateFlow(
            RepositoryDetailUiState(repository = initialRepositoryState(), page = initialPage),
        )
    private var repositoryObserver: Job? = null
    private var readmeObserver: Job? = null
    private var refsJob: Job? = null
    private var accountGeneration = 0L

    val uiState = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            tokenStore.accountLogin.state
                .map { it.trim().lowercase(Locale.ROOT) }
                .distinctUntilChanged()
                .collect { account -> if (account != activeAccount) bindAccount(account) }
        }
        loadRepository()
    }

    fun loadRefs() {
        if (currentRepository() == null) return
        val account = activeAccount
        val session = accountGeneration
        val resource = contentRepository.refs(account, owner, repository)
        refsJob?.cancel()
        resource.refresh { workspaces.loadRepositoryRefs(account, owner, repository) }
        mutableUiState.value = mutableUiState.value.copy(refs = resource.state.value.toLoad())
        refsJob = viewModelScope.launch {
            resource.state.collect {
                if (session == accountGeneration && isCurrentAccount(account)) {
                    mutableUiState.value = mutableUiState.value.copy(refs = it.toLoad())
                }
            }
        }
    }

    fun switchRef(ref: String) {
        if (!isCurrentAccount(activeAccount)) return
        if (ref == currentRef()) return
        loadReadme(selectedRef = ref)
    }

    fun retryRepository() = loadRepository(force = true)

    fun refresh() {
        loadRepository(force = true)
        loadReadme(force = true)
    }

    fun retryReadme() = loadReadme(force = true)

    private fun loadReadme(
        force: Boolean = false,
        selectedRef: String? = mutableUiState.value.selectedRef,
    ) {
        if (currentRepository() == null) return
        val account = activeAccount
        val session = accountGeneration
        val ref = selectedRef ?: currentRepository()?.defaultBranch?.let { "refs/heads/$it" }
        val resource = contentRepository.readme(account, owner, repository, ref)
        readmeObserver?.cancel()
        mutableUiState.value =
            mutableUiState.value.copy(
                selectedRef = selectedRef,
                readme = resource.state.value.toLoad(),
            )
        readmeObserver = viewModelScope.launch {
            resource.state.collect { snapshot ->
                if (
                    session == accountGeneration && isCurrentAccount(account) && ref == currentRef()
                ) {
                    mutableUiState.value = mutableUiState.value.copy(readme = snapshot.toLoad())
                }
            }
        }
        resource.refresh(force) { contentRepository.loadReadme(account, owner, repository, ref) }
    }

    fun openReadme() = setPage(RepositoryDetailPage.Readme)

    fun navigateUp(): Boolean =
        when (mutableUiState.value.page) {
            RepositoryDetailPage.Overview -> false
            RepositoryDetailPage.Readme -> {
                showOverview()
                true
            }
        }

    private fun loadRepository(force: Boolean = false) {
        val account = activeAccount
        if (!isCurrentAccount(account)) return
        val generation = accountGeneration
        val source = contentRepository
        val resource = source.details(account, owner, repository)
        repositoryObserver?.cancel()
        projectRepository(resource.state.value)
        repositoryObserver = viewModelScope.launch {
            resource.state.collect { state ->
                if (generation == accountGeneration && isCurrentAccount(account)) {
                    // 快速 A→B→A 可能被账号 Flow 合并；旧资源清空后必须认领新会话的实例。
                    if (
                        state.data == null &&
                            !state.loading &&
                            state.error == null &&
                            source.details(account, owner, repository) !== resource
                    ) {
                        bindAccount(account)
                        return@collect
                    }
                    projectRepository(state)
                }
            }
        }
        resource.refresh(force = force) { source.loadDetails(account, owner, repository) }
    }

    private fun projectRepository(state: CachedDataState<RepositoryDto>) {
        val previous = currentRepository()
        val repositoryState = state.toLoad()
        val current = (repositoryState as? RepositoryLoad.Ready)?.value
        if (current == null) {
            readmeObserver?.cancel()
            readmeObserver = null
            mutableUiState.value =
                mutableUiState.value.copy(
                    repository = repositoryState,
                    readme = RepositoryLoad.Loading,
                )
            return
        }
        mutableUiState.value = mutableUiState.value.copy(repository = repositoryState)
        if (
            readmeObserver == null ||
                (previous?.defaultBranch != current.defaultBranch &&
                    mutableUiState.value.selectedRef == null)
        ) {
            loadReadme()
        }
    }

    private fun currentRef(): String? =
        mutableUiState.value.selectedRef
            ?: currentRepository()?.defaultBranch?.let { "refs/heads/$it" }

    private fun currentRepository(): RepositoryDto? =
        (mutableUiState.value.repository as? RepositoryLoad.Ready)?.value?.takeIf {
            isCurrentAccount(activeAccount)
        }

    internal fun showOverview() = setPage(RepositoryDetailPage.Overview)

    private fun setPage(page: RepositoryDetailPage) {
        mutableUiState.value = mutableUiState.value.copy(page = page)
    }

    private fun currentAccount(): String =
        tokenStore.accountLogin.value.trim().lowercase(Locale.ROOT)

    private fun initialRepositoryState(): RepositoryLoad<RepositoryDto> =
        activeAccount.takeIf(String::isNotBlank)?.let {
            contentRepository.details(it, owner, repository).state.value.toLoad()
        } ?: RepositoryLoad.Error(CurrentAppStrings.current.Repository.SessionExpired)

    private fun bindAccount(account: String) {
        activeAccount = account
        accountGeneration++
        refsJob?.cancel()
        repositoryObserver?.cancel()
        readmeObserver?.cancel()
        readmeObserver = null
        mutableUiState.value =
            RepositoryDetailUiState(repository = initialRepositoryState(), page = initialPage)
        loadRepository()
    }

    private fun isCurrentAccount(account: String): Boolean =
        account.isNotBlank() && currentAccount() == account

    private fun <T : Any> CachedDataState<T>.toLoad(): RepositoryLoad<T> {
        val content = data
        val failure = error?.takeUnless { it is CancellationException }
        val message = failure?.readErrorMessage()
        if (
            failure is HttpException &&
                (failure.code() == 401 || failure.code() == 403 || failure.code() == 404)
        ) {
            return RepositoryLoad.Error(requireNotNull(message))
        }
        return when {
            content != null -> RepositoryLoad.Ready(content, loading, message)
            message != null && !loading -> RepositoryLoad.Error(message)
            else -> RepositoryLoad.Loading
        }
    }

    private fun Throwable.readErrorMessage(): String =
        when (this) {
            is RepositoryContentTimeoutException,
            is WorkspacesTimeoutException -> CurrentAppStrings.current.Repository.RequestTimeout
            is IOException -> CurrentAppStrings.current.Repository.NetworkFailed
            is HttpException ->
                when (code()) {
                    401 -> CurrentAppStrings.current.Repository.SessionExpired
                    403 -> CurrentAppStrings.current.Repository.ReadForbidden
                    404 -> CurrentAppStrings.current.Repository.ReadNotFound
                    422 -> CurrentAppStrings.current.Repository.InvalidRef
                    else -> CurrentAppStrings.current.Repository.HttpFailed(code())
                }
            is SerializationException -> CurrentAppStrings.current.Repository.ParseFailed
            else -> CurrentAppStrings.current.Repository.LoadRetry
        }
}
