package moe.yumeyuka.yumehub.feature.workspaces

import androidx.compose.runtime.Composable
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubChoiceSheet

@Composable
internal fun WorkspaceBranchSheet(
    visible: Boolean,
    branches: WorkspaceBranchesState,
    selectedBranch: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current.Workspaces
    GithubChoiceSheet(
        visible,
        strings.Branches,
        strings.BranchSearchPlaceholder,
        (branches.branches as? WorkspacesLoad.Ready)?.value.orEmpty(),
        branches.query,
        selectedBranch,
        branches.branches is WorkspacesLoad.Loading,
        branches.hasMore,
        (branches.branches as? WorkspacesLoad.Error)?.message ?: branches.loadMoreError,
        if (branches.query.isEmpty()) strings.NoBranches else strings.NoMatchingBranches,
        onDismiss,
        onRetry,
        onLoadMore,
        onQueryChange,
        onSelect,
        label = { it.removePrefix("refs/heads/") },
    )
}
