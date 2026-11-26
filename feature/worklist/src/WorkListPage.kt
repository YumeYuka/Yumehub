package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 仓库议题／拉取请求列表的路由键。 */
data class WorkListRoute(
    val owner: String,
    val name: String,
    val kind: CollaborationKind,
    val createInitially: Boolean = false,
) : GithubRoute

@Composable
fun WorkListPage(
    owner: String,
    name: String,
    kind: CollaborationKind,
    login: String,
    createInitially: Boolean,
    onOpenDetail: (Int) -> Unit,
) {
  val viewModel: CollaborationListViewModel =
      koinViewModel(
          key = "work-list-$login-$owner-$name-${kind.name}",
          parameters = { parametersOf(owner, name, kind, login) },
      )
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val filterOptions by viewModel.filterOptions.collectAsStateWithLifecycle()
  val branchPicker by viewModel.branchPicker.collectAsStateWithLifecycle()
  val assigneePicker by viewModel.assigneePicker.collectAsStateWithLifecycle()
  val optionsFailed by viewModel.optionsFailed.collectAsStateWithLifecycle()
  val creationState by viewModel.creationState.collectAsStateWithLifecycle()
  androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.initializeCreation(createInitially) }
  val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
  WorkListControls(
      kind, filter, viewModel.scopes, viewModel::setQuery, viewModel::selectFilter,
      labels = filterOptions.labels,
      milestones = filterOptions.milestones,
      issueTypes = if (kind == CollaborationKind.Issue) filterOptions.issueTypes else emptyList(),
      onShowFilters = viewModel::loadFilterOptions,
  ) { actions, searching ->
  GithubPagingContent(
      pages,
      contentKey = Triple(login, WorkListRoute(owner, name, kind), filter),
      revalidation = remember(viewModel, filter) { viewModel.revalidationFor(filter) },
      onRetryRevalidation = { viewModel.retryRevalidation(filter) },
  ) { paging ->
    val items = paging.items
    val host = requireNotNull(LocalGithubPageHost.current)
    val slots =
        rememberGithubPagingSlots(
            items,
            "work-list:$filter",
            paging.initialLoading,
            refreshDomains = emptySet(),
        ) {
          it.nodeId ?: it.number.toString()
        }
    GithubPage(
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
      if (optionsFailed) {
        item(key = "work-list-options-retry") {
          GlasmioGroupedList {
            GlasmioListRow(
                title = LocalGithubStrings.current.Common.Retry,
                onClick = viewModel::retryFilterOptions,
            )
          }
        }
      }
      pageSearchClearance(searching)
    }
    androidx.compose.runtime.LaunchedEffect(host, viewModel) {
        viewModel.created.collect { number -> if (host.navigationEnabled) onOpenDetail(number) }
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
