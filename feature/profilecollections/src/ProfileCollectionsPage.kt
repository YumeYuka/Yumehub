package moe.yumeyuka.yumehub.feature.profilecollections

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.OrganizationRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ProfileCollectionsPage(
    route: ProfileCollectionsRoute,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenOrganization: (login: String, avatarUrl: String?, name: String?) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val viewModel: ProfileCollectionsViewModel = koinViewModel(parameters = { parametersOf(route) })
    val title =
        when (route.kind) {
            ProfileCollectionsRoute.Kind.Repositories -> strings.Profile.Repositories
            ProfileCollectionsRoute.Kind.Starred -> strings.Profile.Starred
            ProfileCollectionsRoute.Kind.Organizations -> strings.Profile.Organizations
        }
    val organizations = route.kind == ProfileCollectionsRoute.Kind.Organizations
    GithubPagingContent(
        viewModel.entries,
        contentKey = viewModel.account to route,
        revalidation = viewModel.revalidation,
        onRetryRevalidation = viewModel::retryRevalidation,
    ) { paging ->
        val pages = paging.items
        val slots =
            rememberGithubPagingSlots(pages, "profile-collection", paging.initialLoading) { entry ->
                when (entry) {
                    is ProfileCollectionEntry.Repository ->
                        entry.value.id?.toString() ?: entry.value.fullName
                    is ProfileCollectionEntry.Organization ->
                        entry.value.id?.toString() ?: entry.value.login
                }
            }
        GithubPage(
            title = title,
            listState = paging.listState,
            itemSpacing = 12.dp,
        ) {
            githubPagingItems(slots, contentType = "profile-collection") { entry ->
                if (organizations) {
                    val organization = (entry as? ProfileCollectionEntry.Organization)?.value
                    GithubLoadingCard(
                        loading = entry == null,
                        placeholder = { OrganizationRowPlaceholder() },
                    ) {
                        GlasmioListRow(
                            title =
                                organization?.name?.takeIf(String::isNotBlank)
                                    ?: organization?.login.orEmpty(),
                            subtitle = organization?.login,
                            leading = {
                                GithubAvatar(
                                    organization?.avatarUrl,
                                    organization?.login,
                                    type = "Organization",
                                    size = 40.dp,
                                    placeholder = {
                                        AvatarPlaceholder(40.dp, type = "Organization")
                                    },
                                )
                            },
                            showTopSeparator = false,
                            showDisclosure = true,
                            onClick =
                                organization?.login?.takeIf(String::isNotBlank)?.let { login ->
                                    {
                                        onOpenOrganization(
                                            login,
                                            organization.avatarUrl,
                                            organization.name,
                                        )
                                    }
                                },
                        )
                    }
                } else {
                    val repository = (entry as? ProfileCollectionEntry.Repository)?.value
                    RepositoryLoadingCard(
                        repository,
                        onClick = repository?.let { { onOpenRepository(it) } },
                    )
                }
            }
            collaborationPagingStatus(slots, title, emptyMessage = strings.Profile.CollectionEmpty(title))
        }
    }
}
