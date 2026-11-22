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
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentTimeoutException
import moe.yumeyuka.yumehub.data.repository.RepositoryTextContent
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesTimeoutException
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.GithubTxt
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

    data class Readme(val preview: RepositoryLoad<RepositoryTextPreview>) : RepositoryDetailPage

    data class License(val contents: RepositoryLoad<String>) : RepositoryDetailPage
}

data class RepositoryDetailUiState(
    val repository: RepositoryLoad<RepositoryDto> = RepositoryLoad.Loading,
    val page: RepositoryDetailPage = RepositoryDetailPage.Overview,
    val overviewReadme: RepositoryLoad<RepositoryTextPreview>? = null,
    val selectedRef: String? = null,
    val refs: RepositoryLoad<List<String>> = RepositoryLoad.Loading,
)

class RepositoryDetailViewModel(
    private val contentRepository: RepositoryContentRepository,
    private val tokenStore: TokenStore,
    private val workspaces: WorkspacesRepository,
    private val owner: String,
    private val repository: String,
) : ViewModel() {
    private var activeAccount = currentAccount()
    private val mutableUiState =
        MutableStateFlow(RepositoryDetailUiState(repository = initialRepositoryState()))
    private var repositoryObserver: Job? = null
    private var contentObserver: Job? = null
    private var overviewReadmeObserver: Job? = null
    private var refsJob: Job? = null
    private var pageGeneration = 0L
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
        mutableUiState.value = mutableUiState.value.copy(selectedRef = ref)
        when (mutableUiState.value.page) {
            RepositoryDetailPage.Overview -> loadOverviewReadme()
            is RepositoryDetailPage.Readme -> openReadme()
            is RepositoryDetailPage.License -> Unit
        }
    }

    fun retry() {
        if (currentRepository() == null) {
            loadRepository(force = true)
            return
        }
        refreshCurrentPage(force = true)
    }

    fun refresh() {
        loadRepository(force = true)
        when (mutableUiState.value.page) {
            RepositoryDetailPage.Overview -> loadOverviewReadme(force = true)
            is RepositoryDetailPage.Readme -> loadReadme(force = true)
            is RepositoryDetailPage.License -> loadLicense(force = true)
        }
    }

    private fun refreshCurrentPage(force: Boolean) {
        when (mutableUiState.value.page) {
            RepositoryDetailPage.Overview -> loadRepository(force)
            is RepositoryDetailPage.Readme -> loadReadme(force)
            is RepositoryDetailPage.License -> loadLicense(force)
        }
    }

    fun openLicense() = loadLicense(force = false)

    private fun loadLicense(force: Boolean) {
        val account = activeAccount
        if (!isCurrentAccount(account)) return
        observeContent(
            resource = contentRepository.license(account, owner, repository),
            force = force,
            update = { setPage(RepositoryDetailPage.License(it)) },
            request = { contentRepository.loadLicense(account, owner, repository) },
        )
    }

    fun retryOverviewReadme() = loadOverviewReadme(force = true)

    private fun loadOverviewReadme(force: Boolean = false) {
        if (currentRepository() == null) return
        val account = activeAccount
        val session = accountGeneration
        val ref = currentRef()
        val resource = contentRepository.readme(account, owner, repository, ref)
        overviewReadmeObserver?.cancel()
        mutableUiState.value =
            mutableUiState.value.copy(overviewReadme = resource.state.value.toLoad())
        overviewReadmeObserver = viewModelScope.launch {
            resource.state.collect { snapshot ->
                if (
                    session == accountGeneration && isCurrentAccount(account) && ref == currentRef()
                ) {
                    mutableUiState.value =
                        mutableUiState.value.copy(overviewReadme = snapshot.toLoad())
                }
            }
        }
        resource.refresh(force) { contentRepository.loadReadme(account, owner, repository, ref) }
    }

    fun openReadme() = loadReadme(force = false)

    private fun loadReadme(force: Boolean) {
        if (currentRepository() == null) return
        val account = activeAccount
        val source = contentRepository
        val ref = currentRef()
        observeContent(
            resource = source.readme(account, owner, repository, ref),
            force = force,
            update = { setPage(RepositoryDetailPage.Readme(it)) },
            request = { source.loadReadme(account, owner, repository, ref) },
        )
    }

    fun navigateUp(): Boolean =
        when (mutableUiState.value.page) {
            RepositoryDetailPage.Overview -> false
            is RepositoryDetailPage.Readme,
            is RepositoryDetailPage.License -> {
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
        val current = state.data
        mutableUiState.value = mutableUiState.value.copy(repository = state.toLoad())
        if (
            current != null &&
                (overviewReadmeObserver == null ||
                    (previous?.defaultBranch != current.defaultBranch &&
                        mutableUiState.value.selectedRef == null))
        ) {
            loadOverviewReadme()
        }
        if (
            previous != null &&
                current != null &&
                previous.defaultBranch != current.defaultBranch &&
                mutableUiState.value.selectedRef == null &&
                mutableUiState.value.page is RepositoryDetailPage.Readme
        ) {
            refreshCurrentPage(force = false)
        }
    }

    private fun currentRef(): String? =
        mutableUiState.value.selectedRef
            ?: currentRepository()?.defaultBranch?.let { "refs/heads/$it" }

    private fun currentRepository(): RepositoryDto? =
        (mutableUiState.value.repository as? RepositoryLoad.Ready)?.value?.takeIf {
            isCurrentAccount(activeAccount)
        }

    internal fun showOverview() {
        stopObservingContent()
        setPage(RepositoryDetailPage.Overview)
        loadRepository()
    }

    private fun setPage(page: RepositoryDetailPage) {
        mutableUiState.value = mutableUiState.value.copy(page = page)
    }

    private fun stopObservingContent() {
        pageGeneration++
        contentObserver?.cancel()
        contentObserver = null
    }

    private fun <T : Any> observeContent(
        resource: CachedResource<T>,
        force: Boolean,
        update: (RepositoryLoad<T>) -> Unit,
        request: suspend () -> T,
    ) {
        stopObservingContent()
        val generation = pageGeneration
        val account = activeAccount
        val session = accountGeneration
        update(resource.state.value.toLoad())
        contentObserver = viewModelScope.launch {
            resource.state.collect { state ->
                if (
                    generation == pageGeneration &&
                        session == accountGeneration &&
                        isCurrentAccount(account)
                ) {
                    update(state.toLoad())
                }
            }
        }
        // 只取消页面订阅；请求由共享仓库持有，多页面共用并合并同 key 的请求。
        resource.refresh(force = force, loader = request)
    }

    private fun currentAccount(): String =
        tokenStore.accountLogin.value.trim().lowercase(Locale.ROOT)

    private fun initialRepositoryState(): RepositoryLoad<RepositoryDto> =
        activeAccount.takeIf(String::isNotBlank)?.let {
            contentRepository.details(it, owner, repository).state.value.toLoad()
        } ?: RepositoryLoad.Error(GithubTxt.current.Repository.SessionExpired)

    private fun bindAccount(account: String) {
        activeAccount = account
        accountGeneration++
        refsJob?.cancel()
        stopObservingContent()
        repositoryObserver?.cancel()
        overviewReadmeObserver?.cancel()
        overviewReadmeObserver = null
        mutableUiState.value = RepositoryDetailUiState(repository = initialRepositoryState())
        loadRepository()
    }

    private fun isCurrentAccount(account: String): Boolean =
        account.isNotBlank() && currentAccount() == account

    private fun <T : Any> CachedDataState<T>.toLoad(): RepositoryLoad<T> {
        val content = data
        val message = error?.takeUnless { it is CancellationException }?.readErrorMessage()
        return when {
            content != null -> RepositoryLoad.Ready(content, loading, message)
            message != null && !loading -> RepositoryLoad.Error(message)
            else -> RepositoryLoad.Loading
        }
    }

    private fun Throwable.readErrorMessage(): String =
        when (this) {
            is RepositoryContentTimeoutException,
            is WorkspacesTimeoutException -> GithubTxt.current.Repository.RequestTimeout
            is IOException -> GithubTxt.current.Repository.NetworkFailed
            is HttpException ->
                when (code()) {
                    401 -> GithubTxt.current.Repository.SessionExpired
                    403 -> GithubTxt.current.Repository.ReadForbidden
                    404 -> GithubTxt.current.Repository.ReadNotFound
                    422 -> GithubTxt.current.Repository.InvalidRef
                    else -> GithubTxt.current.Repository.HttpFailed(code())
                }
            is SerializationException -> GithubTxt.current.Repository.ParseFailed
            else -> GithubTxt.current.Repository.LoadRetry
        }
}
