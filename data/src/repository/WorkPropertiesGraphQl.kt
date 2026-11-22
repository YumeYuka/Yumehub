package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperties
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption

internal class WorkPropertiesGraphQl(
    private val owner: String,
    private val repository: String,
    private val kind: CollaborationKind,
    private val number: Int,
    private val request: suspend (String, JsonObject, Boolean) -> JsonObject,
) {
    private val type
        get() = if (kind == CollaborationKind.Issue) "Issue" else "PullRequest"

    private suspend fun execute(query: String, variables: JsonObject, write: Boolean = false) =
        request(query, variables, write)

    private fun variables() = buildJsonObject {
        put("owner", owner)
        put("name", repository)
        put("number", number)
    }

    private suspend fun node(includeMilestone: Boolean): JsonObject =
        execute(
                """
        query WorkPropertyTarget(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
            repository(owner: ${'$'}owner, name: ${'$'}name) {
                viewerPermission
                issueOrPullRequest(number: ${'$'}number) {
                    __typename ... on $type { id ${if (includeMilestone) "milestone { id title }" else ""} }
                }
            }
        }
    """
                    .trimIndent(),
                variables(),
            )
            .graphObject("repository")

    private suspend fun connection(id: String, field: String, fields: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val cursors = mutableSetOf<String>()
        var after: String? = null
        val arguments =
            if (field == "closedByPullRequestsReferences") ", includeClosedPrs: true" else ""
        do {
            val data =
                execute(
                        """
                query WorkPropertyConnection(${'$'}id: ID!, ${'$'}after: String) {
                    node(id: ${'$'}id) { ... on $type {
                        $field(first: 100, after: ${'$'}after$arguments) {
                            pageInfo { hasNextPage endCursor } nodes { $fields }
                        }
                    } }
                }
            """
                            .trimIndent(),
                        buildJsonObject {
                            put("id", id)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                    .graphObject("node")
                    .graphObject(field)
            result.addAll(data.graphNodes())
            after = data.graphCursor(after)
            if (after != null && (!cursors.add(after) || cursors.size >= 100))
                throw GithubPageException()
        } while (after != null)
        return result
    }

    private fun JsonObject.target(): JsonObject =
        graphObject("issueOrPullRequest").also {
            if (it.graphRequiredText("__typename") != type) throw WorkDetailTypeMismatchException()
        }

    private fun JsonObject.canEdit() =
        graphText("viewerPermission") in setOf("TRIAGE", "WRITE", "MAINTAIN", "ADMIN")

    private fun JsonObject.milestone() =
        when (val value = get("milestone")) {
            JsonNull -> emptyList()
            is JsonObject -> listOf(option(value, "title"))
            else -> throw GithubPageException()
        }

    suspend fun load(): WorkProperties = coroutineScope {
        val repo = node(includeMilestone = true)
        val target = repo.target()
        val labels = async { currentValues(target, WorkProperty.Labels) }
        // Projects V2 需要额外权限；只有展示时允许该分组独立失败。
        val projects = async { optional { currentValues(target, WorkProperty.Projects) } }
        val reviewers = async { currentValues(target, WorkProperty.Reviewers) }
        val linked = async { currentValues(target, WorkProperty.LinkedItems) }
        val subIssues = async { currentValues(target, WorkProperty.SubIssues) }
        WorkProperties(
            mapOf(
                WorkProperty.Labels to labels.await(),
                WorkProperty.Milestone to target.milestone(),
                WorkProperty.Projects to projects.await(),
                WorkProperty.Reviewers to reviewers.await(),
                WorkProperty.LinkedItems to linked.await(),
                WorkProperty.SubIssues to subIssues.await(),
            ),
            repo.canEdit(),
        )
    }

    private suspend fun currentValues(
        target: JsonObject,
        property: WorkProperty,
    ): List<WorkPropertyOption> {
        val id = target.graphRequiredText("id")
        return when (property) {
            WorkProperty.Milestone -> target.milestone()
            WorkProperty.Labels ->
                connection(id, "labels", "id name color").map {
                    option(it, "name").copy(color = it.graphText("color"))
                }
            WorkProperty.Assignees ->
                connection(id, "assignees", "id login avatarUrl").map { option(it, "login") }
            WorkProperty.Projects ->
                connection(id, "projectItems", "id project { id title viewerCanUpdate }").map {
                    val project = it.graphObject("project")
                    WorkPropertyOption(
                        project.graphRequiredText("id"),
                        project.graphRequiredText("title"),
                        project["viewerCanUpdate"]?.jsonPrimitive?.booleanOrNull == true,
                        it.graphRequiredText("id"),
                    )
                }
            WorkProperty.Reviewers ->
                if (kind == CollaborationKind.PullRequest) {
                    connection(
                            id,
                            "reviewRequests",
                            "requestedReviewer { __typename ... on User { id login avatarUrl } ... on Team { id name } }",
                        )
                        .mapNotNull { request ->
                            val reviewer =
                                request["requestedReviewer"] as? JsonObject
                                    ?: return@mapNotNull null
                            when (reviewer.graphText("__typename")) {
                                "User" ->
                                    WorkPropertyOption(
                                        reviewer.graphRequiredText("id"),
                                        reviewer.graphRequiredText("login"),
                                        avatarUrl = reviewer.graphText("avatarUrl"),
                                        kind = "User",
                                    )
                                "Team" ->
                                    WorkPropertyOption(
                                        reviewer.graphRequiredText("id"),
                                        reviewer.graphRequiredText("name"),
                                        editable = false,
                                        kind = "Team",
                                    )
                                else -> null
                            }
                        }
                } else {
                    emptyList()
                }
            WorkProperty.LinkedItems -> {
                val issue = kind == CollaborationKind.Issue
                val field =
                    if (issue) "closedByPullRequestsReferences" else "closingIssuesReferences"
                connection(id, field, "id number title state repository { nameWithOwner }").map {
                    linkedOption(it, if (issue) "PullRequest" else "Issue").copy(editable = false)
                }
            }
            WorkProperty.SubIssues ->
                if (kind == CollaborationKind.Issue) {
                    connection(
                            id,
                            "subIssues",
                            "id number title issueState: state repository { nameWithOwner }",
                        )
                        .map { linkedOption(it, "Issue") }
                } else {
                    emptyList()
                }
        }
    }

    private suspend fun optional(
        block: suspend () -> List<WorkPropertyOption>,
    ): List<WorkPropertyOption> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun linkedOptions(
        query: String,
        after: String?,
    ): GithubGraphQlPage<WorkPropertyOption> {
        val text = query.trim().filterNot { it == '"' || it == '\\' }
        val search = buildString {
            append("repo:$owner/$repository ")
            append(if (kind == CollaborationKind.PullRequest) "is:issue" else "is:pr")
            if (text.isNotEmpty()) append(" ").append(text)
        }
        val connection =
            execute(
                    """
                    query WorkLinkedSearch(${'$'}query: String!, ${'$'}after: String) {
                        search(query: ${'$'}query, type: ISSUE, first: 50, after: ${'$'}after) {
                            pageInfo { hasNextPage endCursor }
                            nodes {
                                ... on Issue { id number title issueState: state repository { nameWithOwner } }
                                ... on PullRequest { id number title pullRequestState: state repository { nameWithOwner } }
                            }
                        }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("query", search)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
                .graphObject("search")
        val repositoryName = "$owner/$repository"
        return GithubGraphQlPage(
            connection
                .graphNodes()
                .map {
                    linkedOption(
                        it,
                        if (kind == CollaborationKind.PullRequest) "Issue" else "PullRequest",
                    )
                }
                .filterNot {
                    it.subtitle.equals(repositoryName, ignoreCase = true) &&
                        it.title.startsWith("#$number ")
                },
            connection.graphCursor(after),
        )
    }

    suspend fun subIssueOptions(
        query: String,
        after: String?,
    ): GithubGraphQlPage<WorkPropertyOption> {
        if (kind != CollaborationKind.Issue) throw GithubPageException()
        val text = query.trim().filterNot { it == '"' || it == '\\' }
        val search = buildString {
            append("repo:$owner/$repository is:issue")
            if (text.isNotEmpty()) append(" ").append(text)
        }
        val data =
            execute(
                """
                query WorkSubIssueSearch(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}query: String!, ${'$'}after: String) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) {
                        issueOrPullRequest(number: ${'$'}number) { __typename ... on Issue { id } }
                    }
                    search(query: ${'$'}query, type: ISSUE, first: 50, after: ${'$'}after) {
                        pageInfo { hasNextPage endCursor }
                        nodes {
                            ... on Issue {
                                id number title issueState: state parent { id }
                                repository { nameWithOwner }
                            }
                        }
                    }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("owner", owner)
                    put("name", repository)
                    put("number", number)
                    put("query", search)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        val currentIssueId = data.graphObject("repository").target().graphRequiredText("id")
        val connection = data.graphObject("search")
        val items =
            connection
                .graphNodes()
                .filter { it.graphText("id") != currentIssueId && it["parent"] !is JsonObject }
                .map { linkedOption(it, "Issue") }
        return GithubGraphQlPage(items, connection.graphCursor(after))
    }

    private fun linkedOption(node: JsonObject, kind: String) =
        WorkPropertyOption(
            id = node.graphRequiredText("id"),
            title = "#${node.graphRequiredText("number")} ${node.graphRequiredText("title")}",
            state =
                node.graphText("issueState")
                    ?: node.graphText("pullRequestState")
                    ?: node.graphRequiredText("state"),
            kind = kind,
            subtitle = node.graphObject("repository").graphRequiredText("nameWithOwner"),
        )

    suspend fun options(property: WorkProperty): List<WorkPropertyOption> {
        if (property == WorkProperty.LinkedItems || property == WorkProperty.SubIssues)
            return emptyList()
        val field =
            when (property) {
                WorkProperty.Reviewers -> "assignableUsers"
                WorkProperty.Assignees -> "assignableUsers"
                WorkProperty.Labels -> "labels"
                WorkProperty.Milestone -> "milestones"
                WorkProperty.Projects -> "projectsV2"
                WorkProperty.LinkedItems,
                WorkProperty.SubIssues -> error("No property options")
            }
        val title =
            when (property) {
                WorkProperty.Reviewers -> "login"
                WorkProperty.Assignees -> "login"
                WorkProperty.Labels -> "name"
                else -> "title"
            }
        val extra =
            when (property) {
                WorkProperty.Reviewers -> "avatarUrl"
                WorkProperty.Projects -> "viewerCanUpdate"
                WorkProperty.Labels -> "color"
                WorkProperty.Assignees -> "avatarUrl"
                else -> ""
            }
        val result = mutableListOf<WorkPropertyOption>()
        val cursors = mutableSetOf<String>()
        var after: String? = null
        do {
            val data =
                execute(
                        """
                query WorkPropertyOptions(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) {
                        $field(first: 100, after: ${'$'}after) {
                            pageInfo { hasNextPage endCursor } nodes { id $title $extra }
                        }
                    }
                }
            """
                            .trimIndent(),
                        buildJsonObject {
                            put("owner", owner)
                            put("name", repository)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                    .graphObject("repository")
                    .graphObject(field)
            result.addAll(
                data.graphNodes().map {
                    option(it, title)
                        .copy(
                            editable =
                                property != WorkProperty.Projects ||
                                    it["viewerCanUpdate"]?.jsonPrimitive?.booleanOrNull == true,
                            color = it.graphText("color"),
                            kind = if (property == WorkProperty.Reviewers) "User" else null,
                        )
                },
            )
            after = data.graphCursor(after)
            if (after != null && (!cursors.add(after) || cursors.size >= 100))
                throw GithubPageException()
        } while (after != null)
        return result.distinctBy { it.id }
    }

    suspend fun save(
        property: WorkProperty,
        original: List<WorkPropertyOption>,
        selected: List<WorkPropertyOption>,
    ) {
        val repo = node(includeMilestone = property == WorkProperty.Milestone)
        val target = repo.target()
        if (!repo.canEdit()) throw GithubPageException()
        val id = target.graphRequiredText("id")
        val actual = currentValues(target, property)
        // 比较目标属性而不是整份详情，避免其他人的评论让无关属性保存失效。
        if (actual.map { it.id }.toSet() != original.map { it.id }.toSet())
            throw WorkDetailReviewChangedException()
        if (property == WorkProperty.LinkedItems) {
            // 官方操作只提供 linkingIds；已有链接保持选中，避免把取消勾选伪装为删除。
            require(actual.all { existing -> selected.any { it.id == existing.id } })
            val additions = selected.filter { candidate -> actual.none { it.id == candidate.id } }
            if (additions.isEmpty()) return
            execute(
                    """
                    mutation LinkWorkItems(${'$'}id: ID!, ${'$'}links: [ID!]!) {
                        linkIssueOrPullRequest(input: {baseIssueOrPullRequestId: ${'$'}id, linkingIds: ${'$'}links}) {
                            linkedIssuesOrPullRequests { __typename }
                        }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("id", id)
                        put("links", JsonArray(additions.map { JsonPrimitive(it.id) }))
                    },
                    write = true,
                )
                .graphObject("linkIssueOrPullRequest")
            return
        }
        if (property == WorkProperty.SubIssues) {
            if (kind != CollaborationKind.Issue) throw GithubPageException()
            val desired = selected.map { it.id }.toSet()
            val existing = actual.map { it.id }.toSet()
            actual
                .filter { it.id !in desired }
                .forEach { subIssueId ->
                    execute(
                            """
                            mutation RemoveSubIssue(${'$'}issueId: ID!, ${'$'}subIssueId: ID!) {
                                removeSubIssue(input: {issueId: ${'$'}issueId, subIssueId: ${'$'}subIssueId}) {
                                    subIssue { id }
                                }
                            }
                            """
                                .trimIndent(),
                            buildJsonObject {
                                put("issueId", id)
                                put("subIssueId", subIssueId.id)
                            },
                            write = true,
                        )
                        .graphObject("removeSubIssue")
                        .graphObject("subIssue")
                        .graphRequiredText("id")
                }
            selected
                .filter { it.id !in existing }
                .forEach { subIssue ->
                    execute(
                            """
                            mutation AddSubIssue(${'$'}issueId: ID!, ${'$'}subIssueId: ID!) {
                                addSubIssue(input: {issueId: ${'$'}issueId, subIssueId: ${'$'}subIssueId}) {
                                    subIssue { id }
                                }
                            }
                            """
                                .trimIndent(),
                            buildJsonObject {
                                put("issueId", id)
                                put("subIssueId", subIssue.id)
                            },
                            write = true,
                        )
                        .graphObject("addSubIssue")
                        .graphObject("subIssue")
                        .graphRequiredText("id")
                }
            return
        }
        if (property == WorkProperty.Reviewers) {
            if (kind != CollaborationKind.PullRequest) throw GithubPageException()
            val users = selected.filter { it.kind != "Team" }.map { it.id }
            // 选择器不提供团队候选；替换用户列表时保留现有团队，避免编辑用户时静默丢掉团队。
            val teams = actual.filter { it.kind == "Team" }.map { it.id }
            execute(
                    """
                    mutation RequestReviewers(${'$'}id: ID!, ${'$'}users: [ID!]!, ${'$'}teams: [ID!]!) {
                        requestReviews(input: {pullRequestId: ${'$'}id, userIds: ${'$'}users, teamIds: ${'$'}teams, union: false}) {
                            pullRequest { id }
                        }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("id", id)
                        put("users", JsonArray(users.map { JsonPrimitive(it) }))
                        put("teams", JsonArray(teams.map { JsonPrimitive(it) }))
                    },
                    write = true,
                )
                .graphObject("requestReviews")
                .graphObject("pullRequest")
                .graphRequiredText("id")
            return
        }
        if (property == WorkProperty.Projects) {
            val desired = selected.map { it.id }.toSet()
            val existing = actual.map { it.id }.toSet()
            actual
                .filter { it.id !in desired }
                .forEach { project ->
                    if (!project.editable) throw GithubPageException()
                    execute(
                            """
                            mutation RemoveWorkProject(${'$'}project: ID!, ${'$'}item: ID!) {
                                deleteProjectV2Item(input: {projectId: ${'$'}project, itemId: ${'$'}item}) { deletedItemId }
                            }
                            """
                                .trimIndent(),
                            buildJsonObject {
                                put("project", project.id)
                                put("item", requireNotNull(project.itemId))
                            },
                            write = true,
                        )
                        .graphObject("deleteProjectV2Item")
                        .graphRequiredText("deletedItemId")
                }
            selected
                .filter { it.id !in existing }
                .forEach { project ->
                    if (!project.editable) throw GithubPageException()
                    execute(
                            """
                            mutation AddWorkProject(${'$'}project: ID!, ${'$'}id: ID!) {
                                addProjectV2ItemById(input: {projectId: ${'$'}project, contentId: ${'$'}id}) { item { id } }
                            }
                            """
                                .trimIndent(),
                            buildJsonObject {
                                put("project", project.id)
                                put("id", id)
                            },
                            write = true,
                        )
                        .graphObject("addProjectV2ItemById")
                        .graphObject("item")
                        .graphRequiredText("id")
                }
            return
        }
        val field =
            when (property) {
                WorkProperty.Assignees -> "assigneeIds"
                WorkProperty.Labels -> "labelIds"
                WorkProperty.Milestone -> "milestoneId"
                else -> error("Unsupported property")
            }
        require(property != WorkProperty.Milestone || selected.size <= 1)
        val input = buildJsonObject {
            put(if (kind == CollaborationKind.Issue) "id" else "pullRequestId", id)
            put(
                field,
                if (property == WorkProperty.Milestone)
                    selected.singleOrNull()?.id?.let(::JsonPrimitive) ?: JsonNull
                else JsonArray(selected.map { JsonPrimitive(it.id) }),
            )
        }
        val mutation = if (kind == CollaborationKind.Issue) "updateIssue" else "updatePullRequest"
        val inputType =
            if (kind == CollaborationKind.Issue) "UpdateIssueInput" else "UpdatePullRequestInput"
        val payload = if (kind == CollaborationKind.Issue) "issue" else "pullRequest"
        execute(
                """
            mutation SaveWorkProperty(${'$'}input: $inputType!) {
                $mutation(input: ${'$'}input) { $payload { id } }
            }
        """
                    .trimIndent(),
                buildJsonObject { put("input", input) },
                write = true,
            )
            .graphObject(mutation)
            .graphObject(payload)
            .graphRequiredText("id")
    }

    private fun option(node: JsonObject, title: String) =
        WorkPropertyOption(
            node.graphRequiredText("id"),
            node.graphRequiredText(title),
            avatarUrl = node.graphText("avatarUrl"),
        )
}
