package moe.yumeyuka.yumehub.feature.notifications

import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface NotificationTarget {
    data class Issue(val owner: String, val repository: String, val number: Int) :
        NotificationTarget

    data class PullRequest(val owner: String, val repository: String, val number: Int) :
        NotificationTarget

    data class Web(val url: String) : NotificationTarget
}

internal data class NotificationRepositoryName(val owner: String, val name: String) {
    val webUrl: String
        get() = "https://github.com/$owner/$name"
}

internal fun parseNotificationRepository(fullName: String): NotificationRepositoryName? {
    val segments = fullName.split('/')
    return if (segments.size == 2) repositoryName(segments[0], segments[1]) else null
}

internal fun NotificationDto.toTarget(): NotificationTarget? {
    val url =
        subject?.url?.toHttpUrlOrNull()?.takeIf {
            it.isHttps &&
                it.port == 443 &&
                it.encodedUsername.isEmpty() &&
                it.encodedPassword.isEmpty()
        }
    val path =
        when {
            url?.host == "api.github.com" && url.pathSegments.firstOrNull() == "repos" ->
                url.pathSegments.drop(1)
            url?.host == "github.com" -> url.pathSegments
            else -> emptyList()
        }
    val subjectRepository =
        path.getOrNull(0)?.let { owner ->
            path.getOrNull(1)?.let { repository -> repositoryName(owner, repository) }
        }
    val kind = path.getOrNull(2)
    val number = path.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }

    if (subjectRepository != null && path.size == 4 && number != null) {
        val (owner, name) = subjectRepository
        when {
            subject?.type == "Issue" && kind == "issues" ->
                return NotificationTarget.Issue(owner, name, number)
            subject?.type == "PullRequest" && kind in listOf("issues", "pulls", "pull") ->
                return NotificationTarget.PullRequest(owner, name, number)
            kind == "discussions" ->
                return NotificationTarget.Web("${subjectRepository.webUrl}/discussions/$number")
        }
    }

    val fallbackRepository =
        repository?.fullName?.let(::parseNotificationRepository)
            ?: subjectRepository?.takeIf { url?.host == "api.github.com" }
    return fallbackRepository?.let {
        NotificationTarget.Web(
            if (subject?.type == "Release") "${it.webUrl}/releases" else it.webUrl,
        )
    }
}

private val RepositorySegment = Regex("[A-Za-z0-9_.-]+")

private fun repositoryName(owner: String, repository: String): NotificationRepositoryName? {
    val valid =
        owner != "." &&
            owner != ".." &&
            RepositorySegment.matches(owner) &&
            repository != "." &&
            repository != ".." &&
            RepositorySegment.matches(repository)
    return if (valid) NotificationRepositoryName(owner, repository) else null
}
