package moe.yumeyuka.yumehub.feature.pullrequestcommits

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitSummary
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.githubActivityTime
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class PullRequestCommitsRoute(val owner: String, val name: String, val number: Int) :
    GithubRoute

@Composable
fun PullRequestCommitsPage(
    route: PullRequestCommitsRoute,
    account: String,
    onOpenCommit: (String) -> Unit,
) {
    val viewModel: PullRequestCommitsViewModel =
        koinViewModel(
            key = "pr-commits:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 首屏由 ViewModel init 发起；首次激活再强刷会作废刚创建的分页。
    GithubRefreshEffect(
        viewModel,
        DataDomain.Work,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::refresh,
    )
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val head = state.data?.head?.sha?.takeIf(String::isNotBlank)
    val contentKey = "pr-commits:$account:$route:$head"
    GithubPagingContent(viewModel.commits, contentKey = contentKey) { paging ->
        val pages = paging.items
        val slots =
            rememberGithubPagingSlots(
                pages,
                contentKey,
                paging.initialLoading || (head == null && state.error == null),
                autoRefresh = false,
            ) {
                it.id
            }
        val refreshError = (pages.loadState.refresh as? LoadState.Error)?.error
        val appendError = (pages.loadState.append as? LoadState.Error)?.error
        val revisionChanged =
            refreshError is WorkDetailReviewChangedException ||
                appendError is WorkDetailReviewChangedException
        val pageError = refreshError ?: appendError
        val currentPage = host.isCurrent
        LaunchedEffect(viewModel, head, revisionChanged, currentPage) {
            if (currentPage && revisionChanged) viewModel.recoverRevision(head)
        }
        GithubLoadFailureToast(state.error, strings.WorkDetail.LoadFailed)
        GithubPage(
            title = strings.WorkDetail.Commits,
            smallTitle = strings.WorkDetail.Commits,
            showTitle = false,
            listState = paging.listState,
            listTransition = paging.transition,
            itemSpacing = 12.dp,
        ) {
            githubPagingItems(slots, contentType = "pr-commit") { row ->
                val entry = row?.commit
                GithubLoadingCard(
                    loading = entry == null,
                    placeholder = { WorkspaceCommitPlaceholder() },
                ) {
                    if (row != null && entry != null) {
                        GithubCommitSummary(
                            message = entry.title.ifBlank { strings.Activity.Unavailable },
                            author = entry.login ?: strings.Workspaces.UnknownAuthor,
                            avatarUrl = entry.avatarUrl,
                            time = entry.timestamp?.let(::githubActivityTime),
                            onClick =
                                if (!revisionChanged && row.head == head && entry.id.isNotBlank()) {
                                    {
                                        if (
                                            host.navigationEnabled &&
                                                viewModel.canOpenCommit(row.head, entry.id)
                                        ) {
                                            onOpenCommit(entry.id)
                                        }
                                    }
                                } else null,
                        )
                    }
                }
            }
            if (!state.loading && (state.error != null || pageError != null)) {
                item(key = "pr-commits-retry") {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.Common.Retry,
                            enabled = host.navigationEnabled,
                            onClick = {
                                if (state.error != null || revisionChanged) viewModel.refresh()
                                else pages.retry()
                            },
                        )
                    }
                }
            }
        }
    }
}

val pullRequestCommitsFeatureModule = module {
    viewModel { (route: PullRequestCommitsRoute, account: String) ->
        PullRequestCommitsViewModel(get(), route, account)
    }
}
