package moe.yumeyuka.yumehub.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.key
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.locale.GithubStrings_Home
import moe.yumeyuka.yumehub.ui.component.GithubSection

internal fun LazyListScope.HomeScreen(
    items: List<HomeWorkItem>,
    strings: GithubStrings_Home,
    onEdit: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenWork: (CollaborationKind) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenOrganizations: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenDiscussions: () -> Unit,
) {
    item(key = "home-repositories") {
        GithubSection(title = strings.MyWork) {
            Column {
                if (items.isEmpty()) {
                    GlasmioListRow(
                        title = strings.NoWorkItems,
                        systemName = null,
                        onClick = onEdit,
                    )
                }
                items.forEachIndexed { index, item ->
                    key(item) {
                        GlasmioListRow(
                            title = item.title(strings),
                            showTopSeparator = index > 0,
                            leading = { HomeWorkIcon(item) },
                            showDisclosure = true,
                            onClick =
                                when (item) {
                                    HomeWorkItem.Issues -> ({
                                            onOpenWork(CollaborationKind.Issue)
                                        })
                                    HomeWorkItem.PullRequests -> ({
                                            onOpenWork(CollaborationKind.PullRequest)
                                        })
                                    HomeWorkItem.Repositories -> onOpenRepositories
                                    HomeWorkItem.Starred -> onOpenStarred
                                    HomeWorkItem.Organizations -> onOpenOrganizations
                                    HomeWorkItem.Discussions -> onOpenDiscussions
                                    HomeWorkItem.Projects -> onOpenProjects
                                },
                        )
                    }
                }
            }
        }
    }
}
