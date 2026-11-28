package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.repository.WorkCardContent
import moe.yumeyuka.yumehub.ui.component.PagingSlots
import moe.yumeyuka.yumehub.ui.component.WorkItemCard
import moe.yumeyuka.yumehub.ui.component.pagingItems

internal fun LazyListScope.CollaborationListScreen(
    kind: CollaborationKind,
    slots: PagingSlots<CollaborationItemDto>,
    onOpenItem: (Int, String?) -> Unit,
    card: (CollaborationItemDto) -> WorkCardContent,
) {
    pagingItems(
        slots,
        contentType = "work-card",
    ) { entry ->
        val rich = entry?.let {
            key(it) {
                val content = remember { card(it) }
                content.updates.collectAsStateWithLifecycle(content.initial).value
            }
        }
        WorkItemCard(
            rich,
            kind,
            onClick = entry?.let { { onOpenItem(it.number, rich?.title) } },
        )
    }
}
