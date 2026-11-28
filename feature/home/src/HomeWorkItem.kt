package moe.yumeyuka.yumehub.feature.home

import androidx.compose.runtime.Composable
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.HomeStrings
import moe.yumeyuka.yumehub.ui.component.EntryIcon
import moe.yumeyuka.yumehub.ui.theme.HomeColors

internal fun HomeWorkItem.title(strings: HomeStrings): String =
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
            HomeWorkItem.Issues -> HomeColors.issues
            HomeWorkItem.PullRequests -> HomeColors.pullRequests
            HomeWorkItem.Discussions -> HomeColors.discussions
            HomeWorkItem.Projects -> HomeColors.projects
            HomeWorkItem.Organizations -> HomeColors.organizations
            HomeWorkItem.Repositories -> HomeColors.repositories
            HomeWorkItem.Starred -> HomeColors.starred
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
    EntryIcon(icon, background, filled = item == HomeWorkItem.Starred)
}
