package moe.yumeyuka.yumehub.navigation

import moe.yumeyuka.yumehub.core.model.GithubLinkTarget
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.core.model.parseGithubLink
import moe.yumeyuka.yumehub.feature.actions.ActionsRoute
import moe.yumeyuka.yumehub.feature.commitdetail.CommitDetailRoute
import moe.yumeyuka.yumehub.feature.discussiondetail.DiscussionDetailRoute
import moe.yumeyuka.yumehub.feature.profile.FollowingRoute
import moe.yumeyuka.yumehub.feature.profile.UserProfileRoute
import moe.yumeyuka.yumehub.feature.profilecollections.ProfileCollectionsRoute
import moe.yumeyuka.yumehub.feature.releases.ReleasesRoute
import moe.yumeyuka.yumehub.feature.repository.RepositoryRoute
import moe.yumeyuka.yumehub.feature.repositoryactivity.RepositoryActivityRoute
import moe.yumeyuka.yumehub.feature.workdetail.WorkDetailRoute
import moe.yumeyuka.yumehub.feature.worklist.WorkListRoute
import moe.yumeyuka.yumehub.feature.workspaces.WorkspacesRoute

/** 唯一 URL→页面映射。所有目标最终通过已有 navigator 进入同一条 Decompose 栈。 */
internal fun AppNavigationComponent.openLink(url: String): Boolean {
    val target = parseGithubLink(url) ?: return false
    if (state.locked) return true
    when (target) {
        GithubLinkTarget.Home -> openMainTab(MainTab.Home)
        GithubLinkTarget.Notifications -> openMainTab(MainTab.Notifications)
        is GithubLinkTarget.Profile ->
            navigator.push(
                UserProfileRoute(
                    target.login,
                    organization = target.organization,
                ),
            )
        is GithubLinkTarget.Collection ->
            if (
                target.kind in
                    setOf(
                        GithubLinkTarget.Collection.Kind.Following,
                        GithubLinkTarget.Collection.Kind.Followers,
                    )
            ) {
                navigator.push(
                    FollowingRoute(
                        target.login,
                        initialFollowing =
                            target.kind == GithubLinkTarget.Collection.Kind.Following,
                    ),
                )
            } else {
                val kind =
                    when (target.kind) {
                        GithubLinkTarget.Collection.Kind.Repositories ->
                            ProfileCollectionsRoute.Kind.Repositories
                        GithubLinkTarget.Collection.Kind.Starred ->
                            ProfileCollectionsRoute.Kind.Starred
                        GithubLinkTarget.Collection.Kind.Organizations ->
                            ProfileCollectionsRoute.Kind.Organizations
                        GithubLinkTarget.Collection.Kind.Followers,
                        GithubLinkTarget.Collection.Kind.Following -> error("Handled above")
                    }
                navigator.push(ProfileCollectionsRoute(target.login, kind, target.organization))
            }
        is GithubLinkTarget.Repository -> {
            val location = target.location
            if (location is GithubRepositoryLocation.GitObject) {
                navigator.push(
                    WorkspacesRoute(
                        owner = target.owner,
                        name = target.name,
                        gitObject = location,
                        linkUrl = url,
                    ),
                )
            } else {
                val readme = target.location as? GithubRepositoryLocation.Readme
                navigator.push(
                    RepositoryRoute(
                        target.owner,
                        target.name,
                        readme,
                    ),
                )
            }
        }
        is GithubLinkTarget.Commit ->
            navigator.push(CommitDetailRoute(target.owner, target.name, target.oid))
        is GithubLinkTarget.Discussion ->
            navigator.push(DiscussionDetailRoute(target.owner, target.name, target.number))
        is GithubLinkTarget.Releases ->
            navigator.push(ReleasesRoute(target.owner, target.name, target.tag))
        is GithubLinkTarget.Activity ->
            navigator.push(
                RepositoryActivityRoute(
                    target.owner,
                    target.name,
                    target.kind,
                    projectNumber = target.projectNumber,
                    organization = target.organization,
                ),
            )
        is GithubLinkTarget.Actions ->
            navigator.push(ActionsRoute(target.owner, target.name, run = target.run))
        is GithubLinkTarget.WorkList ->
            navigator.push(WorkListRoute(target.owner, target.name, target.kind))
        is GithubLinkTarget.Work ->
            navigator.push(WorkDetailRoute(target.owner, target.name, target.kind, target.number))
    }
    return true
}
