package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.workListTabOrder
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
  var creationVisible by rememberSaveable { mutableStateOf(createInitially) }
  val viewModel: CollaborationListViewModel =
      koinViewModel(
          key = "work-list-$login-$owner-$name-${kind.name}",
          parameters = { parametersOf(owner, name, kind, login) },
      )
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val filterOptions by viewModel.filterOptions.collectAsStateWithLifecycle()
  val branchPicker by viewModel.branchPicker.collectAsStateWithLifecycle()
  val assigneePicker by viewModel.assigneePicker.collectAsStateWithLifecycle()
  val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
  WorkListControls(
      kind, filter, viewModel.scopes, viewModel::setQuery, viewModel::selectFilter,
      labels = filterOptions.labels,
      milestones = filterOptions.milestones,
      issueTypes = if (kind == CollaborationKind.Issue) filterOptions.issueTypes else emptyList(),
  ) { actions, searching ->
  GithubPagingContent(pages, contentKey = filter, order = workListTabOrder(kind, filter)) { paging
    ->
    val items = paging.items
    val host = requireNotNull(LocalGithubPageHost.current)
    val slots =
        rememberGithubPagingSlots(
            items,
            "work-list:$filter",
            paging.initialLoading,
        ) {
          it.nodeId ?: it.number.toString()
        }
    GithubPage(
        title = kind.title,
        listState = paging.listState,
        listTransition = paging.transition,
        itemSpacing = 12.dp,
        actions = actions,
    ) {
      CollaborationListScreen(
          kind,
          items,
          slots,
          onOpenDetail,
      )
      pageSearchClearance(searching)
    }
    WorkCreationSheet(
        kind = kind,
        visible = creationVisible,
        branchPicker = branchPicker,
        issueTypes = filterOptions.issueTypeOptions,
        milestones = filterOptions.milestoneOptions,
        issueTemplates = filterOptions.issueTemplates,
        labels = filterOptions.labels,
        assigneePicker = assigneePicker,
        onDismiss = { creationVisible = false },
        onCreate = viewModel::create,
        onBranchQueryChange = viewModel::searchBranches,
        onBranchRetry = viewModel::retryBranches,
        onBranchLoadMore = viewModel::loadMoreBranches,
        onAssigneeQueryChange = viewModel::searchAssignees,
        onAssigneeRetry = viewModel::retryAssignees,
        onAssigneeLoadMore = viewModel::loadMoreAssignees,
        onCreated = { number ->
          items.refresh()
          if (host.navigationEnabled) onOpenDetail(number)
        },
    )
  }
  }
}
