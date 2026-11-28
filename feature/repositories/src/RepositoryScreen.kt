package moe.yumeyuka.yumehub.feature.repositories

import androidx.compose.foundation.lazy.LazyListScope
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.PagingSlots
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pagingItems

internal fun LazyListScope.RepositoryScreen(
    slots: PagingSlots<RepositoryDto>,
    onOpenRepository: (RepositoryDto) -> Unit = {},
    emptyScene: EmptyScene,
    emptyMessage: String,
) {
    pagingItems(slots, contentType = "repository") { repository ->
        RepositoryLoadingCard(repository, onClick = repository?.let { { onOpenRepository(it) } })
    }
    collaborationPagingStatus(
        slots,
        title = emptyMessage,
        emptyMessage = emptyMessage,
        emptyScene = emptyScene,
    )
}
