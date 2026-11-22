package moe.yumeyuka.yumehub.feature.repositories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.compose.LazyPagingItems
import io.github.yumeyucca.lucide.BookMarked
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

internal fun LazyListScope.RepositoryScreen(
    repositories: LazyPagingItems<RepositoryDto>,
    slots: GithubPagingSlots<RepositoryDto>,
    onOpenRepository: (RepositoryDto) -> Unit = {},
    emptyMessage: String,
    initialLoading: Boolean,
) {
    githubPagingItems(slots, contentType = "repository") { repository ->
        RepositoryLoadingCard(repository, onClick = repository?.let { { onOpenRepository(it) } })
    }
    if (initialLoading) return
    collaborationPagingStatus(
        repositories,
        "仓库",
        loadingPlaceholder = {},
        emptyMessage = emptyMessage,
        emptyIcon = Lucide.BookMarked,
    )
}
