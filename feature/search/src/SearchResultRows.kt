package moe.yumeyuka.yumehub.feature.search

import androidx.compose.foundation.lazy.LazyListScope
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.PagingSlots
import moe.yumeyuka.yumehub.ui.component.WorkItemCard
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.pagingItems
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.UserRowPlaceholder

internal fun LazyListScope.searchResultRows(
    slots: PagingSlots<ExploreResult>,
    category: ExploreCategory,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenWork: (WorkTarget) -> Unit,
    onOpenUser: (UserDto) -> Unit,
    onOpenFile: (SearchCodeFile, Int?) -> Unit,
) {
    pagingItems(slots, contentType = category.name) { result ->
        when (category) {
            ExploreCategory.Repositories -> {
                val repo = (result as? ExploreResult.Repository)?.item
                RepositoryLoadingCard(repo, onClick = repo?.let { { onOpenRepository(it) } })
            }
            ExploreCategory.Issues, ExploreCategory.PullRequests -> {
                val work = result as? ExploreResult.Work
                val kind = if (category == ExploreCategory.Issues) CollaborationKind.Issue else CollaborationKind.PullRequest
                WorkItemCard(work?.item, kind, onClick = work?.let { { onOpenWork(it.target) } })
            }
            ExploreCategory.People, ExploreCategory.Organizations -> {
                val user = (result as? ExploreResult.Actor)?.item
                LoadingCard(user == null, placeholder = { UserRowPlaceholder() }) {
                    if (user != null) GlasmioListRow(
                        title = user.login.orEmpty(),
                        subtitle = user.name?.takeUnless { it.isBlank() || it == user.login },
                        leading = { UserAvatar(user.avatarUrl, user.login, user.type) },
                        onClick = { onOpenUser(user) },
                    )
                }
            }
            ExploreCategory.Code -> {
                val file = (result as? ExploreResult.Code)?.file
                CodeResult(file, onOpenFile)
            }
        }
    }
}
