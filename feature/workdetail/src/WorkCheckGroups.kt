package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.WorkflowRow
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap

@Composable
internal fun WorkCheckGroups(
    head: String?,
    viewModel: CollaborationDetailViewModel,
    onOpenRun: (String) -> Unit,
) {
    val strings = LocalAppStrings.current
    val work = strings.WorkDetail
    if (head.isNullOrBlank()) {
        GlasmioGroupedList { GlasmioListRow(title = work.NoChecksCommit, showTopSeparator = false) }
        return
    }
    key(head) {
        val pages = remember(viewModel, head) { viewModel.checks(head) }.collectAsLazyPagingItems()
        val revalidation by
            remember(viewModel, head) { viewModel.checksRevalidation(head) }
                .collectAsStateWithLifecycle()
        val slots =
            rememberPagingSlots(
                pages,
                "work-checks:$head",
                autoRefresh = false,
                accessDenied =
                    (revalidation as? RevalidationState.Failed)
                        ?.error
                        ?.invalidatesCachedContent() == true,
            ) {
                it.id
            }
        val snapshot = slots.snapshot
        val groups = remember(snapshot) { snapshot.filterNotNull().groupBy { it.suiteId } }
        val loads = pages.loadState
        val failure =
            loads.refresh as? LoadState.Error
                ?: loads.append as? LoadState.Error
                ?: loads.prepend as? LoadState.Error
        LoadFailureToast(failure, strings.Activity.LoadFailed)
        if (revalidation is RevalidationState.Failed) {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = strings.Common.CacheSyncFailed,
                    detail = strings.Common.Retry,
                    showDisclosure = false,
                    onClick = { viewModel.retryChecksRevalidation(head) },
                )
            }
        }

        if (
            snapshot.isNotEmpty() &&
                loads.prepend is LoadState.NotLoading &&
                !loads.prepend.endOfPaginationReached
        ) {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.PreviousChecks,
                    onClick = { if (pages.itemCount > 0) pages[0] },
                )
            }
        }
        groups.forEach { (suiteId, checks) ->
            key(suiteId) {
                val first = checks.first()
                val workflow = first.workflowName
                val number = first.runNumber
                val title =
                    when {
                        workflow != null && number != null ->
                            strings.Activity.WorkflowRunTitle(workflow, number)
                        suiteId == null -> work.CommitStatuses
                        else -> workflow ?: first.appName ?: work.Checks
                    }
                ListSection(title) {
                    checks.forEachIndexed { index, check ->
                        key(check.id) {
                            WorkflowRow(
                                title = check.name,
                                state = check.conclusion ?: check.status,
                                stateDescription =
                                    work.CheckConclusion(check.conclusion)
                                        ?: work.CheckStatus(check.status),
                                subtitle =
                                    listOfNotNull(
                                            work.Required.takeIf { check.required },
                                            check.description?.takeIf { it.isNotBlank() },
                                        )
                                        .joinToString(" · ")
                                        .ifEmpty { null },
                                showTopSeparator = index > 0,
                                onClick = check.runUrl?.let { url -> { onOpenRun(url) } },
                            )
                        }
                    }
                }
            }
        }
        val loading =
            failure == null &&
                (loads.refresh is LoadState.Loading && snapshot.isEmpty() ||
                    loads.append is LoadState.Loading ||
                    loads.prepend is LoadState.Loading)
        val hasMore = groups.isNotEmpty() && !loads.append.endOfPaginationReached
        SkeletonSwap(
            loading = loading,
            placeholder = { GlasmioGroupedList { ListRowPlaceholder() } },
        ) {
            when {
                failure != null ->
                    GlasmioGroupedList {
                        GlasmioListRow(title = strings.Common.Retry, onClick = pages::retry)
                    }
                hasMore ->
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.Activity.LoadMoreItems,
                            onClick = { if (pages.itemCount > 0) pages[pages.itemCount - 1] },
                        )
                    }
            }
        }
    }
}
