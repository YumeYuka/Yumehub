package moe.yumeyuka.yumehub.feature.pullrequestcommits

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.GithubCommitSummary
import moe.yumeyuka.yumehub.ui.component.GithubEmptyState
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.IdleGithubPagingRevalidation
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
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

data class PullRequestCommitsRoute(
    val owner: String,
    val name: String,
    val number: Int,
    val activityCommits: List<RepositoryActivityEntry>? = null,
) : GithubRoute

@Composable
fun PullRequestCommitsPage(
    route: PullRequestCommitsRoute,
    account: String,
    onOpenCommit: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val activityCommits = route.activityCommits
    // 时间线已携带这组提交的快照；不查询当前 PR 的完整提交列表。
    if (activityCommits != null) {
        GithubPage(
            title = strings.WorkDetail.Commits,
            smallTitle = strings.WorkDetail.Commits,
            showTitle = false,
            itemSpacing = 12.dp,
        ) {
            if (activityCommits.isEmpty()) {
                item(key = "pr-commits-empty") {
                    GithubEmptyState(
                        scene = EmptyScene.Inbox,
                        title = strings.Workspaces.NoCommitHistory,
                        modifier = Modifier.fillParentMaxHeight(),
                    )
                }
            }
            items(
                count = activityCommits.size,
                key = { activityCommits[it].id },
                contentType = { "pr-commit" },
            ) { index ->
                val entry = activityCommits[index]
                GlasmioGroupedList {
                    PullRequestCommitSummary(
                        entry,
                        onClick =
                            if (host.navigationEnabled && entry.id.isNotBlank()) {
                                { onOpenCommit(entry.id) }
                            } else null,
                    )
                }
            }
        }
        return
    }

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
    val denied = state.error?.invalidatesCachedContent() == true
    val head = state.data?.head?.sha?.takeIf { it.isNotBlank() && !denied }
    val missingHead = head == null && state.data != null && !denied
    val contentKey = "pr-commits:$account:$route:$head"
    val source = remember(viewModel, head) { viewModel.commits(head) }
    val sync =
        remember(viewModel, head) {
            head?.let(viewModel::revalidation) ?: IdleGithubPagingRevalidation
        }
    GithubPagingContent(
        source,
        contentKey = contentKey,
        revalidation = sync,
        onRetryRevalidation = head?.let { { viewModel.retryRevalidation(it) } },
    ) { paging ->
        val pages = paging.items
        val slots =
            rememberGithubPagingSlots(
                pages,
                contentKey,
                if (head == null) state.data == null && state.error == null
                else paging.initialLoading,
                contentReady = head != null,
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
            itemSpacing = 12.dp,
        ) {
            if (head != null || (state.data == null && state.error == null))
                githubPagingItems(slots, contentType = "pr-commit") { entry ->
                    GithubLoadingCard(
                        loading = entry == null,
                        placeholder = { WorkspaceCommitPlaceholder() },
                    ) {
                        if (entry != null) {
                            PullRequestCommitSummary(
                                entry,
                                onClick =
                                    if (!revisionChanged && head != null && entry.id.isNotBlank()) {
                                        {
                                            if (
                                                host.navigationEnabled &&
                                                    viewModel.canOpenCommit(head, entry.id)
                                            ) {
                                                onOpenCommit(entry.id)
                                            }
                                        }
                                    } else null,
                            )
                        }
                    }
                }
            if (head != null && state.error == null && pageError == null) {
                collaborationPagingStatus(
                    slots,
                    strings.WorkDetail.Commits,
                    emptyMessage = strings.Workspaces.NoCommitHistory,
                )
            }
            if (!state.loading && (missingHead || state.error != null || pageError != null)) {
                item(key = "pr-commits-retry") {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.WorkDetail.LoadFailed,
                            detail = strings.Common.Retry,
                            enabled = host.navigationEnabled,
                            onClick = {
                                if (missingHead || state.error != null || revisionChanged)
                                    viewModel.refresh()
                                if (pageError != null) pages.retry()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestCommitSummary(entry: RepositoryActivityEntry, onClick: (() -> Unit)?) {
    val strings = LocalGithubStrings.current
    GithubCommitSummary(
        message = entry.title.ifBlank { strings.Activity.Unavailable },
        author = entry.login ?: strings.Workspaces.UnknownAuthor,
        avatarUrl = entry.avatarUrl,
        time = entry.timestamp?.let(::githubActivityTime),
        onClick = onClick,
    )
}

val pullRequestCommitsFeatureModule = module {
    viewModel { (route: PullRequestCommitsRoute, account: String) ->
        PullRequestCommitsViewModel(get(), route, account)
    }
}
