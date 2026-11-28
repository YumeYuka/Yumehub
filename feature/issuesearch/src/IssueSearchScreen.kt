package moe.yumeyuka.yumehub.feature.issuesearch

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.repository.WorkCardContent
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.ui.component.PagingSlots
import moe.yumeyuka.yumehub.ui.component.WorkItemCard
import moe.yumeyuka.yumehub.ui.component.pagingItems

internal fun LazyListScope.IssueSearchScreen(
    kind: CollaborationKind,
    slots: PagingSlots<WorkItem>,
    onOpenItem: (WorkTarget) -> Unit,
    card: (CollaborationItemDto) -> WorkCardContent,
) {
    pagingItems(
        slots,
        contentType = "work",
    ) { entry ->
        val rich = entry?.let {
            key(it.item) {
                val content = remember { card(it.item) }
                content.updates.collectAsStateWithLifecycle(content.initial).value
            }
        }
        WorkItemCard(rich, kind, onClick = entry?.let { { onOpenItem(it.target) } })
    }
}
