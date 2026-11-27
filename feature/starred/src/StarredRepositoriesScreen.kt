package moe.yumeyuka.yumehub.feature.starred

import androidx.compose.foundation.lazy.LazyListScope
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.StarredRepositoriesScreen(
    slots: GithubPagingSlots<RepositoryDto>,
    onOpenRepository: (RepositoryDto) -> Unit,
    hasAdditions: Boolean,
    emptyScene: EmptyScene,
    emptyText: String,
) {
    githubPagingItems(slots, contentType = "repository") { repository ->
        RepositoryLoadingCard(
            repository,
            onClick = repository?.let { { onOpenRepository(it) } },
            showCover = false,
        )
    }
    collaborationPagingStatus(
        slots,
        title = emptyText,
        emptyPlaceholder = !hasAdditions,
        emptyMessage = emptyText,
        emptyScene = emptyScene,
    )
}
