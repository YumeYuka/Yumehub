package moe.yumeyuka.yumehub.navigation

import android.os.Bundle
import androidx.core.os.bundleOf
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.feature.actions.ActionsRoute
import moe.yumeyuka.yumehub.feature.commitdetail.CommitDetailRoute
import moe.yumeyuka.yumehub.feature.discovery.DiscoveryRoute
import moe.yumeyuka.yumehub.feature.discussiondetail.DiscussionDetailRoute
import moe.yumeyuka.yumehub.feature.issuesearch.IssueSearchRoute
import moe.yumeyuka.yumehub.feature.profile.FollowingRoute
import moe.yumeyuka.yumehub.feature.profile.ProfileRoute
import moe.yumeyuka.yumehub.feature.profile.UserProfileRoute
import moe.yumeyuka.yumehub.feature.profilecollections.ProfileCollectionsRoute
import moe.yumeyuka.yumehub.feature.pullrequestcommits.PullRequestCommitsRoute
import moe.yumeyuka.yumehub.feature.pullrequestdiff.PullRequestDiffRoute
import moe.yumeyuka.yumehub.feature.releases.ReleasesRoute
import moe.yumeyuka.yumehub.feature.repositories.MyRepositoriesRoute
import moe.yumeyuka.yumehub.feature.repository.RepositoryRoute
import moe.yumeyuka.yumehub.feature.repositoryactivity.RepositoryActivityRoute
import moe.yumeyuka.yumehub.feature.search.ExploreSearchRoute
import moe.yumeyuka.yumehub.feature.settings.SettingsRoute
import moe.yumeyuka.yumehub.feature.starred.StarredRoute
import moe.yumeyuka.yumehub.feature.workdetail.WorkDetailRoute
import moe.yumeyuka.yumehub.feature.worklist.WorkListRoute
import moe.yumeyuka.yumehub.feature.workspaces.CommitHistoryRoute
import moe.yumeyuka.yumehub.feature.workspaces.WorkspacesRoute
import moe.yumeyuka.yumehub.ui.navigation.AppRoute

/**
 * Activity state contains route identifiers only. A live editor or an in-memory commit group
 * restores its parent.
 */
private fun AppRoute.arguments(): Bundle? {
    fun repo(type: String, owner: String, name: String, vararg args: Pair<String, Any?>) =
        bundleOf("type" to type, "owner" to owner, "name" to name, *args)
    return when (this) {
        ProfileRoute -> bundleOf("type" to "profile")
        SettingsRoute -> bundleOf("type" to "settings")
        MyRepositoriesRoute -> bundleOf("type" to "repos")
        StarredRoute -> bundleOf("type" to "starred")
        ExploreSearchRoute -> bundleOf("type" to "search")
        is DiscoveryRoute -> bundleOf("type" to "discovery", "kind" to name)
        is IssueSearchRoute -> bundleOf("type" to "issue-search", "kind" to kind.name)
        is FollowingRoute ->
            bundleOf(
                "type" to "following",
                "login" to login,
                "following" to initialFollowing,
                "instance" to instance,
            )
        is UserProfileRoute ->
            bundleOf(
                "type" to "user",
                "login" to login,
                "node" to nodeId,
                "org" to organization,
                "instance" to instance,
            )
        is ProfileCollectionsRoute ->
            bundleOf(
                "type" to "collection",
                "login" to login,
                "kind" to kind.name,
                "org" to organization,
                "instance" to instance,
            )
        is RepositoryRoute ->
            repo(
                "repository",
                owner,
                name,
                "readme" to (readme != null),
                "anchor" to readme?.anchor,
            )
        is WorkListRoute -> repo("work-list", owner, name, "kind" to kind.name)
        is WorkDetailRoute ->
            repo("work", owner, name, "kind" to kind.name, "number" to number, "title" to title)
        is CommitDetailRoute -> repo("commit", owner, name, "oid" to oid)
        is CommitHistoryRoute -> repo("history", owner, name, "ref" to ref)
        is DiscussionDetailRoute ->
            repo("discussion", owner, name, "number" to number, "parent" to parentId)
        is PullRequestDiffRoute -> repo("diff", owner, name, "number" to number)
        is PullRequestCommitsRoute ->
            if (activityCommits == null) repo("commits", owner, name, "number" to number) else null
        is ReleasesRoute -> repo("release", owner, name, "tag" to tag)
        is ActionsRoute ->
            repo(
                "actions",
                owner,
                name,
                "run" to run,
                "suite" to suite,
                "workflow" to workflowName,
                "runNumber" to runNumber,
            )
        is RepositoryActivityRoute ->
            repo(
                "activity",
                owner,
                name,
                "kind" to kind.name,
                "node" to nodeId,
                "heading" to heading,
                "project" to projectNumber,
                "org" to organization,
            )
        is WorkspacesRoute ->
            repo(
                "workspace",
                owner,
                name,
                "ref" to ref,
                "path" to path,
                "file" to file,
                "anchor" to anchor,
                "object" to gitObject?.refAndPath,
                "directory" to gitObject?.directory,
                "objectAnchor" to gitObject?.anchor,
                "link" to linkUrl,
                "license" to license,
            )
        else -> null
    }
}

