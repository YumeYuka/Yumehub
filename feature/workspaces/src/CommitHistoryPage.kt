package moe.yumeyuka.yumehub.feature.workspaces

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.FolderGit2
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CommitHistoryPage(owner: String, name: String, ref: String) {
    val strings = LocalGithubStrings.current.Workspaces
    val route = CommitHistoryRoute(owner, name, ref)
    val viewModel: CommitHistoryViewModel =
        koinViewModel(
            key = "commit-history-$owner-$name-$ref",
            parameters = { parametersOf(route) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Content,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::refresh,
    )
    val branches by viewModel.branches.uiState.collectAsStateWithLifecycle()
    val host = requireNotNull(LocalGithubPageHost.current)
    val open = moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler()
    val branchName = state.ref.removePrefix("refs/heads/")
    val listState = LocalGithubPageState.current?.listState("commit-history")
    var scrolledRef by rememberSaveable { mutableStateOf(state.ref) }
    var branchPickerVisible by rememberSaveable { mutableStateOf(false) }
    val slotAllocator = remember(state.ref) { GithubPagingSlotKeys("commit-history:${state.ref}") }
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
    val initialLoading = state.loading && state.commits.isEmpty()
    val commitKeys =
        remember(slotAllocator, commitIds, initialLoading, state.loadingMore) {
            slotAllocator.update(
                commitIds,
                loading = state.loadingMore,
                initialLoading = initialLoading,
            )
        }
    LaunchedEffect(state.ref, listState) {
        if (state.ref != scrolledRef) {
            scrolledRef = state.ref
            listState?.scrollToItem(0)
        }
    }
    val failure = state.error ?: state.loadMoreError
    GithubLoadFailureToast(failure, failure.orEmpty())
    GithubPage(
        title = branchName,
        smallTitle = branchName,
        showTitle = false,
        listState = listState,
        itemSpacing = 12.dp,
        actions = {
            GlasmioIconButton(
                onClick = {
                    if (host.navigationEnabled) {
                        branchPickerVisible = true
                        viewModel.loadBranches()
                    }
                },
                enabled = host.navigationEnabled,
            ) {
                GithubIcon(Lucide.FolderGit2, contentDescription = strings.SwitchBranch)
            }
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
    strings: moe.yumeyuka.yumehub.locale.GithubStrings_Workspaces,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenCommit: (String) -> Unit,
) {
    when {
        state.error != null && state.commits.isEmpty() -> Unit
        !state.loading && state.commits.isEmpty() ->
            item(key = "commit-history-empty") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.NoCommitHistory,
                        systemName = null,
                        showTopSeparator = false,
                    )
                }
            }
        else ->
            commitKeys.forEachIndexed { index, key ->
                item(key = key, contentType = "commit-history-card") {
                    val commit = state.commits.getOrNull(index)
                    GithubLoadingCard(
                        loading = commit == null,
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec = tween(260),
                                fadeOutSpec = tween(180),
                                placementSpec = spring(dampingRatio = 1f, stiffness = 400f),
                            ),
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
    if (state.commits.isNotEmpty() && state.hasMore && !state.loadingMore) {
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
