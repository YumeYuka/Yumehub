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
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.DiffFileCard
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.PagingContent
import moe.yumeyuka.yumehub.ui.component.IdlePagingRevalidation
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pagingItems
import moe.yumeyuka.yumehub.ui.component.rememberDiffExpansions
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.PageRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class PullRequestDiffRoute(val owner: String, val name: String, val number: Int) : AppRoute

@Composable
fun PullRequestDiffPage(route: PullRequestDiffRoute, account: String) {
    val viewModel: PullRequestDiffViewModel =
        koinViewModel(
            key = "pr-diff:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 首屏由 ViewModel init 发起；首次激活再强刷会作废刚创建的分页。
    PageRefreshEffect(
        viewModel,
        DataDomain.Work,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::refresh,
    )
    val strings = LocalAppStrings.current
    val host = requireNotNull(LocalAppPageHost.current)
    val denied = state.error?.invalidatesCachedContent() == true
    val head = state.data?.head?.sha?.takeIf { it.isNotBlank() && !denied }
    val missingHead = head == null && state.data != null && !denied
    val contentKey = "pr-diff:$account:$route:$head"
    val expansions = rememberDiffExpansions(contentKey)
    val source = remember(viewModel, head) { viewModel.files(head) }
    val sync =
        remember(viewModel, head) {
            head?.let(viewModel::revalidation) ?: IdlePagingRevalidation
        }
    PagingContent(
        source,
        contentKey = contentKey,
        revalidation = sync,
        onRetryRevalidation = head?.let { { viewModel.retryRevalidation(it) } },
    ) { paging ->
        val pages = paging.items
        val slots =
            rememberPagingSlots(
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
        LoadFailureToast(state.error, strings.WorkDetail.LoadFailed)
        AppPage(
            title = strings.WorkDetail.Files,
            smallTitle = strings.WorkDetail.Files,
            showTitle = false,
            listState = paging.listState,
            itemSpacing = 8.dp,
        ) {
            if (head != null || (state.data == null && state.error == null)) {
                pagingItems(slots, contentType = "pr-diff-file", reading = true) { file ->
                    DiffFileCard(file, expansions, Modifier.fillMaxWidth())
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
