package moe.yumeyuka.yumehub.feature.pullrequestdiff

import androidx.compose.foundation.layout.fillMaxWidth
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
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubDiffFileCard
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.IdleGithubPagingRevalidation
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.rememberGithubDiffExpansions
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class PullRequestDiffRoute(val owner: String, val name: String, val number: Int) : GithubRoute

@Composable
fun PullRequestDiffPage(route: PullRequestDiffRoute, account: String) {
    val viewModel: PullRequestDiffViewModel =
        koinViewModel(
            key = "pr-diff:$account:$route",
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
    val denied = state.error?.invalidatesCachedContent() == true
    val head = state.data?.head?.sha?.takeIf { it.isNotBlank() && !denied }
    val missingHead = head == null && state.data != null && !denied
    val contentKey = "pr-diff:$account:$route:$head"
    val expansions = rememberGithubDiffExpansions(contentKey)
    val source = remember(viewModel, head) { viewModel.files(head) }
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
                it.filename
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
            title = strings.WorkDetail.Files,
            smallTitle = strings.WorkDetail.Files,
            showTitle = false,
            listState = paging.listState,
            itemSpacing = 8.dp,
        ) {
            if (head != null || (state.data == null && state.error == null)) {
                githubPagingItems(slots, contentType = "pr-diff-file", reading = true) { file ->
                    GithubDiffFileCard(file, expansions, Modifier.fillMaxWidth())
                }
            }
            if (head != null && state.error == null && pageError == null) {
                collaborationPagingStatus(
                    slots,
                    strings.WorkDetail.Files,
                    emptyMessage = strings.Profile.CollectionEmpty(strings.WorkDetail.Files),
                )
            }
            if (!state.loading && (missingHead || state.error != null || pageError != null)) {
                item(key = "pr-diff-retry") {
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

val pullRequestDiffFeatureModule = module {
    viewModel { (route: PullRequestDiffRoute, account: String) ->
        PullRequestDiffViewModel(get(), route, account)
    }
}
