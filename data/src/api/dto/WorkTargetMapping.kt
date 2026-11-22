package moe.yumeyuka.yumehub.data.api.dto

import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 把 API 返回的条目解析成可安全导航的 [WorkTarget]，任何异常形态都返回 null。 */
fun CollaborationItemDto.workTarget(): WorkTarget? {
    val url = htmlUrl?.toHttpUrlOrNull() ?: return null
    if (
        !url.isHttps ||
            url.host != "github.com" ||
            url.port != 443 ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty()
    ) {
        return null
    }
    val path = url.pathSegments
    if (path.size != 4 || number <= 0 || path[3] != number.toString()) return null
    val (owner, repository, type) = path
    if (
        !GithubOwner.matches(owner) ||
            !GithubRepository.matches(repository) ||
            repository == "." ||
            repository == ".."
    ) {
        return null
    }
    val kind =
        when (type) {
            "issues" -> CollaborationKind.Issue
            "pull" -> CollaborationKind.PullRequest
            else -> return null
        }
    return WorkTarget(owner, repository, number, kind)
}

private val GithubOwner = Regex("[A-Za-z0-9_-]+")
private val GithubRepository = Regex("[A-Za-z0-9_.-]+")
