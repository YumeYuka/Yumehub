package moe.yumeyuka.yumehub.feature.workspaces

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommitDraft
import moe.yumeyuka.yumehub.data.repository.WorkspaceFileDraft
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentTimeoutException
import moe.yumeyuka.yumehub.data.repository.RepositoryTextContent
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommit
import moe.yumeyuka.yumehub.data.repository.WorkspaceEntry
import moe.yumeyuka.yumehub.data.repository.WorkspacesFileNotFound
import moe.yumeyuka.yumehub.data.repository.WorkspacesGraphQlException
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesTimeoutException
import moe.yumeyuka.yumehub.data.repository.isRepositoryContentPath
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.locale.GithubStrings_Workspaces
import moe.yumeyuka.yumehub.locale.GithubTxt
import moe.yumeyuka.yumehub.ui.component.githubCodeLanguageForFilename
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorKind
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession
import moe.yumeyuka.yumehub.ui.navigation.GithubFileReadStatus
import retrofit2.HttpException

internal sealed interface WorkspacesLoad<out T> {
    data object Loading : WorkspacesLoad<Nothing>

    data class Ready<T>(
        val value: T,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
    ) : WorkspacesLoad<T>

    data class Error(val message: String) : WorkspacesLoad<Nothing>
}

internal data class WorkspacesUiState(
    val repository: WorkspacesLoad<String> = WorkspacesLoad.Loading,
    val ref: String? = null,
    val path: String = "",
    val entries: WorkspacesLoad<List<WorkspaceEntry>> = WorkspacesLoad.Loading,
    val commit: WorkspacesLoad<WorkspaceCommit?> = WorkspacesLoad.Loading,
    val updatedAt: Map<String, String> = emptyMap(),
    val pendingEditor: GithubEditorSession? = null,
)

private data class WorkspaceTarget(
    val ref: String,
    val path: String,
    val file: Boolean,
    val anchor: String?,
)

private fun WorkspacesRoute.target(defaultRef: String?): WorkspaceTarget? {
    val ref = ref?.takeIf(String::isNotBlank) ?: defaultRef ?: return null
    val targetPath = path.takeIf { it.isEmpty() || isRepositoryContentPath(it) }.orEmpty()
    return WorkspaceTarget(ref, targetPath, file, anchor)
}

internal sealed interface WorkspacesEvent {
    data object Saved : WorkspacesEvent
}

