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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityRepository
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
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val open = rememberGithubUriHandler()
    val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
    GithubPagingContent(pages, contentKey = filter) { paging ->
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
        DiscussionCreationSheet(
            visible = creationVisible && route.name.isNotBlank(),
            categories = filterOptions.discussionCategories,
            optionsFailed = viewModel.optionsFailed,
            onRetry = viewModel::loadFilterOptions,
            body = viewModel.creationBody,
            onEditBody = {
                host.onOpenEditor(viewModel.bodyEditor(strings.WorkList.CreationBody, strings.Common.Save))
            },
            onDismiss = {
                creationVisible = false
                viewModel.clearCreationBody()
            },
            onCreate = { categoryId, title, body ->
                viewModel.createDiscussion(categoryId, title, body)
            },
            onCreated = { number ->
                items.refresh()
                if (host.navigationEnabled) onOpenDiscussion(route.owner, route.name, number)
            },
        )
    }
}

internal class DiscussionListViewModel(
    private val source: RepositoryActivityRepository,
    private val route: RepositoryActivityRoute,
    private val account: String,
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

    fun itemsFor(filter: CollaborationListFilter) =
        source.discussionItems(account, route.owner, route.name, personal, filter)

    fun clearCreationBody() {
        creationBody = ""
    }

    fun bodyEditor(title: String, saveLabel: String): GithubEditorSession {
        lateinit var editor: GithubEditorSession
        editor = GithubEditorSession(
            title = title,
            kind = GithubEditorKind.Markdown,
            source = creationBody,
            repositoryRootUrl = "https://github.com/${route.owner}/${route.name}",
            onSourceChange = { creationBody = it },
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

    suspend fun createDiscussion(categoryId: String, title: String, body: String): Int =
        source.createDiscussion(
            account,
            mutableOptions.value.repositoryId ?: throw GithubPageException(),
            categoryId,
            title,
            body,
        )
}
