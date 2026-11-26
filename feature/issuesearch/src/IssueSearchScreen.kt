package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.compose.LazyPagingItems
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
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
    card: (CollaborationItemDto) -> Flow<CollaborationItemDto>,
) {
    githubPagingItems(slots, contentType = "work", placeholder = { GithubWorkItemPlaceholder() }) {
        entry ->
        val rich by remember(entry.item) { card(entry.item) }.collectAsStateWithLifecycle(entry.item)
        GithubWorkItemCard(rich, kind) { onOpenItem(entry.target) }
    }
    collaborationPagingStatus(
        items,
        kind.title,
        loadingPlaceholder = {},
        emptyIcon =
            if (kind == CollaborationKind.PullRequest) Lucide.GitPullRequest else Lucide.CircleDot,
    )
}
