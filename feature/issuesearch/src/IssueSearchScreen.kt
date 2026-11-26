package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.repository.WorkCardContent
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.IssueSearchScreen(
    kind: CollaborationKind,
    slots: GithubPagingSlots<WorkItem>,
    onOpenItem: (WorkTarget) -> Unit,
    card: (CollaborationItemDto) -> WorkCardContent,
) {
    githubPagingItems(
        slots,
        contentType = "work",
    ) { entry ->
        val rich = entry?.let {
            key(it.item) {
                val content = remember { card(it.item) }
                content.updates.collectAsStateWithLifecycle(content.initial).value
            }
        }
        GithubWorkItemCard(rich, kind, onClick = entry?.let { { onOpenItem(it.target) } })
    }
    collaborationPagingStatus(
        slots,
        kind.title,
        emptyIcon =
            if (kind == CollaborationKind.PullRequest) Lucide.GitPullRequest else Lucide.CircleDot,
    )
}
