package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
  val optionsFailed by viewModel.optionsFailed.collectAsStateWithLifecycle()
  val creationState by viewModel.creationState.collectAsStateWithLifecycle()
  androidx.compose.runtime.LaunchedEffect(creationVisible) {
      viewModel.setCreationVisible(creationVisible)
  }
  val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
  WorkListControls(
      kind, filter, viewModel.scopes, viewModel::setQuery, viewModel::selectFilter,
      labels = filterOptions.labels,
      milestones = filterOptions.milestones,
      issueTypes = if (kind == CollaborationKind.Issue) filterOptions.issueTypes else emptyList(),
  ) { actions, searching ->
  GithubPagingContent(
      pages,
      contentKey = filter,
      revalidation = viewModel.revalidation,
      onRetryRevalidation = viewModel::retryRevalidation,
      order = workListTabOrder(kind, filter),
  ) { paging ->
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
    androidx.compose.runtime.LaunchedEffect(creationState.createdNumber) {
        val number = creationState.createdNumber ?: return@LaunchedEffect
        val wasVisible = creationVisible
        viewModel.consumeCreatedNumber()
        creationVisible = false
        items.refresh()
        if (wasVisible && host.navigationEnabled) onOpenDetail(number)
    }
    WorkCreationSheet(
        kind = kind,
        visible = creationVisible,
        creationState = creationState,
        branchPicker = branchPicker,
        issueTypes = filterOptions.issueTypeOptions,
        milestones = filterOptions.milestoneOptions,
        issueTemplates = filterOptions.issueTemplates,
        labels = filterOptions.labels,
        assigneePicker = assigneePicker,
        onDraftChange = { title, body, base, head ->
            viewModel.updateCreationDraft(title = title, body = body, base = base, head = head)
        },
        onDismiss = { creationVisible = false },
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
