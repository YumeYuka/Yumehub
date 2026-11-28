package moe.yumeyuka.yumehub.feature.repositoryactivity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.dto.DiscussionCreationDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.core.model.hasActiveCriteria
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.DiscussionCard
import moe.yumeyuka.yumehub.ui.component.PagingContent
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pagingItems
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.EditorSessionKind
import moe.yumeyuka.yumehub.ui.navigation.EditorSession
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberPageLinkHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun DiscussionListPage(
    route: RepositoryActivityRoute,
    account: String,
    onOpenDiscussion: (owner: String, name: String, number: Int) -> Unit,
) {
    val viewModel: DiscussionListViewModel =
        koinViewModel(
            key = "repository-discussions:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filtered = filter.hasActiveCriteria(CollaborationStateFilter.Open, viewModel.scopes.first())
    val filterOptions by viewModel.options.collectAsStateWithLifecycle()
    val creationState by viewModel.creationState.collectAsStateWithLifecycle()
    val strings = LocalAppStrings.current
    val host = requireNotNull(LocalAppPageHost.current)
    val open = rememberPageLinkHandler()
    val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
    PagingContent(
        pages,
        contentKey = Triple(account, route, filter),
        revalidation = remember(viewModel, filter) { viewModel.revalidationFor(filter) },
        onRetryRevalidation = { viewModel.retryRevalidation(filter) },
    ) { paging ->
        val items = paging.items
        val slots =
            rememberPagingSlots(
                items,
                "discussions:$filter",
                paging.initialLoading,
                refreshDomains = emptySet(),
            ) {
                it.nodeId ?: "${it.repository}#${it.number}"
            }
        WorkListControls(
            kind = null,
            filter = filter,
            scopes = viewModel.scopes,
            onQueryChange = viewModel::setQuery,
            onApply = viewModel::selectFilter,
            labels = filterOptions.labels,
            discussionCategories = filterOptions.categories,
        ) { actions, searching ->
          AppPage(
            title = route.heading?.takeIf(String::isNotBlank) ?: strings.Repository.Discussions,
            listState = paging.listState,
            itemSpacing = 12.dp,
            actions = actions,
          ) {
            pagingItems(
                slots,
                contentType = "discussion-card",
            ) { entry ->
                val target =
                    if (route.name.isNotBlank()) {
                        route.owner to route.name
                    } else {
                        entry?.repository
                            ?.split("/")
                            ?.takeIf { parts ->
                                parts.size == 2 && parts.all { part -> part.isNotBlank() }
                            }
                            ?.let { it[0] to it[1] }
                    }
                DiscussionCard(
                    item = entry,
                    onClick =
                        if (entry != null && target != null) {
                            { onOpenDiscussion(target.first, target.second, entry.number) }
                        } else {
                            entry?.url?.let { url -> { open(url) } }
                        },
                )
            }
            collaborationPagingStatus(
                slots = slots,
                title = strings.Repository.Discussions,
                emptyMessage = if (filtered) strings.EditorSearch.NoMatches else strings.WorkList.EmptyDiscussions,
                emptyScene = if (filtered) EmptyScene.Search else EmptyScene.Inbox,
                emptyVerticalBias = -0.2f,
            )
            pageSearchClearance(searching)
          }
        }
        androidx.compose.runtime.LaunchedEffect(host, viewModel) {
            viewModel.created.collect { number -> if (host.navigationEnabled) onOpenDiscussion(route.owner, route.name, number) }
        }
        DiscussionCreationSheet(
            visible = creationState.visible && route.name.isNotBlank(),
            creationState = creationState,
            categories = filterOptions.discussionCategories,
            optionsFailed = viewModel.optionsFailed,
            onRetry = viewModel::loadFilterOptions,
            onTitleChange = viewModel::updateCreationTitle,
            onCategoryChange = viewModel::updateCategory,
            body = viewModel.creationBody,
            onEditBody = {
                host.onOpenEditor(viewModel.bodyEditor(strings.WorkList.CreationBody, strings.Common.Save))
            },
            onDismiss = { viewModel.setCreationVisible(false) },
            onSubmit = { categoryId, title, body ->
                viewModel.submitDiscussion(categoryId, title, body)
            },
            onAcknowledgeUncertain = viewModel::acknowledgeDiscussionUncertain,
        )
    }
}

internal data class DiscussionCreationState(
    val title: String = "",
    val categoryId: String? = null,
    val formRevision: Long = 0L,
    val draftRestored: Boolean = false,
    val visible: Boolean = false,
    val submitting: Boolean = false,
    val failed: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
)

internal class DiscussionListViewModel(
    private val source: RepositoryActivityRepository,
    private val route: RepositoryActivityRoute,
    private val account: String,
    private val draftStore: CommentDraftStore,
) : ViewModel() {
    private val personal = route.kind == RepositoryActivityKind.PersonalDiscussions
    val scopes =
        if (personal) {
            listOf(
                CollaborationScopeFilter.CreatedByMe,
                CollaborationScopeFilter.Commented,
            )
        } else {
            listOf(
                CollaborationScopeFilter.All,
                CollaborationScopeFilter.CreatedByMe,
                CollaborationScopeFilter.Commented,
            )
        }
    private val mutableFilter =
        MutableStateFlow(
            CollaborationListFilter(
                scope =
                    if (personal) CollaborationScopeFilter.CreatedByMe
                    else CollaborationScopeFilter.All,
            ),
        )
    val filter = mutableFilter.asStateFlow()
    private val mutableOptions = MutableStateFlow(CollaborationFilterOptions())
    val options = mutableOptions.asStateFlow()
    private var optionsRequested = false
    var optionsFailed by mutableStateOf(false)
        private set
    var creationBody by mutableStateOf("")
        private set
    private val draftPrefix =
        "create-discussion/${route.owner.lowercase()}/${route.name.lowercase()}"
    private val creationKey =
        MutationKey(
            MutationKind.DISCUSSION_CREATE,
            "${route.owner.lowercase()}/${route.name.lowercase()}",
            Int.serializer(),
        )
    private val mutableCreationState = MutableStateFlow(DiscussionCreationState())
    val creationState = mutableCreationState.asStateFlow()
    private var creationObserverStarted = false
    val created = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private var submittingHere = false
    private var navigateOnSuccess = false

    fun itemsFor(filter: CollaborationListFilter) =
        source.discussionItems(account, route.owner, route.name, personal, filter)

    fun revalidationFor(filter: CollaborationListFilter) =
        source.revalidation(account, source.discussionItemsKey(route.owner, route.name, personal, filter))

    fun retryRevalidation(filter: CollaborationListFilter) =
        source.retryRevalidation(account, source.discussionItemsKey(route.owner, route.name, personal, filter))

    private fun saveDraft() {
        val state = mutableCreationState.value
        val draft = DiscussionCreationDraft(state.title, creationBody, state.categoryId)
        draftStore.saveEncoded(account, draftPrefix) { Json.encodeToString(draft) }
    }

    fun setCreationVisible(visible: Boolean) {
        if (!visible) navigateOnSuccess = false
        mutableCreationState.update { it.copy(visible = visible) }
        if (visible && !mutableCreationState.value.draftRestored) {
            viewModelScope.launch {
                try {
                    val encoded = draftStore.loadOrNull(account, draftPrefix)
                    val restored = if (encoded != null) Json.decodeFromString<DiscussionCreationDraft>(encoded)
                        else DiscussionCreationDraft(draftStore.load(account, "$draftPrefix/title"), draftStore.load(account, "$draftPrefix/body"))
                    if (!mutableCreationState.value.draftRestored) {
                        creationBody = restored.body
                        mutableCreationState.update { it.copy(title = restored.title, categoryId = restored.categoryId, draftRestored = true) }
                    }
                } catch (_: kotlinx.serialization.SerializationException) {
                    mutableCreationState.update { it.copy(failed = true) }
                }
            }
        }
        ensureCreationObserver()
    }

    fun updateCreationTitle(title: String) {
        val current = mutableCreationState.value
        if (!current.draftRestored || current.submitting || current.uncertain || current.title == title) return
        mutableCreationState.update { it.copy(title = title, failed = false) }
        saveDraft()
    }

    fun updateCategory(id: String) {
        val current = mutableCreationState.value
        if (!current.draftRestored || current.submitting || current.uncertain || current.categoryId == id) return
        mutableCreationState.update { it.copy(categoryId = id) }
        saveDraft()
    }

    fun bodyEditor(title: String, saveLabel: String): EditorSession {
        lateinit var editor: EditorSession
        editor = EditorSession(
            title = title,
            kind = EditorSessionKind.Markdown,
            source = creationBody,
            repositoryRootUrl = "https://github.com/${route.owner}/${route.name}",
            onSourceChange = {
                creationBody = it
                saveDraft()
            },
            submitLabel = saveLabel,
            submitEnabled = true,
            onSubmit = { editor.complete() },
        )
        return editor
    }

    init {
        loadFilterOptions()
        if (!personal) setCreationVisible(route.createInitially)
    }

    fun loadFilterOptions() {
        if (optionsRequested || personal) return
        optionsRequested = true
        optionsFailed = false
        viewModelScope.launch {
            try {
                mutableOptions.value =
                    source.discussionFilterOptions(account, route.owner, route.name)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                optionsRequested = false
                optionsFailed = true
            }
        }
    }

    fun selectFilter(filter: CollaborationListFilter) {
        if (filter.scope in scopes && !filter.draftOnly) {
            mutableFilter.value = filter.copy(searchQuery = mutableFilter.value.searchQuery)
        }
    }

    fun setQuery(query: String) {
        mutableFilter.value = mutableFilter.value.copy(searchQuery = query.trim())
    }

    fun submitDiscussion(categoryId: String, title: String, body: String) {
        val current = mutableCreationState.value
        if (!current.draftRestored || current.submitting || current.uncertain || title.isBlank()) return
        val repoId = mutableOptions.value.repositoryId ?: return
        submittingHere = true
        navigateOnSuccess = current.visible
        mutableCreationState.update {
            it.copy(title = title, categoryId = categoryId, submitting = true, failed = false)
        }
        viewModelScope.launch {
            val store = draftStore
            val key = draftPrefix
            val login = account
            val repo = source
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                Json.encodeToString(DiscussionCreationDraft(title, body, categoryId))
            }
            if (!store.saveConfirmed(login, key, encoded)) {
                submittingHere = false
                mutableCreationState.update { it.copy(submitting = false, failed = true) }
                return@launch
            }
            val coord = source.mutations(account)
            ensureCreationObserver(coord)
            val tags =
                setOf(
                    CacheTag(DataDomain.Work, null),
                    repositoryCacheTag(route.owner, route.name),
                )
            val receipt =
                coord.submitCommand(
                    key = creationKey,
                    tags = tags,
                    onConfirmed = {
                        store.removeIfUnchanged(login, key, encoded)
                        store.removeIfUnchanged(login, "$key/title", title)
                        store.removeIfUnchanged(login, "$key/body", body)
                    },
                    send = { repo.createDiscussion(login, repoId, categoryId, title.trim(), body) },
                    readBack = { null },
                )
            val command = receipt?.await() ?: coord.currentCommand(creationKey)
            submittingHere = false
            val number = command.result
            if (receipt != null && command.phase == WritePhase.IDLE && number != null) {
                val navigate = navigateOnSuccess && mutableCreationState.value.visible
                creationBody = ""
                mutableCreationState.value = DiscussionCreationState(draftRestored = true, formRevision = mutableCreationState.value.formRevision + 1,
                    visible = mutableCreationState.value.visible && !navigate)
                if (navigate) created.tryEmit(number)
            } else {
                mutableCreationState.update { it.copy(submitting = command.phase == WritePhase.PENDING,
                    failed = command.phase != WritePhase.PENDING, uncertain = command.phase == WritePhase.UNCERTAIN,
                    uncertainOperationId = command.submissionId) }
            }
        }
    }


    fun acknowledgeDiscussionUncertain() {
        val opId = mutableCreationState.value.uncertainOperationId
        viewModelScope.launch {
            val coord = source.mutations(account)
            val id =
                opId
                    ?: coord.pending.value
                        .firstOrNull { it.kind == creationKey.kind && it.target == creationKey.target }
                        ?.operationId
            if (id != null) coord.acknowledge(id)
        }
    }

    private fun ensureCreationObserver(
        existing: moe.yumeyuka.yumehub.data.cache.MutationCoordinator? = null,
    ) {
        if (creationObserverStarted) return
        creationObserverStarted = true
        viewModelScope.launch {
            val coord = existing ?: source.mutations(account)
            coord.observeCommand(creationKey).collect { cmd ->
                if (!submittingHere) mutableCreationState.update { state ->
                    state.copy(submitting = cmd.phase == WritePhase.PENDING, uncertain = cmd.phase == WritePhase.UNCERTAIN,
                        uncertainOperationId = cmd.submissionId.takeIf { cmd.phase == WritePhase.UNCERTAIN },
                        failed = state.failed && !state.uncertain)
                }
            }
        }
    }

}
