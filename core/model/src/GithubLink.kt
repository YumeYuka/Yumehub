package moe.yumeyuka.yumehub.core.model

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** 网页链接的领域目标；页面路由仍由 app 映射，feature 不互相依赖。 */
sealed interface GithubLinkTarget {
    data object Home : GithubLinkTarget

    data object Notifications : GithubLinkTarget

    data class Profile(val login: String, val organization: Boolean = false) : GithubLinkTarget

    data class Collection(
        val login: String,
        val kind: Kind,
        val organization: Boolean = false,
    ) : GithubLinkTarget {
        enum class Kind {
            Repositories,
            Starred,
            Organizations,
            Following,
            Followers,
        }
    }

    data class Repository(
        val owner: String,
        val name: String,
        val location: GithubRepositoryLocation? = null,
    ) : GithubLinkTarget

    data class Commit(val owner: String, val name: String, val oid: String) : GithubLinkTarget

    data class Discussion(val owner: String, val name: String, val number: Int) : GithubLinkTarget

    data class Releases(val owner: String, val name: String, val tag: String? = null) :
        GithubLinkTarget

    data class Actions(val owner: String, val name: String, val run: Long? = null) :
        GithubLinkTarget

    data class Activity(
        val owner: String,
        val name: String,
        val kind: RepositoryActivityKind,
        val projectNumber: Int? = null,
        val organization: Boolean = false,
    ) : GithubLinkTarget

    data class WorkList(val owner: String, val name: String, val kind: CollaborationKind) :
        GithubLinkTarget

    data class Work(
        val owner: String,
        val name: String,
        val kind: CollaborationKind,
        val number: Int,
    ) : GithubLinkTarget
}

sealed interface GithubRepositoryLocation {
    data class Readme(val anchor: String? = null) : GithubRepositoryLocation

    /** 保留整个 ref/path，取得真实 refs 后再拆分，不能假设分支名只有一段。 */
    data class GitObject(
        val refAndPath: String,
        val directory: Boolean,
        val anchor: String? = null,
    ) : GithubRepositoryLocation
}

data class GithubGitObject(val ref: String, val path: String, val anchor: String? = null)

