package moe.yumeyuka.yumehub.feature.repositories

import androidx.compose.foundation.lazy.LazyListScope
import io.github.yumeyucca.lucide.BookMarked
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.RepositoryScreen(
    slots: GithubPagingSlots<RepositoryDto>,
    onOpenRepository: (RepositoryDto) -> Unit = {},
    emptyMessage: String,
) {
    githubPagingItems(slots, contentType = "repository") { repository ->
        RepositoryLoadingCard(repository, onClick = repository?.let { { onOpenRepository(it) } })
    }
    collaborationPagingStatus(
        slots,
        "仓库",
        emptyMessage = emptyMessage,
        emptyIcon = Lucide.BookMarked,
    )
}