internal class WorkspacesViewModel(
    private val workspacesRepository: WorkspacesRepository,
    private val contentRepository: RepositoryContentRepository,
    private val tokenStore: TokenStore,
    private val route: WorkspacesRoute,
    private val draftStore: CommentDraftStore,
) : ViewModel() {
    private val strings: GithubStrings_Workspaces
        get() = GithubTxt.current.Workspaces

    private var activeAccount = currentAccount()
    private var accountGeneration = 0L
    private var directoryGeneration = 0L
    private var detailsJob: Job? = null
    private var directoryJob: Job? = null
    private var commitJob: Job? = null
    private var fileJob: Job? = null
    private var saveJob: Job? = null
    private val prefetchJobs = mutableMapOf<String, Job>()
    private val directoryCache =
        LinkedHashMap<Pair<String, String>, DirectorySnapshot>(MaxCachedDirectories, 0.75f, true)

    private data class DirectorySnapshot(
        val entries: List<WorkspaceEntry>,
        val commit: WorkspacesLoad<WorkspaceCommit?> = WorkspacesLoad.Loading,
        val updatedAt: Map<String, String> = emptyMap(),
        val timestampsLoaded: Boolean = false,
    )

    private var activeSession: GithubEditorSession? = null
    private var activePath: String? = null
    private var activeSha: String? = null
    private var originalSource = ""
    private var draft = ""
    private var saving = false

    private val mutableUiState = MutableStateFlow(WorkspacesUiState())
    private val mutableEvents = MutableSharedFlow<WorkspacesEvent>(extraBufferCapacity = 4)
    val uiState = mutableUiState.asStateFlow()
    val events = mutableEvents.asSharedFlow()
    val branches =
        WorkspaceBranches(
            scope = viewModelScope,
            source = workspacesRepository,
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
        loadRepository(force = !route.license)
    }

    fun openDirectory(path: String) {
        if (saving) return
        if (path.isNotEmpty() && !isRepositoryContentPath(path)) return
        if (
            path == mutableUiState.value.path &&
                mutableUiState.value.entries is WorkspacesLoad.Ready
        )
            return
        openPath(path, force = false)
    }

    fun openFile(
        path: String,
        name: String,
        submoduleGitUrl: String? = null,
        anchor: String? = null,
    ) {
        if (saving || mutableUiState.value.pendingEditor != null) return
        if (!isRepositoryContentPath(path)) return
        val ref = mutableUiState.value.ref ?: return
        openEditor(name, path, ref, anchor, submoduleGitUrl != null)
    }

    fun navigateUp(): Boolean {
        val path = mutableUiState.value.path
        if (path.isEmpty()) return false
        openDirectory(path.substringBeforeLast('/', ""))
        return true
    }

    fun refresh() {
        if (
            saving ||
                fileJob?.isActive == true ||
                directoryJob?.isActive == true ||
                detailsJob?.isActive == true
        )
            return
        directoryCache.clear()
        val state = mutableUiState.value
        if (state.ref == null) loadRepository(force = true) else openPath(state.path, force = true)
    }

    fun retry() {
        val state = mutableUiState.value
        if (state.repository is WorkspacesLoad.Error) loadRepository(force = true)
        else state.ref?.let { loadDirectory(state.path, it, force = true, refreshCommit = false) }
    }

    fun retryCommit() {
        loadCommit(mutableUiState.value.path)
    }

    fun loadBranches() {
        branches.open()
    }

    fun switchBranch(branchRef: String) {
        val currentPath = mutableUiState.value.path
        if (!branches.contains(branchRef) || branchRef == mutableUiState.value.ref || saving) return
        directoryJob?.cancel()
        commitJob?.cancel()
        prefetchJobs.values.toList().forEach { it.cancel() }
        prefetchJobs.clear()
        cancelFileOpening()
        mutableUiState.value =
            mutableUiState.value.copy(
                ref = branchRef,
                path = currentPath,
                entries = WorkspacesLoad.Loading,
                commit = WorkspacesLoad.Loading,
                updatedAt = emptyMap(),
            )
        openPath(currentPath, force = false)
    }

    private fun bindAccount(account: String) {
        activeAccount = account
        accountGeneration++
        directoryGeneration++
        detailsJob?.cancel()
        directoryJob?.cancel()
        commitJob?.cancel()
        branches.reset()
        cancelFileOpening()
        saveJob?.cancel()
        prefetchJobs.values.toList().forEach { it.cancel() }
        prefetchJobs.clear()
        directoryCache.clear()
        activeSession?.complete()
        activeSession = null
        activePath = null
        activeSha = null
        originalSource = ""
        draft = ""
        saving = false
        mutableUiState.value = WorkspacesUiState()
        loadRepository()
    }

    private fun loadRepository(force: Boolean = false) {
        if (!isCurrentAccount(activeAccount)) return
        val account = activeAccount
        val generation = accountGeneration
        detailsJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(repository = WorkspacesLoad.Loading)
        detailsJob = viewModelScope.launch {
            try {
                val location = route.gitObject
                val (target, repositoryUrl) =
                    if (location != null) {
                        val linked =
                            workspacesRepository.resolveGitObject(
                                account,
                                route.owner,
                                route.name,
                                location,
                            )
                        val resolved = linked?.gitObject
                        resolved?.let {
                            WorkspaceTarget(it.ref, it.path, !location.directory, it.anchor)
                        } to linked?.repositoryUrl
                    } else {
                        val details =
                            contentRepository.details(account, route.owner, route.name).await(
                                force,
                            ) {
                                contentRepository.loadDetails(account, route.owner, route.name)
                            }
                        val defaultRef = details.defaultBranch?.let { "refs/heads/$it" }
                        val target = if (route.license && defaultRef != null) {
                            val path = contentRepository.loadLicensePath(route.owner, route.name)
                            WorkspaceTarget(defaultRef, path, file = true, anchor = null)
                        } else if (route.license) null else route.target(defaultRef)
                        target to details.htmlUrl
                    }
                ensureActive()
                if (generation != accountGeneration || !isCurrentAccount(account)) return@launch
                if (target == null) {
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            repository = WorkspacesLoad.Error(strings.LoadFailed),
                        )
                    return@launch
                }
                val url = repositoryUrl ?: "https://github.com/${route.owner}/${route.name}"
                mutableUiState.value =
                    mutableUiState.value.copy(
                        repository = WorkspacesLoad.Ready(url),
                        ref = target.ref,
                    )
                if (target.file && target.path.isNotEmpty()) {
                    val parent = target.path.substringBeforeLast('/', "")
                    openPath(parent, force = true)
                    openFile(
                        target.path,
                        target.path.substringAfterLast('/'),
                        anchor = target.anchor,
                    )
                } else {
                    openPath(target.path, force = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == accountGeneration && isCurrentAccount(account)) {
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            repository = WorkspacesLoad.Error(error.readErrorMessage()),
                        )
                }
            }
        }
    }

    private fun openPath(path: String, force: Boolean) {
        val ref = mutableUiState.value.ref ?: return
        if (path.isNotEmpty() && !isRepositoryContentPath(path)) return
        cancelFileOpening()
        commitJob?.cancel()
        prefetchJobs.remove(path)?.cancel()
        val cached = directoryCache[ref to path]
        if (cached != null && !force) {
            directoryGeneration++
            directoryJob?.cancel()
            mutableUiState.value =
                mutableUiState.value.copy(
                    path = path,
                    entries = WorkspacesLoad.Ready(cached.entries),
                    commit = cached.commit,
                    updatedAt = cached.updatedAt,
                )
            if (cached.commit == WorkspacesLoad.Loading) loadCommit(path)
            if (!cached.timestampsLoaded && cached.entries.any { !it.directory }) {
                val generation = directoryGeneration
                directoryJob = viewModelScope.launch {
                    applyUpdatedAt(path, cached.entries, ref, activeAccount, accountGeneration, generation)
                }
            }
            return
        }
        loadDirectory(path, ref, force)
    }

    private fun loadDirectory(path: String, ref: String, force: Boolean, refreshCommit: Boolean = true) {
        val account = activeAccount
        val generation = accountGeneration
        val directory = ++directoryGeneration
        directoryJob?.cancel()
        if (refreshCommit) commitJob?.cancel()
        cancelFileOpening()
        prefetchJobs.remove(path)?.cancel()
        mutableUiState.value =
            mutableUiState.value.copy(
                path = path,
                entries =
                    (mutableUiState.value.entries as? WorkspacesLoad.Ready)
                        ?.takeIf { mutableUiState.value.path == path }
                        ?.copy(refreshing = true, refreshError = null) ?: WorkspacesLoad.Loading,
                commit = mutableUiState.value.commit.takeIf { mutableUiState.value.path == path }
                    ?: WorkspacesLoad.Loading,
                updatedAt =
                    mutableUiState.value.updatedAt
                        .takeIf { mutableUiState.value.path == path }
                        .orEmpty(),
            )
        if (refreshCommit) loadCommit(path)
        directoryJob = viewModelScope.launch {
            try {
                val entries =
                    workspacesRepository.loadDirectory(
                        account,
                        route.owner,
                        route.name,
                        ref,
                        path,
                        requireTree = route.gitObject != null,
                    )
                ensureActive()
                if (!isCurrentAccount(account) || accountGeneration != generation) return@launch
                if (directory != directoryGeneration) return@launch
                if (force) directoryCache.remove(ref to path)
                // 目录先落地，提交卡与行内更新时间并发补齐，互不阻塞。
                mutableUiState.value =
                    mutableUiState.value.copy(
                        entries = WorkspacesLoad.Ready(entries),
                    )
                prefetchDirectories(entries, ref)
                retainDirectory(ref, path)
                applyUpdatedAt(path, entries, ref, account, generation, directory)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (directory != directoryGeneration) return@launch
                if (!isCurrentAccount(account) || accountGeneration != generation) return@launch
                if (error.invalidatesCachedContent()) {
                    forgetWorkspace(error)
                    return@launch
                }
                mutableUiState.value =
                    mutableUiState.value.copy(
                        entries =
                            (mutableUiState.value.entries as? WorkspacesLoad.Ready)?.copy(
                                refreshing = false,
                                refreshError = error.readErrorMessage(),
                            ) ?: WorkspacesLoad.Error(error.readErrorMessage()),
                    )
            }
        }
    }

    private suspend fun applyUpdatedAt(
        path: String,
        entries: List<WorkspaceEntry>,
        ref: String,
        account: String,
        generation: Long,
        directory: Long,
    ) {
        if (entries.isEmpty()) return
        val updatedAt =
            try {
                workspacesRepository.loadUpdatedAt(
                    account,
                    route.owner,
                    route.name,
                    ref,
                    entries.filterNot(WorkspaceEntry::directory).map(WorkspaceEntry::path),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }
        currentCoroutineContext().ensureActive()
        if (!isCurrentAccount(account) || accountGeneration != generation) return
        if (directory != directoryGeneration || mutableUiState.value.path != path) return
        mutableUiState.value = mutableUiState.value.copy(updatedAt = updatedAt)
        retainDirectory(ref, path, timestampsLoaded = true)
    }

    private fun loadCommit(path: String) {
        val ref = mutableUiState.value.ref ?: return
        val account = activeAccount
        val generation = accountGeneration
        val previous = mutableUiState.value.commit as? WorkspacesLoad.Ready
        mutableUiState.value = mutableUiState.value.copy(
            commit = previous?.copy(refreshing = true, refreshError = null) ?: WorkspacesLoad.Loading,
        )
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            val result =
                try {
                    WorkspacesLoad.Ready(
                        workspacesRepository.loadCommit(
                            account,
                            route.owner,
                            route.name,
                            ref,
                            path.ifEmpty { null },
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error.invalidatesCachedContent() && isCurrentAccount(account) && accountGeneration == generation && mutableUiState.value.ref == ref && mutableUiState.value.path == path) {
                        forgetWorkspace(error)
                        return@launch
                    }
                    previous?.copy(refreshing = false, refreshError = error.readErrorMessage())
                        ?: WorkspacesLoad.Error(error.readErrorMessage())
                }
            ensureActive()
            if (!isCurrentAccount(account) || accountGeneration != generation) return@launch
            if (mutableUiState.value.path != path || mutableUiState.value.ref != ref) return@launch
            mutableUiState.value = mutableUiState.value.copy(commit = result)
            retainDirectory(ref, path)
        }
    }

    private fun prefetchDirectories(entries: List<WorkspaceEntry>, ref: String) {
        entries.asSequence().filter(WorkspaceEntry::directory).take(2).forEach { entry ->
            if (directoryCache.containsKey(ref to entry.path) || prefetchJobs.containsKey(entry.path))
                return@forEach
            if (prefetchJobs.size >= 2) return@forEach
            val account = activeAccount
            val generation = accountGeneration
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val children =
                            workspacesRepository.loadDirectory(
                                account,
                                route.owner,
                                route.name,
                                ref,
                                entry.path,
                            )
                        ensureActive()
                        if (!isCurrentAccount(account) || accountGeneration != generation)
                            return@launch
                        // 未打开的子目录只预取条目，提交与逐文件历史留到目录可见时再读。
                        cacheDirectory(ref, entry.path, DirectorySnapshot(children))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // 预取失败留空，点击目录时再走可见的重试路径。
                    } finally {
                        if (prefetchJobs[entry.path] === currentCoroutineContext()[Job])
                            prefetchJobs.remove(entry.path)
                    }
                }
            prefetchJobs[entry.path] = job
            job.start()
        }
    }

    private fun openEditor(
        name: String,
        path: String,
        ref: String,
        anchor: String?,
        submodule: Boolean,
    ) {
        val language = githubCodeLanguageForFilename(name)
        val line = anchor?.takeIf { it.startsWith("L") }?.drop(1)?.toIntOrNull()?.takeIf { it > 0 }
        val markdownFile = language == "markdown" && line == null
        val canWrite = ref.startsWith("refs/heads/") && !submodule
        val account = activeAccount
        val generation = tokenStore.sessionGeneration.value
        val url = fileHtmlUrl(ref.removePrefix("refs/heads/"), path)
        cancelFileOpening()
        activeSession?.complete()
        activePath = path
        activeSha = null
        originalSource = ""
        draft = ""
        val showCommit = mutableStateOf(false)
        val commitForm = mutableStateOf<WorkspaceCommitDraft?>(null)
        var draftBase: String? = null
        val baseChanged = mutableStateOf(false)
        var commit: String? = null
        var editorPath = path
        var editorUrl = url
        var openMarkdownLink: (String) -> Boolean = { false }
        lateinit var session: GithubEditorSession
        fun current(path: String = editorPath) =
            editorPath == path &&
                activePath == path &&
                activeSession === session &&
                !session.closeRequested &&
                isCurrentAccount(account) &&
                generation == tokenStore.sessionGeneration.value &&
                mutableUiState.value.ref == ref
        session =
            GithubEditorSession(
                title = name,
                kind = if (markdownFile) GithubEditorKind.Markdown else GithubEditorKind.Code,
                source = "",
                language = language,
                editable = false,
                submitLabel = strings.CommitChanges,
                pageUrl = url,
                repositoryRootUrl = repositoryHtmlUrl(),
                scrollToAnchor = anchor,
                filePath = path,
                previewOnOpen = markdownFile,
                onOpenMarkdownLink =
                    if (markdownFile && !submodule) ({ targetUrl -> openMarkdownLink(targetUrl) })
                    else null,
                onSourceChange = { source ->
                    if (current() && activeSha != null && !saving) {
                        draft = source
                        val edit = WorkspaceFileDraft(draftBase, source)
                        draftStore.saveEncoded(account, fileDraftKey(ref, editorPath)) { Json.encodeToString(edit) }
                        activeSession?.update(submitEnabled = source != originalSource)
                    }
                },
                onSubmit =
                    if (canWrite)
                        ({
                            if (current() && activeSha != null && !saving) {
                                val target = editorPath
                                showCommit.value = true
                                commitForm.value = null
                                viewModelScope.launch {
                                    val encoded = draftStore.loadOrNull(account, commitDraftKey(ref, target))
                                    if (current(target) && showCommit.value) {
                                        try {
                                            commitForm.value = encoded?.let { Json.decodeFromString<WorkspaceCommitDraft>(it) }
                                                ?: WorkspaceCommitDraft(ref, strings.UpdateFile(session.title))
                                        } catch (error: SerializationException) {
                                            showCommit.value = false
                                            session.update(error = error.readErrorMessage())
                                        }
                                    }
                                }
                            }
                        })
                    else null,
                submissionSheet =
                    if (!canWrite) null
                    else
                        { editor ->
                            WorkspaceCommitSheet(
                                visible = showCommit.value,
                                editor = editor,
                                draft = commitForm.value,
                                baseChanged = baseChanged.value,
                                onDraftChange = { form ->
                                    commitForm.value = form
                                    draftStore.saveEncoded(account, commitDraftKey(ref, editorPath)) { Json.encodeToString(form) }
                                },
                                branches = branches,
                                onDismiss = { showCommit.value = false },
                                onConfirm = { form ->
                                    if (activeSession === editor && showCommit.value) saveFile(ref, form)
                                },
                            )
                        },
                onRequestEdit =
                    if (canWrite)
                        ({
                            val targetPath = editorPath
                            val revision = commit
                            if (
                                current(targetPath) &&
                                    revision != null &&
                                    !session.readingOriginal &&
                                    !session.editable
                            ) {
                                session.readingOriginal = true
                                session.update(error = null)
                                fileJob = viewModelScope.launch {
                                    try {
                                        val raw =
                                            withTimeoutOrNull(RequestTimeoutMillis) {
                                                workspacesRepository.loadFile(
                                                    account,
                                                    route.owner,
                                                    route.name,
                                                    revision,
                                                    targetPath,
                                                    editorUrl,
                                                    ref,
                                                )
                                            } ?: throw RepositoryContentTimeoutException()
                                        if (!current(targetPath)) return@launch
                                        val text = raw.text
                                        if (text == null || raw.sha == null) {
                                            session.update(error = strings.Unavailable)
                                        } else {
                                            activeSha = raw.sha
                                            originalSource = text
                                            val encoded = draftStore.loadOrNull(account, fileDraftKey(ref, targetPath))
                                            val restored = if (encoded != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                                Json.decodeFromString<WorkspaceFileDraft>(encoded)
                                            } else draftStore.loadOrNull(account, legacyFileDraftKey(ref, targetPath))?.let { WorkspaceFileDraft(null, it) }
                                            if (!current(targetPath)) return@launch
                                            draftBase = if (restored == null || restored.text == text) raw.sha else restored.blob
                                            baseChanged.value = draftBase != raw.sha
                                            draft = restored?.text ?: text
                                            session.readHtml = null
                                            session.update(
                                                source = draft,
                                                editable = true,
                                                submitEnabled = draft != text,
                                                error = strings.DraftBaseChanged.takeIf { baseChanged.value },
                                            )
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        if (current(targetPath))
                                            session.update(error = error.readErrorMessage())
                                    } finally {
                                        session.readingOriginal = false
                                    }
                                }
                            }
                        })
                    else null,
                onDismiss = {
                    if (activeSession === session) {
                        fileJob?.cancel()
                        activeSession = null
                        activePath = null
                        activeSha = null
                    }
                },
            )
        fun positionAtLine(text: String) {
            if (line == null) return
            var offset = 0
            var remaining = line - 1
            while (remaining > 0) {
                val newline = text.indexOf('\n', offset)
                if (newline < 0) break
                offset = newline + 1
                remaining--
            }
            if (remaining == 0) {
                val state = session.markdownState
                state.edit(state.value.copy(selection = TextRange(offset)))
            }
        }
        activeSession = session
        fun read(force: Boolean) {
            val targetPath = editorPath
            if (!current(targetPath)) return
            session.fileReadStatus = GithubFileReadStatus.Loading
            fileJob = viewModelScope.launch {
                try {
                    val preview =
                        withTimeoutOrNull(RequestTimeoutMillis) {
                            contentRepository
                                .preview(account, route.owner, route.name, ref, targetPath)
                                .await(
                                    force,
                                ) {
                                    workspacesRepository.loadPreview(
                                        account,
                                        route.owner,
                                        route.name,
                                        ref,
                                        targetPath,
                                    )
                                }
                        } ?: throw RepositoryContentTimeoutException()
                    if (!current(targetPath)) return@launch
                    if (preview.lines == null && (!markdownFile || preview.html == null)) {
                        session.fileReadStatus =
                            GithubFileReadStatus.Failed(strings.Unavailable, null)
                    } else {
                        commit = preview.commit
                        session.readHtml = preview.html.takeIf { markdownFile }
                        session.update(source = preview.lines.orEmpty())
                        positionAtLine(session.source)
                        session.fileReadStatus = null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (current(targetPath))
                        session.fileReadStatus =
                            GithubFileReadStatus.Failed(error.readErrorMessage()) { read(true) }
                }
            }
        }
        openMarkdownLink = handler@{ targetUrl ->
            if (
                saving ||
                    activeSession !== session ||
                    (session.editable && session.source != originalSource)
            )
                return@handler false
            val linkedFile =
                resolveMarkdownEditorLink(targetUrl, route.owner, route.name, ref)
                    ?: return@handler false
            val nextPath = linkedFile.path
            val nextName = nextPath.substringAfterLast('/')
            if (!isCurrentAccount(account) || generation != tokenStore.sessionGeneration.value)
                return@handler false
            val nextUrl =
                fileHtmlUrl(ref.removePrefix("refs/heads/"), nextPath) ?: return@handler false
            cancelFileOpening()
            editorPath = nextPath
            editorUrl = nextUrl
            activePath = nextPath
            activeSha = null
            commit = null
            originalSource = ""
            draft = ""
            session.openMarkdownDocument(
                title = nextName,
                filePath = nextPath,
                pageUrl = nextUrl,
                repositoryRootUrl = repositoryHtmlUrl(),
                scrollToAnchor = linkedFile.anchor,
            )
            read(false)
            true
        }
        if (submodule)
            session.fileReadStatus = GithubFileReadStatus.Failed(strings.Unavailable, null)
        else read(false)
        mutableUiState.value = mutableUiState.value.copy(pendingEditor = session)
    }

    fun consumeEditor(session: GithubEditorSession): Boolean {
        if (mutableUiState.value.pendingEditor !== session || !isCurrentAccount(activeAccount))
            return false
        mutableUiState.value = mutableUiState.value.copy(pendingEditor = null)
        return true
    }

    private fun fileDraftKey(ref: String, path: String): String =
        "workspace-file-v2/${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}/${ref.length}:$ref/$path"

    private fun legacyFileDraftKey(ref: String, path: String): String =
        "workspace-file/${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}/$ref/$path"

    private fun commitDraftKey(ref: String, path: String): String =
        "workspace-message/${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}/${ref.length}:$ref/$path"

    private fun saveFileKey(ref: String, path: String) =
        MutationKey(
            MutationKind.FILE_COMMIT,
            "${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}:$ref:$path",
            String.serializer(),
        )

    private fun saveFile(sourceRef: String, form: WorkspaceCommitDraft) {
        val (ref, headline, body) = form
        val path = activePath ?: return
        val sha = activeSha ?: return
        val session = activeSession ?: return
        if (
            saving ||
                session.submitting ||
                !session.submitEnabled ||
                session.closeRequested ||
                !ref.startsWith("refs/heads/") ||
                headline.isBlank() ||
                !isCurrentAccount(activeAccount)
        )
            return
        val account = activeAccount
        val generation = accountGeneration
        val token = tokenStore.accessToken.value
        val submittedText = draft
        val draftKey = fileDraftKey(sourceRef, path)
        val legacyKey = legacyFileDraftKey(sourceRef, path)
        val formKey = commitDraftKey(sourceRef, path)
        val htmlUrl = fileHtmlUrl(ref.removePrefix("refs/heads/"), path)
        val commitKey = saveFileKey(ref, path)
        val tags =
            setOf(
                CacheTag(DataDomain.Content, "${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}"),
                repositoryCacheTag(route.owner, route.name),
            )
        saving = true
        session.update(submitting = true, submitEnabled = false, error = null)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val store = draftStore
            val encoded = Json.encodeToString(form)
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Json.encodeToString(WorkspaceFileDraft(sha, submittedText)) }
            val savedDraft = store.saveConfirmed(account, draftKey, file) && store.saveConfirmed(account, formKey, encoded)
            if (!savedDraft) {
                saving = false
                if (isCurrentAccount(account) && generation == accountGeneration) {
                    session.update(
                        submitting = false,
                        submitEnabled = true,
                        error = GithubTxt.current.Common.MutationNotSubmitted,
                    )
                }
                return@launch
            }
            val coord = workspacesRepository.mutations(account)
            var capturedError: Exception? = null
            val receipt =
                coord.submitCommand(
                    key = commitKey,
                    tags = tags,
                    onConfirmed = {
                        store.removeIfUnchanged(account, draftKey, file)
                        store.removeIfUnchanged(account, legacyKey, submittedText)
                        store.removeIfUnchanged(account, formKey, encoded)
                    },
                    send = {
                        try {
                            withTimeout(RequestTimeoutMillis) {
                                workspacesRepository.commitFile(
                                    account,
                                    route.owner,
                                    route.name,
                                    ref,
                                    path,
                                    sha,
                                    submittedText,
                                    headline.trim(),
                                    body,
                                ).also { blob ->
                                    contentRepository.fileSaved(account, route.owner, route.name, ref, path,
                                        RepositoryTextContent(text = submittedText, htmlUrl = htmlUrl, sha = blob, path = path))
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            val timeout = WorkspacesTimeoutException()
                            capturedError = timeout
                            throw timeout
                        } catch (error: Exception) {
                            capturedError = error
                            throw error
                        }
                    },
                    readBack = { null },
                )
            val cmd = receipt?.await() ?: coord.currentCommand(commitKey)
            saving = false
            if (!isCurrentAccount(account) || generation != accountGeneration || token != tokenStore.accessToken.value) return@launch
            if (receipt != null && cmd.phase == WritePhase.IDLE) {
                if (activeSession === session && activePath == path && draft == submittedText) {
                    originalSource = submittedText
                    session.update(source = submittedText, editable = false, submitting = false, submitEnabled = false, error = null)
                    session.complete()
                }
                mutableEvents.tryEmit(WorkspacesEvent.Saved)
                if (mutableUiState.value.ref == ref) {
                    directoryCache.remove(ref to mutableUiState.value.path)
                    loadDirectory(mutableUiState.value.path, ref, force = true)
                }
            } else if (activeSession === session) {
                val unknown = cmd.phase == WritePhase.UNCERTAIN
                session.update(submitting = false, submitEnabled = !unknown && cmd.phase != WritePhase.PENDING,
                    error = if (unknown) GithubTxt.current.Common.MutationUncertain else capturedError?.writeErrorMessage()
                        ?: cmd.message ?: GithubTxt.current.Common.MutationNotSubmitted)
            }
        }
    }

    private fun repositoryHtmlUrl(): String? =
        (mutableUiState.value.repository as? WorkspacesLoad.Ready)?.value

    /** 与仓库内容页一致：分支名保留 `/`，路径逐段编码；作为编辑器浏览入口的兜底链接。 */
    private fun fileHtmlUrl(
        ref: String,
        path: String,
        url: String? = repositoryHtmlUrl(),
    ): String? {
        val root = url?.trimEnd('/') ?: return null
        return "$root/blob/${Uri.encode(ref, "/")}/${Uri.encode(path, "/")}"
    }

    private fun cancelFileOpening() {
        val job = fileJob
        fileJob = null
        job?.cancel()
    }

    private fun forgetWorkspace(error: Throwable) {
        directoryGeneration++
        directoryJob?.cancel()
        commitJob?.cancel()
        prefetchJobs.values.toList().forEach { it.cancel() }
        prefetchJobs.clear()
        directoryCache.clear()
        branches.reset()
        cancelFileOpening()
        activeSession?.complete()
        activeSession = null
        activePath = null
        activeSha = null
        originalSource = ""
        draft = ""
        mutableUiState.value = WorkspacesUiState(repository = WorkspacesLoad.Error(error.readErrorMessage()))
    }

    private fun retainDirectory(ref: String, path: String, timestampsLoaded: Boolean = false) {
        val state = mutableUiState.value
        if (state.ref != ref || state.path != path) return
        val entries = state.entries as? WorkspacesLoad.Ready ?: return
        val complete = timestampsLoaded || directoryCache[ref to path]?.timestampsLoaded == true
        cacheDirectory(ref, path, DirectorySnapshot(entries.value, state.commit, state.updatedAt, complete))
    }

    private fun cacheDirectory(ref: String, path: String, snapshot: DirectorySnapshot) {
        directoryCache[ref to path] = snapshot
        while (directoryCache.size > MaxCachedDirectories) {
            val oldest = directoryCache.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    private fun currentAccount(): String =
        tokenStore.accountLogin.value.trim().lowercase(Locale.ROOT)

    private fun isCurrentAccount(account: String): Boolean =
        account.isNotBlank() && currentAccount() == account

    private fun Throwable.readErrorMessage(): String =
        when (this) {
            is RepositoryContentTimeoutException,
            is WorkspacesTimeoutException,
            is TimeoutCancellationException -> GithubTxt.current.Repository.RequestTimeout
            is WorkspacesFileNotFound -> strings.Unavailable
            is moe.yumeyuka.yumehub.data.repository.WorkspaceFileConflict ->
                GithubTxt.current.Repository.FileConflict
            is WorkspacesGraphQlException,
            is SerializationException -> GithubTxt.current.Repository.ParseFailed
            is HttpException ->
                when (code()) {
                    401 -> GithubTxt.current.Repository.SessionExpired
                    403 -> GithubTxt.current.Repository.ReadForbidden
                    404 -> GithubTxt.current.Repository.ReadNotFound
                    else -> GithubTxt.current.Repository.HttpFailed(code())
                }
            is IOException -> GithubTxt.current.Repository.NetworkFailed
            else -> strings.LoadFailed
        }

    private fun Throwable.writeErrorMessage(): String =
        when (this) {
            is moe.yumeyuka.yumehub.data.repository.WorkspaceFileConflict ->
                GithubTxt.current.Repository.FileConflict
            is moe.yumeyuka.yumehub.data.repository.GithubPageException ->
                message ?: GithubTxt.current.Repository.WriteRejected
            is TimeoutCancellationException,
            is WorkspacesTimeoutException -> GithubTxt.current.Repository.RequestTimeout
            is HttpException ->
                when (code()) {
                    401 -> GithubTxt.current.Repository.SessionExpired
                    403 -> GithubTxt.current.Repository.WriteForbidden
                    404 -> GithubTxt.current.Repository.WriteNotFound
                    409 -> GithubTxt.current.Repository.FileConflict
                    422 -> GithubTxt.current.Repository.WriteRejected
                    else -> GithubTxt.current.Repository.HttpFailed(code())
                }
            is IOException -> GithubTxt.current.Repository.NetworkFailed
            is SerializationException -> GithubTxt.current.Repository.ParseFailed
            else -> GithubTxt.current.Repository.SaveFailed
        }

    private companion object {
        const val RequestTimeoutMillis = 20_000L
        const val MaxCachedDirectories = 8
    }
}
