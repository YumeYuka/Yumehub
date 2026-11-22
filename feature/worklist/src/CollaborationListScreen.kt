package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.compose.LazyPagingItems
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemCard
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemPlaceholder
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.CollaborationListScreen(
    kind: CollaborationKind,
    items: LazyPagingItems<CollaborationItemDto>,
    slots: GithubPagingSlots<CollaborationItemDto>,
    onOpenItem: (Int) -> Unit,
) {
    githubPagingItems(
        slots,
        contentType = "work-card",
        placeholder = { GithubWorkItemPlaceholder() },
    ) { entry ->
        GithubWorkItemCard(entry, kind) { onOpenItem(entry.number) }
    }
    collaborationPagingStatus(
        items = items,
        title = kind.title,
        loadingPlaceholder = {},
        emptyIcon =
            if (kind == CollaborationKind.PullRequest) Lucide.GitPullRequest else Lucide.CircleDot,
    )
}
