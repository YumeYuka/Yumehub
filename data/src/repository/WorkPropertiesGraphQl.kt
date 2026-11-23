package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.IssuePropertyChoices
import moe.yumeyuka.yumehub.data.api.dto.WorkProperties
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.api.dto.WorkReviewRequests

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

    private suspend fun node(
        includeMilestone: Boolean,
        includeReviewTarget: Boolean = false,
        includeAssignPermission: Boolean = false,
    ): JsonObject =
        execute(
                """
        query WorkPropertyTarget(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
            repository(owner: ${'$'}owner, name: ${'$'}name) {
                viewerPermission
                issueOrPullRequest(number: ${'$'}number) {
                    __typename ... on $type {
                        id ${if (includeMilestone) "milestone { id title }" else ""}
                        ${if (includeReviewTarget) "state author { login }" else ""}
                        ${if (includeAssignPermission) "viewerCanAssign" else ""}
                    }
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
        val linked = async { currentValues(target, WorkProperty.LinkedItems) }
        val subIssues = async { currentValues(target, WorkProperty.SubIssues) }
        WorkProperties(
            mapOf(
                WorkProperty.Labels to labels.await(),
                WorkProperty.Milestone to target.milestone(),
                WorkProperty.Projects to projects.await(),
                WorkProperty.LinkedItems to linked.await(),
                WorkProperty.SubIssues to subIssues.await(),
            ),
            repo.canEdit(),
        )
    }

    suspend fun reviewRequests(): WorkReviewRequests = coroutineScope {
        check(kind == CollaborationKind.PullRequest)
        val repo = node(includeMilestone = false, includeReviewTarget = true)
        val target = repo.target()
        val id = target.graphRequiredText("id")
        val author = (target["author"] as? JsonObject)?.graphText("login")
        val editable = repo.canEdit() && target.graphRequiredText("state") == "OPEN"
        val requested = async { requestedReviewers(id) }
        val reviewed = async {
            connection(
                    id,
                    "latestReviews",
                    "state author { __typename login avatarUrl ... on Node { id } }",
                )
                .mapNotNull { entry ->
                    val actor = entry["author"] as? JsonObject ?: return@mapNotNull null
                    val login = actor.graphText("login") ?: return@mapNotNull null
                    val actorId = actor.graphText("id") ?: return@mapNotNull null
                    val actorKind = actor.graphRequiredText("__typename")
                    WorkPropertyOption(
                        actorId,
                        login,
                        editable = actorKind == "User" && !login.equals(author, ignoreCase = true),
                        state = entry.graphRequiredText("state"),
                        kind = actorKind,
                        avatarUrl = actor.graphText("avatarUrl"),
                    )
                }
        }
        WorkReviewRequests(
            requested.await(),
            reviewed.await(),
            author,
            editable,
        )
    }

    suspend fun reviewerCandidates(query: String, after: String?): GithubGraphQlPage<WorkPropertyOption> {
        val page = execute(
            """
            query WorkReviewerCandidates(${'$'}owner: String!, ${'$'}name: String!, ${'$'}query: String, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    assignableUsers(first: 50, query: ${'$'}query, after: ${'$'}after) {
                        pageInfo { hasNextPage endCursor } nodes { id login avatarUrl }
                    }
                }
            }
            """.trimIndent(),
            buildJsonObject {
                put("owner", owner)
                put("name", repository)
                put("query", query.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull)
                put("after", after?.let(::JsonPrimitive) ?: JsonNull)
            },
        ).graphObject("repository").graphObject("assignableUsers")
        return GithubGraphQlPage(
            page.graphNodes().map { option(it, "login").copy(kind = "User") },
            page.graphCursor(after),
        )
    }

    private suspend fun requestedReviewers(id: String): List<WorkPropertyOption> =
        connection(
                id,
                "reviewRequests",
                """requestedReviewer {
                    __typename ... on Node { id } ... on Actor { login avatarUrl }
                    ... on Team { name } ... on EnterpriseTeam { name }
                }""",
            )
            .map { request ->
                val reviewer = request.graphObject("requestedReviewer")
                val reviewerKind = reviewer.graphRequiredText("__typename")
                WorkPropertyOption(
                    reviewer.graphRequiredText("id"),
                    reviewer.graphText("login") ?: reviewer.graphRequiredText("name"),
                    editable = reviewerKind == "User",
                    kind = reviewerKind,
                    avatarUrl = reviewer.graphText("avatarUrl"),
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
                if (kind == CollaborationKind.Issue) {
                    connection(id, "assignedActors", "__typename ... on Node { id } ... on Actor { login avatarUrl }")
                        .map { assignee ->
                            option(assignee, "login").copy(editable = assignee.graphText("__typename") == "User")
                        }
                } else {
                    connection(id, "assignees", "id login avatarUrl").map { option(it, "login") }
                }
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
            WorkProperty.Reviewers -> requestedReviewers(id)
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

    /** 头部项目标签只需要名称；缺少权限时保持为空。 */
    suspend fun headerProjectTitles(): List<String> =
        try {
            val target = node(includeMilestone = false).target()
            currentValues(target, WorkProperty.Projects).map { it.title }
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
            title = node.graphRequiredText("title"),
            number = node.graphRequiredText("number").toInt(),
            state =
                node.graphText("issueState")
                    ?: node.graphText("pullRequestState")
                    ?: node.graphRequiredText("state"),
            kind = kind,
            subtitle = node.graphObject("repository").graphRequiredText("nameWithOwner"),
        )

    /** 时间线里的交叉引用：引用此工作项的议题／拉取请求，按时间倒序，只读。 */
    suspend fun crossReferences(): List<WorkPropertyOption> {
        val nodes =
            execute(
                    """
                    query WorkCrossReferences(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
                        repository(owner: ${'$'}owner, name: ${'$'}name) {
                            issueOrPullRequest(number: ${'$'}number) {
                                ... on $type {
                                    timelineItems(itemTypes: [CROSS_REFERENCED_EVENT], last: 30) {
                                        nodes {
                                            ... on CrossReferencedEvent {
                                                createdAt
                                                source {
                                                    __typename
                                                    ... on Issue { id number title issueState: state repository { nameWithOwner } }
                                                    ... on PullRequest { id number title pullRequestState: state repository { nameWithOwner } }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    """
                        .trimIndent(),
                variables(),
            )
                .graphObject("repository")
                .graphObject("issueOrPullRequest")
                .graphObject("timelineItems")
                .graphNodes()
        return nodes
            .mapNotNull { event ->
                val source = event.graphObject("source")
                val sourceKind = source.graphText("__typename")
                if (sourceKind == "Issue" || sourceKind == "PullRequest") {
                    event.graphText("createdAt") to linkedOption(source, sourceKind)
                } else {
                    null
                }
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.id }
    }

    suspend fun issueAssignees(): IssuePropertyChoices = coroutineScope {
        check(kind == CollaborationKind.Issue)
        val target = node(includeMilestone = false, includeAssignPermission = true).target()
        val editable = target["viewerCanAssign"]?.jsonPrimitive?.booleanOrNull == true
        val selected = async { currentValues(target, WorkProperty.Assignees) }
        val candidates = if (editable) async { options(WorkProperty.Assignees) } else null
        val current = selected.await()
        IssuePropertyChoices(
            target.graphRequiredText("id"),
            current,
            (current + candidates?.await().orEmpty()).distinctBy { it.id },
            editable,
        )
    }

    private val issueMetadata by lazy { IssueMetadataGraphQl(owner, repository, number, request) }

    fun issueMetadata(): IssueMetadataGraphQl {
        check(kind == CollaborationKind.Issue)
        return issueMetadata
    }

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
        expectedIssueId: String? = null,
    ) {
        val repo =
            node(
                includeMilestone = property == WorkProperty.Milestone,
                includeReviewTarget = property == WorkProperty.Reviewers,
                includeAssignPermission = property == WorkProperty.Assignees,
            )
        val target = repo.target()
        if (property == WorkProperty.Assignees) {
            if (target["viewerCanAssign"]?.jsonPrimitive?.booleanOrNull != true)
                throw GithubPageException()
        } else if (!repo.canEdit()) throw GithubPageException()
        val id = target.graphRequiredText("id")
        if (expectedIssueId != null && id != expectedIssueId) throw WorkDetailReviewChangedException()
        val actual = currentValues(target, property)
        if (property == WorkProperty.Assignees) {
            val desired = selected.mapTo(hashSetOf()) { it.id }
            if (actual.any { !it.editable && it.id !in desired }) throw GithubPageException()
            if (actual.mapTo(hashSetOf()) { it.id } == desired) return
        }
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
            if (kind != CollaborationKind.PullRequest || target.graphRequiredText("state") != "OPEN") {
                throw WorkDetailReviewChangedException()
            }
            val actualIds = actual.map(WorkPropertyOption::id).toSet()
            val selectedIds = selected.map(WorkPropertyOption::id).toSet()
            val removed = actual.filterNot { it.id in selectedIds }
            val added = selected.filterNot { it.id in actualIds }
            if (removed.isEmpty() && added.isEmpty()) return
            if (removed.any { it.kind != "User" } || added.any { it.kind != "User" }) throw GithubPageException()
            val union = removed.isEmpty()
            if (!union && actual.any { it.kind !in setOf("User", "Team", "EnterpriseTeam", "Bot") }) {
                throw GithubPageException()
            }
            // 只新增时保留现有请求；替换时显式保留不可编辑的已有团队与机器人。
            val users = if (union) added else selected.filter { it.kind == "User" }
            val teams = if (union) emptyList() else actual.filter { it.kind == "Team" || it.kind == "EnterpriseTeam" }
            val bots = if (union) emptyList() else actual.filter { it.kind == "Bot" }
            execute(
                    """
                    mutation RequestReviewers(${'$'}id: ID!, ${'$'}users: [ID!]!, ${'$'}teams: [ID!]!, ${'$'}bots: [ID!]!, ${'$'}union: Boolean!) {
                        requestReviews(input: {pullRequestId: ${'$'}id, userIds: ${'$'}users, teamIds: ${'$'}teams, botIds: ${'$'}bots, union: ${'$'}union}) {
                            pullRequest { id }
                        }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("id", id)
                        put("users", JsonArray(users.map { JsonPrimitive(it.id) }))
                        put("teams", JsonArray(teams.map { JsonPrimitive(it.id) }))
                        put("bots", JsonArray(bots.map { JsonPrimitive(it.id) }))
                        put("union", union)
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
