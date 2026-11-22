package moe.yumeyuka.yumehub.feature.search

import androidx.compose.foundation.lazy.LazyListScope
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemCard
import moe.yumeyuka.yumehub.ui.component.GithubWorkItemPlaceholder
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.placeholders.UserRowPlaceholder

internal fun LazyListScope.searchResultRows(
    slots: GithubPagingSlots<ExploreResult>,
    category: ExploreCategory,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenWork: (WorkTarget) -> Unit,
    onOpenUser: (UserDto) -> Unit,
    onOpenFile: (SearchCodeFile, Int?) -> Unit,
) {
    githubPagingItems(
        slots,
        contentType = category.name,
        placeholder = {
            when (category) {
                ExploreCategory.Repositories -> RepositoryLoadingCard(null)
                ExploreCategory.Issues,
                ExploreCategory.PullRequests -> GithubWorkItemPlaceholder()
                ExploreCategory.People,
                ExploreCategory.Organizations -> GlasmioGroupedList { UserRowPlaceholder() }
                ExploreCategory.Code -> CodeResult(null, onOpenFile)
            }
        },
    ) { result ->
        when (result) {
            is ExploreResult.Repository ->
                RepositoryLoadingCard(
                    result.item,
                    onClick = { onOpenRepository(result.item) },
                )
            is ExploreResult.Work ->
                GithubWorkItemCard(result.item, result.target.kind) { onOpenWork(result.target) }
            is ExploreResult.Actor ->
                GlasmioGroupedList {
                    val user = result.item
                    GlasmioListRow(
                        title = user.login.orEmpty(),
                        subtitle = user.name?.takeUnless { it.isBlank() || it == user.login },
                        leading = { GithubAvatar(user.avatarUrl, user.login, user.type) },
                        onClick = { onOpenUser(user) },
                    )
                }
            is ExploreResult.Code -> CodeResult(result.file, onOpenFile)
        }
    }
}
