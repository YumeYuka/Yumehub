package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.compose.LazyPagingItems
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemCard
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemPlaceholder
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.IssueSearchScreen(
    kind: CollaborationKind,
    items: LazyPagingItems<WorkItem>,
    slots: GithubPagingSlots<WorkItem>,
    onOpenItem: (WorkTarget) -> Unit,
) {
    githubPagingItems(slots, contentType = "work", placeholder = { GithubWorkItemPlaceholder() }) {
        entry ->
        GithubWorkItemCard(entry.item, kind) { onOpenItem(entry.target) }
    }
    collaborationPagingStatus(
        items,
        kind.title,
        loadingPlaceholder = {},
        emptyIcon =
            if (kind == CollaborationKind.PullRequest) Lucide.GitPullRequest else Lucide.CircleDot,
    )
}
