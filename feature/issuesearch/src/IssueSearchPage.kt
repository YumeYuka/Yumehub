package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
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
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 我的议题／拉取请求列表的路由键。 */
data class IssueSearchRoute(val kind: CollaborationKind) : AppRoute

@Composable
fun IssueSearchPage(
    login: String,
    kind: CollaborationKind,
    onOpenWork: (WorkTarget) -> Unit,
) {
  val viewModel: IssueSearchViewModel =
      koinViewModel(
          key = "issue-search-$login-${kind.name}",
          parameters = { parametersOf(login, kind) },
      )
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val strings = LocalAppStrings.current
  val filtered = filter.hasActiveCriteria()
  val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
  WorkListControls(kind, filter, viewModel.scopes, viewModel::setQuery, viewModel::selectFilter) { actions, searching ->
    PagingContent(
        pages,
        contentKey = Triple(login, kind, filter),
        revalidation = remember(viewModel, filter) { viewModel.revalidationFor(filter) },
        onRetryRevalidation = { viewModel.retryRevalidation(filter) },
    ) { paging ->
      val items = paging.items
      val slots =
          rememberPagingSlots(
              items,
              "work-search:$filter",
              paging.initialLoading,
              refreshDomains = emptySet(),
          ) {
            it.target.toString()
          }
      AppPage(
          title = kind.title,
          listState = paging.listState,
          itemSpacing = 12.dp,
          actions = actions,
      ) {
        IssueSearchScreen(kind, slots, onOpenWork, viewModel::card)
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
        pageSearchClearance(searching)
      }
    }
  }
}
