package moe.yumeyuka.yumehub.feature.starred

import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.compose.LazyPagingItems
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Star
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.githubPagingItems

fun LazyListScope.StarredRepositoriesScreen(
    repositories: LazyPagingItems<RepositoryDto>,
    slots: GithubPagingSlots<RepositoryDto>,
    initialLoading: Boolean,
    onOpenRepository: (RepositoryDto) -> Unit,
    emptyText: String? = null,
) {
    // 首屏四个骨架槽逐槽交给正文，追加只留一个尾槽。
    githubPagingItems(slots, contentType = "repository") { repository ->
        RepositoryLoadingCard(
            repository,
            onClick = repository?.let { { onOpenRepository(it) } },
            showCover = false,
        )
    }
    if (initialLoading) return
    collaborationPagingStatus(
        repositories,
        emptyText.orEmpty(),
        loadingPlaceholder = {},
        emptyMessage = emptyText,
        emptyIcon = Lucide.Star,
    )
}
