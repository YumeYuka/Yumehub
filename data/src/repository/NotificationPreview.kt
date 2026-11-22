package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationRepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationSubjectDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto

internal suspend fun loadNotificationPage(
    session: GithubGraphQlSession,
    query: NotificationsQuery,
    after: String?,
): GithubGraphQlPage<NotificationDto> {
    val statuses =
        when {
            query.done -> listOf("DONE")
            query.includeRead -> listOf("READ", "UNREAD")
            else -> listOf("UNREAD")
        }
    val connection =
        session
            .execute(
                NotificationThreadsQuery,
                buildJsonObject {
                    put("first", 50)
                    if (after != null) put("after", after)
                    putJsonObject("filterBy") {
                        putJsonArray("statuses") { statuses.forEach { add(JsonPrimitive(it)) } }
                    }
                    query.repository?.let { put("query", "repo:$it") }
                },
                features = "issues_close_state",
            )
            .graphObject("viewer")
            .graphObject("notificationThreads")
    return parseNotificationPage(connection, after)
}

internal fun parseNotificationPage(
    connection: JsonObject,
    after: String?,
): GithubGraphQlPage<NotificationDto> =
    GithubGraphQlPage(connection.graphNodes().map(::notification), connection.graphCursor(after))

private fun notification(node: JsonObject): NotificationDto {
    val subject = node["optionalSubject"] as? JsonObject
    val list = node["optionalList"] as? JsonObject
    val repo =
        (subject?.get("repository") as? JsonObject)
            ?: list?.takeIf { it.graphText("__typename") == "Repository" }
    val author = node["summaryItemAuthor"] as? JsonObject
    val state = subject?.graphText("issueState") ?: subject?.graphText("pullRequestState")
    return NotificationDto(
        id = node.graphRequiredText("id"),
        unread = (node["isUnread"] as? JsonPrimitive)?.booleanOrNull ?: throw GithubPageException(),
        reason = node.graphText("reason")?.lowercase(Locale.ROOT),
        subject =
            NotificationSubjectDto(
                title = node.graphText("title") ?: throw GithubPageException(),
                type = subject?.graphText("__typename") ?: node.graphText("threadType"),
                url = subject?.graphText("url") ?: subject?.graphText("permalink"),
            ),
        repository =
            repo?.let {
                val name = it.graphRequiredText("name")
                val owner = it.graphObject("owner").graphRequiredText("login")
                NotificationRepositoryDto(name = name, fullName = "$owner/$name")
            },
        updatedAt = node.graphRequiredText("lastUpdatedAt"),
        preview =
            NotificationPreviewDto(
                state = state,
                stateReason = subject?.graphText("stateReason"),
                merged = state == "MERGED",
                draft = (subject?.get("isDraft") as? JsonPrimitive)?.booleanOrNull == true,
                author =
                    author?.let {
                        UserDto(
                            login = it.graphText("login"),
                            avatarUrl = it.graphText("avatarUrl"),
                            type = it.graphText("__typename"),
                        )
                    },
                body =
                    node.graphText("summaryItemBody")?.replace(Whitespace, " ")?.trim()?.take(512),
            ),
    )
}

private val Whitespace = Regex("\\s+")

// GitHub Mobile v1.283.0-beta，defpackage/pyo.java:48。
private val NotificationThreadsQuery =
    """
    query NotificationsQuery(
        ${'$'}first: Int!, ${'$'}after: String,
        ${'$'}filterBy: NotificationThreadFilters, ${'$'}query: String
    ) {
        viewer {
            notificationThreads(first: ${'$'}first, after: ${'$'}after, filterBy: ${'$'}filterBy, query: ${'$'}query) {
                pageInfo { hasNextPage endCursor }
                nodes {
                    id threadType title isUnread lastUpdatedAt reason
                    summaryItemAuthor { __typename login avatarUrl }
                    summaryItemBody
                    optionalList {
                        __typename
                        ... on Repository { name owner { login } }
                    }
                    optionalSubject {
                        __typename
                        ... on Issue {
                            url issueState: state stateReason
                            repository { name owner { login } }
                        }
                        ... on PullRequest {
                            url pullRequestState: state isDraft
                            repository { name owner { login } }
                        }
                        ... on Release { url repository { name owner { login } } }
                        ... on Discussion { url repository { name owner { login } } }
                        ... on Commit { url }
                        ... on Gist { url }
                        ... on CheckSuite { url }
                        ... on WorkflowRun { url }
                        ... on RepositoryInvitation { permalink }
                        ... on RepositoryVulnerabilityAlert { permalink }
                        ... on RepositoryAdvisory { url }
                    }
                }
            }
        }
    }
    """
        .trimIndent()