/** 一个可能的 ref/path 拆分；顺序从最长 ref 到最短 ref。 */
data class GithubGitRefCandidate(val ref: String, val path: String) {
    val qualifiedRefs: List<String>
        get() =
            if (ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/")) listOf(ref)
            else listOf("refs/heads/$ref", "refs/tags/$ref")
}

fun githubGitRefCandidates(
    location: GithubRepositoryLocation.GitObject,
): List<GithubGitRefCandidate> {
    val segments = location.refAndPath.split('/')
    if (segments.any(String::isEmpty)) return emptyList()
    val minRefSize =
        if (segments.first() == "refs" && segments.getOrNull(1) in listOf("heads", "tags")) 3 else 1
    return (segments.size downTo minRefSize).mapNotNull { refSize ->
        val ref = segments.take(refSize).joinToString("/")
        val path = segments.drop(refSize).joinToString("/")
        if ((!location.directory && path.isEmpty()) || !ref.isGitRefName()) null
        else GithubGitRefCandidate(ref, path)
    }
}

private fun String.isGitRefName(): Boolean =
    !endsWith('.') &&
        ".." !in this &&
        "@{" !in this &&
        none { it <= ' ' || it == '\u007f' || it in "~^:?*[\\" } &&
        split('/').none { it.startsWith('.') || it.endsWith(".lock") }

fun resolveGithubGitObject(
    location: GithubRepositoryLocation.GitObject,
    refs: List<String>,
    defaultRef: String? = null,
): GithubGitObject? {
    val first = location.refAndPath.substringBefore('/')
    if (first == "HEAD") {
        val path = location.refAndPath.removePrefix("HEAD").removePrefix("/")
        if (!location.directory && path.isEmpty()) return null
        return defaultRef?.let { GithubGitObject(it, path, location.anchor) }
    }
    val knownRefs = buildList {
        refs.forEach { ref ->
            if (ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/")) add(ref)
        }
        defaultRef?.takeIf(String::isNotBlank)?.let(::add)
    }
        .distinct()
    githubGitRefCandidates(location).forEach { candidate ->
        candidate.qualifiedRefs
            .firstOrNull { it in knownRefs }
            ?.let { ref ->
                return GithubGitObject(ref, candidate.path, location.anchor)
            }
    }
    val ref = first.takeIf { CommitId.matches(it) } ?: return null
    val path = location.refAndPath.removePrefix(first).removePrefix("/")
    if (!location.directory && path.isEmpty()) return null
    return GithubGitObject(ref, path, location.anchor)
}

/** 只接管当前 dotcom 页面可承接的目标；未知路径、查询和锚点保留原 URL 供浏览器处理。 */
fun parseGithubLink(url: String): GithubLinkTarget? {
    val uri = githubWebUri(url) ?: return null
    if (uri.host.lowercase(Locale.ROOT) !in setOf("github.com", "www.github.com")) return null
    val path = uri.rawPath.orEmpty().removePrefix("/").trimEnd('/')
    val segments =
        if (path.isEmpty()) emptyList()
        else path.split('/').map { URI("https://github.com/$it").path.removePrefix("/") }
    if (
        segments.any { segment ->
            segment.isEmpty() ||
                segment.any { it < ' ' || it == '\u007f' || it == '\\' } ||
                segment.split('/').any { it == "." || it == ".." }
        }
    )
        return null
    val query =
        uri.rawQuery
            ?.takeIf { it.isNotEmpty() }
            ?.split('&')
            ?.map {
                val parts = it.split('=', limit = 2)
                decodeQuery(parts[0]) to decodeQuery(parts.getOrElse(1) { "" })
            }
            .orEmpty()
    if (query.any { (key, _) -> key != "tab" && key !in TrackingParameters }) return null
    if (query.count { it.first == "tab" } > 1) return null
    val tab = query.firstOrNull { it.first == "tab" }?.second
    val anchor = uri.fragment?.takeIf { it.isNotEmpty() }
    if (segments.isEmpty()) {
        return if (tab == null && anchor == null) GithubLinkTarget.Home else null
    }
    val owner = segments[0]
    if (owner == "notifications" && segments.size == 1 && tab == null && anchor == null) {
        return GithubLinkTarget.Notifications
    }
    if (
        owner in setOf("orgs", "users") &&
            segments.size == 4 &&
            segments[2] == "projects" &&
            anchor == null &&
            tab == null
    ) {
        val login = segments[1].takeIf { AccountName.matches(it) } ?: return null
        val number = segments[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return GithubLinkTarget.Activity(
            login,
            "",
            RepositoryActivityKind.ProjectItems,
            number,
            owner == "orgs",
        )
    }
    if (owner == "orgs" && segments.size in 2..3 && anchor == null && tab == null) {
        val login = segments[1].takeIf { AccountName.matches(it) } ?: return null
        return when (segments.getOrNull(2)) {
            null -> GithubLinkTarget.Profile(login, organization = true)
            "repositories" ->
                GithubLinkTarget.Collection(
                    login,
                    GithubLinkTarget.Collection.Kind.Repositories,
                    organization = true,
                )
            else -> null
        }
    }
    if (!AccountName.matches(owner) || owner.lowercase(Locale.ROOT) in ReservedNames) return null
    if (segments.size == 1) {
        if (anchor != null) return null
        return when (tab) {
            null,
            "overview" -> GithubLinkTarget.Profile(owner)
            "repositories" ->
                GithubLinkTarget.Collection(owner, GithubLinkTarget.Collection.Kind.Repositories)
            "stars" -> GithubLinkTarget.Collection(owner, GithubLinkTarget.Collection.Kind.Starred)
            "organizations" ->
                GithubLinkTarget.Collection(owner, GithubLinkTarget.Collection.Kind.Organizations)
            "following" ->
                GithubLinkTarget.Collection(owner, GithubLinkTarget.Collection.Kind.Following)
            "followers" ->
                GithubLinkTarget.Collection(owner, GithubLinkTarget.Collection.Kind.Followers)
            else -> null
        }
    }
    if (
        segments.size == 2 &&
            tab == null &&
            anchor == null &&
            segments[1] in setOf("following", "followers")
    ) {
        return GithubLinkTarget.Collection(
            owner,
            if (segments[1] == "following") GithubLinkTarget.Collection.Kind.Following
            else GithubLinkTarget.Collection.Kind.Followers,
        )
    }
    val name = segments[1].removeSuffix(".git")
    if (tab != null || name in setOf(".", "..") || !RepositoryName.matches(name)) return null
    if (segments.size == 2) {
        return GithubLinkTarget.Repository(
            owner,
            name,
            anchor?.let { GithubRepositoryLocation.Readme(it.takeUnless { it == "readme" }) },
        )
    }
    if (segments[2] == "actions" && (anchor == null || anchor == "artifacts")) {
        if (segments.size == 3 && anchor == null) return GithubLinkTarget.Actions(owner, name)
        if (segments.size == 5 && segments[3] == "runs") {
            return segments[4]
                .toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { GithubLinkTarget.Actions(owner, name, it) }
        }
    }
    if (anchor == null) {
        if (segments.size == 4 && segments[2] == "commit" && CommitId.matches(segments[3]))
            return GithubLinkTarget.Commit(owner, name, segments[3])
        if (segments.size == 4 && segments[2] == "discussions")
            return segments[3]
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { GithubLinkTarget.Discussion(owner, name, it) }
        val kind =
            when {
                segments.size == 3 && segments[2] == "projects" -> RepositoryActivityKind.Projects
                segments.size == 3 && segments[2] == "discussions" ->
                    RepositoryActivityKind.Discussions
                segments.size == 3 && segments[2] == "watchers" -> RepositoryActivityKind.Watchers
                segments.size == 4 && segments[2] == "graphs" && segments[3] == "contributors" ->
                    RepositoryActivityKind.Contributors
                else -> null
            }
        if (kind != null) return GithubLinkTarget.Activity(owner, name, kind)
    }
    return when (segments[2]) {
        "releases" -> {
            if (anchor != null) return null
            when {
                segments.size == 3 || segments.size == 4 && segments[3] == "latest" ->
                    GithubLinkTarget.Releases(owner, name)
                segments.size >= 5 && segments[3] == "tag" -> {
                    val tag = segments.drop(4).joinToString("/")
                    if (tag.split('/').any(String::isEmpty)) return null
                    GithubLinkTarget.Releases(owner, name, tag)
                }
                else -> null
            }
        }
        "issues",
        "pull",
        "pulls" -> {
            if (anchor != null) return null
            val kind =
                if (segments[2] == "issues") CollaborationKind.Issue
                else CollaborationKind.PullRequest
            when (segments.size) {
                3 -> GithubLinkTarget.WorkList(owner, name, kind)
                4 ->
                    segments[3]
                        .takeIf { it.all(Char::isDigit) }
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { GithubLinkTarget.Work(owner, name, kind, it) }
                else -> null
            }
        }
        "tree",
        "blob" -> {
            if (segments.size < 4) return null
            // 代码行锚点仍交给浏览器；文件内的标题锚点随文件打开。
            if (anchor != null && CodeLineAnchor.matches(anchor)) return null
            if (segments.drop(3).any { part -> part.split('/').any(String::isEmpty) }) return null
            GithubLinkTarget.Repository(
                owner,
                name,
                GithubRepositoryLocation.GitObject(
                    segments.drop(3).joinToString("/"),
                    directory = segments[2] == "tree",
                    anchor = anchor,
                ),
            )
        }
        else -> null
    }
}

/** 系统入口与内部解析共用同一协议/主机边界，OAuth host 不会进入此入口。 */
fun githubWebUri(url: String): URI? {
    if (url.length > 8192) return null
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val dotcom = host in setOf("github.com", "www.github.com")
    val enterprise = scheme == "https" && host.endsWith(".ghe.com") && host.length > 8
    if (
        scheme !in setOf("https", "github") ||
            (!dotcom && !enterprise) ||
            uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443)
    )
        return null
    return if (scheme == "github") URI("https" + url.substring(uri.scheme.length)) else uri
}

private fun decodeQuery(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

private val AccountName = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
private val RepositoryName = Regex("[A-Za-z0-9_.-]+")
private val CommitId = Regex("[a-fA-F0-9]{7,40}")
private val CodeLineAnchor = Regex("L\\d+(?:-L\\d+)?")
private val TrackingParameters = setOf("utm_source", "utm_medium", "utm_campaign")

private val ReservedNames =
    setOf(
        "about",
        "account",
        "account_verifications",
        "apps",
        "auth",
        "blog",
        "business",
        "codespaces",
        "collections",
        "contact",
        "copilot",
        "customer-stories",
        "dashboard",
        "enterprise",
        "enterprises",
        "events",
        "explore",
        "features",
        "issues",
        "join",
        "login",
        "logout",
        "marketplace",
        "mcp",
        "new",
        "notifications",
        "organizations",
        "orgs",
        "password_reset",
        "pricing",
        "pulls",
        "readme",
        "search",
        "security",
        "session",
        "sessions",
        "settings",
        "signup",
        "site",
        "solutions",
        "sponsors",
        "stars",
        "topics",
        "trending",
        "users",
    )
