package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.hasActiveCriteria
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.PagingContent
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 仓库议题／拉取请求列表的路由键。 */
data class WorkListRoute(
    val owner: String,
    val name: String,
    val kind: CollaborationKind,
    val createInitially: Boolean = false,
) : AppRoute

@Composable
fun WorkListPage(
    owner: String,
    name: String,
    kind: CollaborationKind,
    login: String,
    createInitially: Boolean,
    onOpenDetail: (Int, String?) -> Unit,
) {
    val viewModel: CollaborationListViewModel =
        koinViewModel(
            key = "work-list-$login-$owner-$name-${kind.name}",
            parameters = { parametersOf(owner, name, kind, login) },
        )
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val strings = LocalAppStrings.current
    val filtered = filter.hasActiveCriteria()
    val filterOptions by viewModel.filterOptions.collectAsStateWithLifecycle()
    val branchPicker by viewModel.branchPicker.collectAsStateWithLifecycle()
    val assigneePicker by viewModel.assigneePicker.collectAsStateWithLifecycle()
    val optionsFailed by viewModel.optionsFailed.collectAsStateWithLifecycle()
    val creationState by viewModel.creationState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.initializeCreation(createInitially)
    }
    val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
    WorkListControls(
        kind,
        filter,
        viewModel.scopes,
        viewModel::setQuery,
        viewModel::selectFilter,
        labels = filterOptions.labels,
        milestones = filterOptions.milestones,
        issueTypes = if (kind == CollaborationKind.Issue) filterOptions.issueTypes else emptyList(),
        onShowFilters = viewModel::loadFilterOptions,
    ) { actions, searching ->
        PagingContent(
            pages,
            contentKey = Triple(login, WorkListRoute(owner, name, kind), filter),
            revalidation = remember(viewModel, filter) { viewModel.revalidationFor(filter) },
            onRetryRevalidation = { viewModel.retryRevalidation(filter) },
        ) { paging ->
            val items = paging.items
            val host = requireNotNull(LocalAppPageHost.current)
            val slots =
                rememberPagingSlots(
                    items,
                    "work-list:$filter",
                    paging.initialLoading,
                    refreshDomains = emptySet(),
                ) {
                    it.nodeId ?: it.number.toString()
                }
            AppPage(
                title = kind.title,
                listState = paging.listState,
                itemSpacing = 12.dp,
                actions = actions,
            ) {
                CollaborationListScreen(
                    kind,
                    slots,
                    onOpenDetail,
                    viewModel::card,
                )
                collaborationPagingStatus(
                    slots,
                    kind.title,
                    emptyMessage = when {
                        filtered -> strings.EditorSearch.NoMatches
                        kind == CollaborationKind.PullRequest -> strings.WorkList.EmptyPullRequests
                        else -> strings.WorkList.EmptyIssues
                    },
                    emptyScene = if (filtered) EmptyScene.Search else EmptyScene.Inbox,
                )
                if (optionsFailed) {
                    item(key = "work-list-options-retry") {
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = LocalAppStrings.current.Common.Retry,
                                onClick = viewModel::retryFilterOptions,
                            )
                        }
                    }
                }
                pageSearchClearance(searching)
            }
            androidx.compose.runtime.LaunchedEffect(host, viewModel) {
                viewModel.created.collect { (number, title) ->
                    if (host.navigationEnabled) onOpenDetail(number, title)
                }
            }
            androidx.compose.runtime.key(creationState.formRevision) {
                WorkCreationSheet(
                    kind = kind,
                    visible = creationState.visible,
                    creationState = creationState,
                    branchPicker = branchPicker,
                    issueTypes = filterOptions.issueTypeOptions,
                    milestones = filterOptions.milestoneOptions,
                    issueTemplates = filterOptions.issueTemplates,
                    labels = filterOptions.labels,
                    assigneePicker = assigneePicker,
                    onDraftChange = viewModel::updateCreationDraft,
                    onDismiss = { viewModel.setCreationVisible(false) },
                    onSubmit = viewModel::submitCreation,
                    onAcknowledgeUncertain = viewModel::acknowledgeCreationUncertain,
                    onBranchQueryChange = viewModel::searchBranches,
                    onBranchRetry = viewModel::retryBranches,
                    onBranchLoadMore = viewModel::loadMoreBranches,
                    onAssigneeQueryChange = viewModel::searchAssignees,
                    onAssigneeRetry = viewModel::retryAssignees,
                    onAssigneeLoadMore = viewModel::loadMoreAssignees,
                )
            }
        }
    }
}