private fun Bundle.route(): AppRoute? {
    fun text(key: String) = requireNotNull(getString(key))
    fun owner() = text("owner")
    fun name() = text("name")
    fun number() = getInt("number")
    fun kind() = CollaborationKind.valueOf(text("kind"))
    return when (getString("type")) {
        "profile" -> ProfileRoute
        "settings" -> SettingsRoute
        "repos" -> MyRepositoriesRoute
        "starred" -> StarredRoute
        "search" -> ExploreSearchRoute
        "discovery" -> DiscoveryRoute.valueOf(text("kind"))
        "issue-search" -> IssueSearchRoute(kind())
        "following" -> FollowingRoute(text("login"), text("instance"), getBoolean("following"))
        "user" ->
            UserProfileRoute(
                text("login"),
                nodeId = getString("node"),
                instance = text("instance"),
                organization = getBoolean("org"),
            )
        "collection" ->
            ProfileCollectionsRoute(
                text("login"),
                ProfileCollectionsRoute.Kind.valueOf(text("kind")),
                getBoolean("org"),
                text("instance"),
            )
        "repository" ->
            RepositoryRoute(
                owner(),
                name(),
                if (getBoolean("readme")) GithubRepositoryLocation.Readme(getString("anchor"))
                else null,
            )
        "work-list" -> WorkListRoute(owner(), name(), kind())
        "work" -> WorkDetailRoute(owner(), name(), kind(), number(), getString("title"))
        "commit" -> CommitDetailRoute(owner(), name(), text("oid"))
        "history" -> CommitHistoryRoute(owner(), name(), text("ref"))
        "discussion" -> DiscussionDetailRoute(owner(), name(), number(), getString("parent"))
        "diff" -> PullRequestDiffRoute(owner(), name(), number())
        "commits" -> PullRequestCommitsRoute(owner(), name(), number())
        "release" -> ReleasesRoute(owner(), name(), getString("tag"))
        "actions" ->
            ActionsRoute(
                owner(),
                name(),
                getLong("run", -1L).takeIf { it >= 0 },
                getString("suite"),
                getString("workflow"),
                getInt("runNumber", -1).takeIf { it >= 0 },
            )
        "activity" ->
            RepositoryActivityRoute(
                owner(),
                name(),
                RepositoryActivityKind.valueOf(text("kind")),
                getString("node"),
                getString("heading"),
                getInt("project", -1).takeIf { it >= 0 },
                getBoolean("org"),
            )
        "workspace" ->
            WorkspacesRoute(
                owner(),
                name(),
                getString("ref"),
                text("path"),
                getBoolean("file"),
                getString("anchor"),
                getString("object")?.let {
                    GithubRepositoryLocation.GitObject(
                        it,
                        getBoolean("directory"),
                        getString("objectAnchor"),
                    )
                },
                getString("link"),
                getBoolean("license"),
            )
        else -> null
    }
}

internal data class RestoredNavigation(val tab: MainTab, val routes: List<AppRoute>)

internal fun AppNavigationComponent.saveNavigation(session: String): Bundle? {
    if (session != navigationSessionId) return null
    val entries = ArrayList<Bundle>()
    for (entry in routeStack.value.value.items.drop(1).takeLast(24)) {
        val args = entry.instance.route.arguments() ?: break
        entries.add(args)
    }
    return bundleOf("session" to session, "tab" to mainTab.name).apply {
        putParcelableArrayList("routes", entries)
    }
}

internal fun Bundle.restoreNavigation(session: String): RestoredNavigation? {
    if (session != getString("session")) return null
    val routes = ArrayList<AppRoute>()
    for (entry in getParcelableArrayList("routes", Bundle::class.java).orEmpty()) {
        routes.add(
            try {
                entry.route()
            } catch (_: IllegalArgumentException) {
                null
            } ?: break,
        )
    }
    val tab = MainTab.entries.firstOrNull { it.name == getString("tab") } ?: MainTab.Home
    return RestoredNavigation(tab, routes)
}
