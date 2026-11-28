package moe.yumeyuka.yumehub.feature.workspaces

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.FolderGit2
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.EmptyState
import moe.yumeyuka.yumehub.ui.component.PagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.cardItemMotion
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.PageRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CommitHistoryPage(owner: String, name: String, ref: String) {
    val strings = LocalAppStrings.current.Workspaces
    val route = CommitHistoryRoute(owner, name, ref)
    val viewModel: CommitHistoryViewModel =
        koinViewModel(
            key = "commit-history-$owner-$name-$ref",
            parameters = { parametersOf(route) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PageRefreshEffect(
        viewModel,
        DataDomain.Content,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::refresh,
    )
    val branches by viewModel.branches.uiState.collectAsStateWithLifecycle()
    val host = requireNotNull(LocalAppPageHost.current)
    val open = moe.yumeyuka.yumehub.ui.platform.rememberPageLinkHandler()
    val branchName = state.ref.removePrefix("refs/heads/")
    val listState = LocalAppPageState.current?.listState("commit-history:${state.ref}")
    var branchPickerVisible by rememberSaveable { mutableStateOf(false) }
    val slotAllocator = remember(state.ref) { PagingSlotKeys("commit-history:${state.ref}") }
    val commitIds =
        remember(state.commits) {
            val occurrences = mutableMapOf<String, Int>()
            state.commits.mapIndexed { index, commit ->
                val id = commit.oid?.takeIf(String::isNotBlank) ?: "missing:$index"
                val occurrence = occurrences.getOrDefault(id, 0)
                occurrences[id] = occurrence + 1
                "$id:$occurrence"
            }
        }
    val initialLoading = state.loading && !state.hasSnapshot
    val commitKeys =
        remember(slotAllocator, commitIds, initialLoading, state.loadingMore) {
            slotAllocator.update(
                commitIds,
                loading = state.loadingMore,
                initialLoading = initialLoading,
            )
        }
    AppPage(
        title = branchName,
        smallTitle = branchName,
        showTitle = false,
        listState = listState,
        itemSpacing = 12.dp,
        actions = {
            moe.yumeyuka.yumehub.ui.component.ToolbarActions(
                moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                    Lucide.FolderGit2,
                    strings.SwitchBranch,
                    enabled = host.navigationEnabled,
                    key = "branch",
                ) {
                    if (host.navigationEnabled) {
                        branchPickerVisible = true
                        viewModel.loadBranches()
                    }
                },
            )
        },
    ) {
        commitHistoryItems(state, commitKeys, strings, viewModel::retry, viewModel::loadMore) { oid
            ->
            if (host.navigationEnabled) open("https://github.com/$owner/$name/commit/$oid")
        }
    }
    WorkspaceBranchSheet(
        visible = branchPickerVisible,
        branches = branches,
        selectedBranch = state.ref,
        onDismiss = { branchPickerVisible = false },
        onRetry = viewModel.branches::retry,
        onLoadMore = viewModel.branches::loadMore,
        onQueryChange = viewModel.branches::search,
        onSelect = { branch ->
            if (host.navigationEnabled) viewModel.switchBranch(branch)
            branchPickerVisible = false
        },
    )
}

private fun LazyListScope.commitHistoryItems(
    state: CommitHistoryUiState,
    commitKeys: List<String>,
    strings: moe.yumeyuka.yumehub.locale.WorkspacesStrings,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenCommit: (String) -> Unit,
) {
    when {
        state.error != null && state.commits.isEmpty() -> Unit
        state.hasSnapshot && state.commits.isEmpty() ->
            item(key = "commit-history-empty") {
                EmptyState(
                    scene = EmptyScene.Inbox,
                    title = strings.NoCommitHistory,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
        else ->
            commitKeys.forEachIndexed { index, key ->
                item(key = key, contentType = "commit-history-card") {
                    val commit = state.commits.getOrNull(index)
                    LoadingCard(
                        loading = commit == null,
                        modifier = cardItemMotion(),
                        placeholder = { WorkspaceCommitPlaceholder() },
                    ) {
                        if (commit != null)
                            WorkspaceCommitCard(
                                commit = commit,
                                strings = strings,
                                onClick = commit.oid?.let { oid -> { onOpenCommit(oid) } },
                            )
                    }
                }
            }
    }
    val failure = state.error ?: state.loadMoreError
    if (failure != null) {
        item(key = "commit-history-retry") {
            GlasmioGroupedList { WorkspaceRetryRow(failure, onRetry) }
        }
    } else if (state.commits.isNotEmpty() && state.hasMore && !state.loadingMore) {
        item(key = "commit-history-load-more") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = strings.LoadMoreCommits,
                    systemName = null,
                    onClick = onLoadMore,
                )
            }
        }
    }
}
