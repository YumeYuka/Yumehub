package moe.yumeyuka.yumehub.feature.repositoryactivity

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubActivityListPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class RepositoryActivityRoute(
    val owner: String,
    val name: String,
    val kind: RepositoryActivityKind,
    val nodeId: String? = null,
    val heading: String? = null,
    val projectNumber: Int? = null,
    val organization: Boolean = false,
    val createInitially: Boolean = false,
) : GithubRoute

private val RepositoryActivityKind.isDiscussion: Boolean
    get() =
        this == RepositoryActivityKind.Discussions ||
            this == RepositoryActivityKind.PersonalDiscussions

@Composable
fun RepositoryActivityPage(
    route: RepositoryActivityRoute,
    account: String,
    onOpenDiscussion: (owner: String, name: String, number: Int) -> Unit,
) {
    if (route.kind.isDiscussion) {
        DiscussionListPage(route, account, onOpenDiscussion = onOpenDiscussion)
        return
    }
    val strings = LocalGithubStrings.current.Repository
    val open = rememberGithubUriHandler()
    val viewModel: RepositoryActivityViewModel =
        koinViewModel(
            key = "repository-activity:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val title =
        route.heading
            ?: when (route.kind) {
                RepositoryActivityKind.PersonalProjects,
                RepositoryActivityKind.Projects,
                RepositoryActivityKind.ProjectItems -> strings.Projects
                RepositoryActivityKind.PersonalDiscussions,
                RepositoryActivityKind.Discussions -> strings.Discussions
                RepositoryActivityKind.CheckRuns,
                RepositoryActivityKind.CheckSteps,
                RepositoryActivityKind.Actions,
                RepositoryActivityKind.WorkflowRuns ->
                    error("Actions routes belong to :feature:actions")
                RepositoryActivityKind.Contributors -> strings.Contributors
                RepositoryActivityKind.Watchers -> strings.Watchers
            }
    GithubActivityListPage(
        title = title,
        source = viewModel.items,
        icon =
            when (route.kind) {
                RepositoryActivityKind.PersonalProjects,
                RepositoryActivityKind.Projects,
                RepositoryActivityKind.ProjectItems -> Lucide.Columns3
                RepositoryActivityKind.PersonalDiscussions,
                RepositoryActivityKind.Discussions -> Lucide.MessagesSquare
                RepositoryActivityKind.Contributors -> Lucide.UsersRound
                RepositoryActivityKind.Watchers -> Lucide.Eye
                else -> Lucide.CirclePlay
            },
        contentKey = route,
        subtitle = if (route.name.isNotEmpty()) "${route.owner}/${route.name}" else route.owner,
        onOpen = { it.url?.let(open) },
    )
}

internal class RepositoryActivityViewModel(
    source: RepositoryActivityRepository,
    route: RepositoryActivityRoute,
    account: String,
) : ViewModel() {
    val items =
        source
            .items(
                account,
                route.owner,
                route.name,
                route.kind,
                route.nodeId,
                route.projectNumber,
                route.organization,
            )
            .cachedIn(viewModelScope)
}

val repositoryActivityFeatureModule = module {
    viewModel { (route: RepositoryActivityRoute, account: String) ->
        RepositoryActivityViewModel(get(), route, account)
    }
    viewModel { (route: RepositoryActivityRoute, account: String) ->
        DiscussionListViewModel(get(), route, account)
    }
}
