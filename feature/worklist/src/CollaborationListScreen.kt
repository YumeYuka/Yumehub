package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.repository.WorkCardContent
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.CollaborationListScreen(
    kind: CollaborationKind,
    slots: GithubPagingSlots<CollaborationItemDto>,
    onOpenItem: (Int, String?) -> Unit,
    card: (CollaborationItemDto) -> WorkCardContent,
) {
    githubPagingItems(
        slots,
        contentType = "work-card",
    ) { entry ->
        val rich = entry?.let {
            key(it) {
                val content = remember { card(it) }
                content.updates.collectAsStateWithLifecycle(content.initial).value
            }
        }
        GithubWorkItemCard(
            rich,
            kind,
            onClick = entry?.let { { onOpenItem(it.number, rich?.title) } },
        )
    }
    collaborationPagingStatus(
        slots = slots,
        title = kind.title,
        emptyIcon =
            if (kind == CollaborationKind.PullRequest) Lucide.GitPullRequest else Lucide.CircleDot,
    )
}
