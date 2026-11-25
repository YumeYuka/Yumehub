package moe.yumeyuka.yumehub.feature.repositoryactivity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessagesSquare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityRepository
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubDiscussionCard
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemPlaceholder
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorKind
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun DiscussionListPage(
    route: RepositoryActivityRoute,
    account: String,
    onOpenDiscussion: (owner: String, name: String, number: Int) -> Unit,
) {
    var creationVisible by rememberSaveable { mutableStateOf(route.createInitially) }
    val viewModel: DiscussionListViewModel =
        koinViewModel(
            key = "repository-discussions:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filterOptions by viewModel.options.collectAsStateWithLifecycle()
    val creationState by viewModel.creationState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(creationVisible) {
        viewModel.setCreationVisible(creationVisible)
    }
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val open = rememberGithubUriHandler()
    val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
    GithubPagingContent(
        pages,
        contentKey = filter,
        revalidation = viewModel.revalidation,
        onRetryRevalidation = viewModel::retryRevalidation,
    ) { paging ->
        val items = paging.items
        val slots =
            rememberGithubPagingSlots(
                items,
                "discussions:$filter",
                paging.initialLoading,
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
          GithubPage(
            title = route.heading?.takeIf(String::isNotBlank) ?: strings.Repository.Discussions,
            listState = paging.listState,
            listTransition = paging.transition,
            itemSpacing = 12.dp,
            actions = actions,
          ) {
            githubPagingItems(
                slots,
                contentType = "discussion-card",
                placeholder = { GithubWorkItemPlaceholder() },
            ) { entry ->
                val target =
                    if (route.name.isNotBlank()) {
                        route.owner to route.name
                    } else {
                        entry.repository
                            ?.split("/")
                            ?.takeIf { parts ->
                                parts.size == 2 && parts.all { part -> part.isNotBlank() }
                            }
                            ?.let { it[0] to it[1] }
                    }
                GithubDiscussionCard(
                    item = entry,
                    onClick =
                        if (target != null) {
                            { onOpenDiscussion(target.first, target.second, entry.number) }
                        } else {
                            entry.url?.let { url -> { open(url) } }
                        },
                )
            }
            collaborationPagingStatus(
                items = items,
                title = strings.Repository.Discussions,
                loadingPlaceholder = {},
                emptyMessage = strings.WorkList.EmptyDiscussions,
                emptyIcon = Lucide.MessagesSquare,
                emptyVerticalBias = -0.2f,
            )
            pageSearchClearance(searching)
          }
        }
        androidx.compose.runtime.LaunchedEffect(creationState.createdNumber) {
            val number = creationState.createdNumber ?: return@LaunchedEffect
            val wasVisible = creationVisible
            viewModel.consumeCreatedDiscussion()
            creationVisible = false
            items.refresh()
            if (wasVisible && host.navigationEnabled) {
                onOpenDiscussion(route.owner, route.name, number)
            }
        }
        DiscussionCreationSheet(
            visible = creationVisible && route.name.isNotBlank(),
            creationState = creationState,
            categories = filterOptions.discussionCategories,
            optionsFailed = viewModel.optionsFailed,
            onRetry = viewModel::loadFilterOptions,
            onTitleChange = viewModel::updateCreationTitle,
            body = viewModel.creationBody,
            onEditBody = {
                host.onOpenEditor(viewModel.bodyEditor(strings.WorkList.CreationBody, strings.Common.Save))
            },
            onDismiss = { creationVisible = false },
            onSubmit = { categoryId, title, body ->
                viewModel.submitDiscussion(categoryId, title, body)
            },
            onAcknowledgeUncertain = viewModel::acknowledgeDiscussionUncertain,
        )
    }
}

internal data class DiscussionCreationState(
    val title: String = "",
    val draftRestored: Boolean = false,
    val visible: Boolean = false,
    val submitting: Boolean = false,
    val failed: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
    val createdNumber: Int? = null,
)

internal class DiscussionListViewModel(
    private val source: RepositoryActivityRepository,
    private val route: RepositoryActivityRoute,
    private val account: String,
    private val draftStore: CommentDraftStore? = null,
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

    fun itemsFor(filter: CollaborationListFilter) =
        source.discussionItems(account, route.owner, route.name, personal, filter)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val revalidation: StateFlow<RevalidationState> =
        mutableFilter
            .flatMapLatest { f ->
                source.revalidation(account, source.discussionItemsKey(route.owner, route.name, personal, f))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        source.retryRevalidation(
            account,
            source.discussionItemsKey(route.owner, route.name, personal, mutableFilter.value),
        )
    }

    fun clearCreationBody() {
        creationBody = ""
        draftStore?.remove(account, "$draftPrefix/body")
        draftStore?.remove(account, "$draftPrefix/title")
    }

    fun setCreationVisible(visible: Boolean) {
        mutableCreationState.update { it.copy(visible = visible) }
        if (visible && !mutableCreationState.value.draftRestored) {
            viewModelScope.launch {
                val restoredTitle = draftStore?.loadOrNull(account, "$draftPrefix/title").orEmpty()
                val restoredBody = draftStore?.loadOrNull(account, "$draftPrefix/body").orEmpty()
                if (creationBody.isBlank() && restoredBody.isNotBlank()) creationBody = restoredBody
                mutableCreationState.update { current ->
                    if (current.draftRestored) current
                    else
                        current.copy(
                            title = current.title.ifBlank { restoredTitle },
                            draftRestored = true,
                        )
                }
            }
        }
        ensureCreationObserver()
    }

    fun updateCreationTitle(title: String) {
        if (mutableCreationState.value.submitting) return
        mutableCreationState.update { it.copy(title = title, failed = false) }
        draftStore?.save(account, "$draftPrefix/title", title)
    }

    fun bodyEditor(title: String, saveLabel: String): GithubEditorSession {
        lateinit var editor: GithubEditorSession
        editor = GithubEditorSession(
            title = title,
            kind = GithubEditorKind.Markdown,
            source = creationBody,
            repositoryRootUrl = "https://github.com/${route.owner}/${route.name}",
            onSourceChange = {
                creationBody = it
                draftStore?.save(account, "$draftPrefix/body", it)
            },
            submitLabel = saveLabel,
            submitEnabled = true,
            onSubmit = { editor.complete() },
        )
        return editor
    }

    init {
        loadFilterOptions()
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
        if (current.submitting || current.uncertain) return
        mutableCreationState.update {
            it.copy(title = title, submitting = true, failed = false, createdNumber = null)
        }
        viewModelScope.launch {
            val savedTitle = draftStore?.saveConfirmed(account, "$draftPrefix/title", title) ?: true
            val savedBody = draftStore?.saveConfirmed(account, "$draftPrefix/body", body) ?: true
            if (!savedTitle || !savedBody) {
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
            val accepted =
                coord.submitCommand(
                    key = creationKey,
                    tags = tags,
                    send = { createDiscussion(categoryId, title.trim(), body) },
                    readBack = { null },
                )
            if (!accepted) {
                val opId =
                    coord.pending.value.firstOrNull {
                        it.kind == creationKey.kind && it.target == creationKey.target
                    }?.operationId
                mutableCreationState.update {
                    it.copy(
                        submitting = false,
                        failed = true,
                        uncertain = true,
                        uncertainOperationId = opId,
                    )
                }
            }
        }
    }

    fun consumeCreatedDiscussion() {
        mutableCreationState.update { it.copy(createdNumber = null) }
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
            mutableCreationState.update {
                it.copy(uncertain = false, uncertainOperationId = null, failed = false)
            }
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
                when (cmd.phase) {
                    WritePhase.PENDING ->
                        mutableCreationState.update { it.copy(submitting = true, failed = false) }
                    WritePhase.IDLE -> {
                        val number = cmd.result
                        if (number != null) {
                            clearCreationBody()
                            val showCreated = mutableCreationState.value.visible
                            mutableCreationState.value =
                                DiscussionCreationState(
                                    draftRestored = true,
                                    visible = false,
                                    createdNumber = number.takeIf { showCreated },
                                )
                        } else {
                            mutableCreationState.update {
                                it.copy(submitting = false, uncertain = false, uncertainOperationId = null)
                            }
                        }
                    }
                    WritePhase.REJECTED ->
                        mutableCreationState.update {
                            it.copy(submitting = false, failed = true, uncertain = false)
                        }
                    WritePhase.UNCERTAIN -> {
                        val opId =
                            coord.pending.value.firstOrNull {
                                it.kind == creationKey.kind && it.target == creationKey.target
                            }?.operationId
                        mutableCreationState.update {
                            it.copy(
                                submitting = false,
                                failed = true,
                                uncertain = true,
                                uncertainOperationId = opId,
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun createDiscussion(categoryId: String, title: String, body: String): Int =
        source.createDiscussion(
            account,
            mutableOptions.value.repositoryId ?: throw GithubPageException(),
            categoryId,
            title,
            body,
        )
}
