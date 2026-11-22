package moe.yumeyuka.yumehub.feature.home

import androidx.compose.runtime.Composable
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.GithubStrings_Home
import moe.yumeyuka.yumehub.ui.component.GithubEntryIcon
import moe.yumeyuka.yumehub.ui.theme.GithubHomeColors

internal fun HomeWorkItem.title(strings: GithubStrings_Home): String =
    when (this) {
        HomeWorkItem.Issues -> strings.Issues
        HomeWorkItem.PullRequests -> strings.PullRequests
        HomeWorkItem.Discussions -> strings.Discussions
        HomeWorkItem.Projects -> strings.Projects
        HomeWorkItem.Organizations -> strings.Organizations
        HomeWorkItem.Repositories -> strings.Repositories
        HomeWorkItem.Starred -> strings.Starred
    }

@Composable
internal fun HomeWorkIcon(item: HomeWorkItem) {
    val background =
        when (item) {
            HomeWorkItem.Issues -> GithubHomeColors.issues
            HomeWorkItem.PullRequests -> GithubHomeColors.pullRequests
            HomeWorkItem.Discussions -> GithubHomeColors.discussions
            HomeWorkItem.Projects -> GithubHomeColors.projects
            HomeWorkItem.Organizations -> GithubHomeColors.organizations
            HomeWorkItem.Repositories -> GithubHomeColors.repositories
            HomeWorkItem.Starred -> GithubHomeColors.starred
        }
    val icon =
        when (item) {
            HomeWorkItem.Issues -> Lucide.CircleDot
            HomeWorkItem.PullRequests -> Lucide.GitPullRequest
            HomeWorkItem.Discussions -> Lucide.MessagesSquare
            HomeWorkItem.Projects -> Lucide.PanelsTopLeft
            HomeWorkItem.Organizations -> Lucide.Building2
            HomeWorkItem.Repositories -> Lucide.BookMarked
            HomeWorkItem.Starred -> Lucide.Star
        }
    GithubEntryIcon(icon, background, filled = item == HomeWorkItem.Starred)
}
