package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.WorkListControls
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.workListTabOrder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 我的议题／拉取请求列表的路由键。 */
data class IssueSearchRoute(val kind: CollaborationKind) : GithubRoute

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
  val pages = remember(viewModel, filter) { viewModel.itemsFor(filter) }
  WorkListControls(kind, filter, viewModel.scopes, viewModel::setQuery, viewModel::selectFilter) { actions, searching ->
    GithubPagingContent(pages, contentKey = filter, order = workListTabOrder(kind, filter)) { paging
    ->
      val items = paging.items
      val slots =
          rememberGithubPagingSlots(
              items,
              "work-search:$filter",
              paging.initialLoading,
          ) {
            it.target.toString()
          }
      GithubPage(
          title = kind.title,
          listState = paging.listState,
          listTransition = paging.transition,
          itemSpacing = 12.dp,
          actions = actions,
      ) {
        IssueSearchScreen(kind, items, slots, onOpenWork)
        pageSearchClearance(searching)
      }
    }
  }
}
