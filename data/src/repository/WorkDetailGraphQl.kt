package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.*
import org.jsoup.Jsoup

internal fun String.workTaskProgress(): WorkTaskProgressDto? {
    if (!contains("task-list-item-checkbox") && !contains("tlb-")) return null
    val tasks =
        Jsoup.parseBodyFragment(this)
            .select(
                "input.task-list-item-checkbox, input.tlb-checkbox, input.tlb-issue-reference-number",
            )
    if (tasks.isEmpty()) return null
    return WorkTaskProgressDto(
        completed = tasks.count { it.hasAttr("checked") },
        total = tasks.size,
    )
}

internal class WorkDetailGraphQl(
    private val session: GithubGraphQlSession,
    private val owner: String,
    private val repository: String,
    private val kind: CollaborationKind,
    private val number: Int,
) {
    private val properties =
        WorkPropertiesGraphQl(owner, repository, kind, number) { query, variables, write ->
            session.execute(query, variables, write)
        }

    suspend fun properties(): WorkProperties = properties.load()

    suspend fun propertyValues(property: WorkProperty) = properties.values(property)

    suspend fun reviewRequests() = properties.reviewRequests()

    suspend fun reviewerCandidates(query: String, after: String?) =
        properties.reviewerCandidates(query, after)

    suspend fun issueAssignees() = properties.issueAssignees()

    fun issueMetadata() = properties.issueMetadata()

    suspend fun crossReferences(): List<WorkPropertyOption> = properties.crossReferences()

    suspend fun linkedOptions(query: String, after: String?) =
        properties.linkedOptions(query, after)

    suspend fun subIssueOptions(query: String, after: String?) =
        properties.subIssueOptions(query, after)

    suspend fun propertyOptions(property: WorkProperty): List<WorkPropertyOption> =
        properties.options(property)

    suspend fun saveProperty(
        property: WorkProperty,
        original: List<WorkPropertyOption>,
        selected: List<WorkPropertyOption>,
        expectedIssueId: String? = null,
        onStep: ((List<String>) -> Unit)? = null,
    ) {
        properties.save(property, original, selected, expectedIssueId, onStep)
    }

    suspend fun editPullRequest(id: String, field: String, value: String): String {
        require(field == "title" || field == "baseRefName")
        return session
            .execute(
                """
                mutation EditPullRequest(${'$'}input: UpdatePullRequestInput!) {
                    updatePullRequest(input: ${'$'}input) { pullRequest { id $field } }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put(
                        "input",
                        buildJsonObject {
                            put("pullRequestId", id)
                            put(field, value)
                        },
                    )
                },
                write = true,
            )
            .graphObject("updatePullRequest")
            .graphObject("pullRequest")
            .requireNode(id)
            .graphRequiredText(field)
    }

    suspend fun editBody(id: String, body: String): String {
        val operation = if (kind == CollaborationKind.Issue) "updateIssue" else "updatePullRequest"
        val inputField = if (kind == CollaborationKind.Issue) "id" else "pullRequestId"
        val resultField = if (kind == CollaborationKind.Issue) "issue" else "pullRequest"
        return session
            .execute(
                """
            mutation EditWorkBody(${'$'}id: ID!, ${'$'}body: String) {
                $operation(input: {$inputField: ${'$'}id, body: ${'$'}body}) {
                    $resultField { id body }
                }
            }
        """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("body", body)
                },
                write = true,
            )
            .graphObject(operation)
            .graphObject(resultField)
            .requireNode(id)
            .graphText("body") ?: throw GithubPageException()
    }

    suspend fun editTitle(id: String, title: String): String {
        val operation = if (kind == CollaborationKind.Issue) "updateIssue" else "updatePullRequest"
        val inputField = if (kind == CollaborationKind.Issue) "id" else "pullRequestId"
        val resultField = if (kind == CollaborationKind.Issue) "issue" else "pullRequest"
        return session
            .execute(
                """
            mutation EditWorkTitle(${'$'}id: ID!, ${'$'}title: String!) {
                $operation(input: {$inputField: ${'$'}id, title: ${'$'}title}) {
                    $resultField { id title }
                }
            }
        """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("title", title)
                },
                write = true,
            )
            .graphObject(operation)
            .graphObject(resultField)
            .requireNode(id)
            .graphRequiredText("title")
    }

    suspend fun closeIssue(id: String): String {
        return session
            .execute(
                """
                mutation CloseWorkIssue(${'$'}id: ID!) {
                    closeIssue(input: {issueId: ${'$'}id}) { issue { id state } }
                }
                """
                    .trimIndent(),
                buildJsonObject { put("id", id) },
                write = true,
            )
            .graphObject("closeIssue")
            .graphObject("issue")
            .requireNode(id)
            .graphRequiredText("state")
            .lowercase(Locale.ROOT)
    }

    suspend fun setLocked(id: String, locked: Boolean): Boolean {
        val operation = if (locked) "lockLockable" else "unlockLockable"
        val inputType = if (locked) "LockLockableInput" else "UnlockLockableInput"
        val record = if (locked) "lockedRecord" else "unlockedRecord"
        return session
            .execute(
                """
            mutation LockWork(${'$'}input: $inputType!) {
                $operation(input: ${'$'}input) { $record { ... on Node { id } locked } }
            }
        """
                    .trimIndent(),
                buildJsonObject { put("input", buildJsonObject { put("lockableId", id) }) },
                write = true,
            )
            .graphObject(operation)
            .graphObject(record)
            .requireNode(id)
            .flag("locked")
    }

    suspend fun closePullRequest(id: String): String {
        return session
            .execute(
                """
                mutation CloseWorkPullRequest(${'$'}id: ID!) {
                    closePullRequest(input: {pullRequestId: ${'$'}id}) { pullRequest { id state } }
                }
                """
                    .trimIndent(),
                buildJsonObject { put("id", id) },
                write = true,
            )
            .graphObject("closePullRequest")
            .graphObject("pullRequest")
            .requireNode(id)
            .graphRequiredText("state")
            .lowercase(Locale.ROOT)
    }

    private fun variables(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("owner", owner)
        put("name", repository)
        put("number", number)
        extra()
    }

    suspend fun detail(): CollaborationItemDto {
        val data = session.execute(DetailQuery, variables())
        val repo = data.graphObject("repository")
        val node = repo.graphObject("issueOrPullRequest")
        val type = node.graphRequiredText("__typename")
        if (type != if (kind == CollaborationKind.Issue) "Issue" else "PullRequest") {
            throw WorkDetailTypeMismatchException()
        }
        val isPull = type == "PullRequest"
        val labels = linkedMapOf<String, CollaborationLabelDto>()
        var connection = node.graphObject("labels")
        var after: String? = null
        val cursors = mutableSetOf<String>()
        while (true) {
            connection.graphNodes().forEach {
                val name = it.graphRequiredText("name")
                labels[name] = CollaborationLabelDto(name, it.graphRequiredText("color"))
            }
            val next = connection.graphCursor(after) ?: break
            if (!cursors.add(next) || cursors.size >= 1_000) throw GithubPageException()
            val current =
                session
                    .execute(
                        LabelsQuery,
                        buildJsonObject {
                            put("id", node.graphRequiredText("id"))
                            put("after", next)
                        },
                    )
                    .graphObject("node")
            if (current.graphRequiredText("updatedAt") != node.graphRequiredText("updatedAt")) {
                throw WorkDetailReviewChangedException()
            }
            after = next
            connection = current.graphObject("labels")
        }
        // 评审人与当前账号的最新评审直接复用详情响应。
        val reviewRequests = if (isPull) node["reviewRequests"] as? JsonObject else null
        val bodyHtml = node.graphText("bodyHTML")
        val reviewerEntries =
            if (isPull) {
                reviewRequests.connectionNodes().mapNotNull { request ->
                    val reviewer =
                        request["requestedReviewer"] as? JsonObject ?: return@mapNotNull null
                    val login = reviewer.graphText("login") ?: reviewer.graphText("name")
                    login?.let { it to reviewer.graphText("avatarUrl") }
                }
            } else {
                emptyList()
            }
        return CollaborationItemDto(
            number = node.requiredInt("number"),
            title = node.graphRequiredText("title"),
            state =
                (node.graphText("issueState") ?: node.graphRequiredText("pullRequestState"))
                    .lowercase(Locale.ROOT),
            body = node.graphText("body") ?: throw GithubPageException(),
            bodyHtml = bodyHtml,
            taskProgress = bodyHtml?.workTaskProgress(),
            nodeId = node.graphRequiredText("id"),
            htmlUrl = node.graphRequiredText("url"),
            updatedAt = node.graphText("updatedAt"),
            user = node.actor(),
            locked = node.flag("locked"),
            draft = isPull && node.flag("isDraft"),
            merged = isPull && node.flag("merged"),
            head =
                if (isPull)
                    PullRequestHeadDto(
                        node.graphRequiredText("headRefOid"),
                        node.graphText("headRefName"),
                    )
                else null,
            baseRefName = if (isPull) node.graphText("baseRefName") else null,
            baseRefOid = if (isPull) node.graphText("baseRefOid") else null,
            labels = labels.values.toList(),
            assignedActors =
                if (isPull) emptyList()
                else
                    (node["assignedActors"] as? JsonObject).connectionNodes().mapNotNull {
                        it.graphText("login")
                    },
            issueType = (node["issueType"] as? JsonObject)?.graphText("name"),
            issueTypeColor = (node["issueType"] as? JsonObject)?.graphText("color"),
            viewerCanTypeIssue = !isPull && node.flag("viewerCanType"),
            viewerCanSeeIssueFields = !isPull && repo.flag("viewerCanSeeIssueFields"),
            createdAt = node.graphText("createdAt"),
            viewerCanReact = node.flag("viewerCanReact"),
            reactions = node.reactionGroups(),
            mergedAt = if (isPull) node.graphText("mergedAt") else null,
            commitCount =
                if (isPull) node.graphObject("commits").requiredInt("totalCount") else null,
            reviewCount =
                if (isPull) (node["reviews"] as? JsonObject)?.requiredInt("totalCount") else null,
            changedFiles = if (isPull) node.requiredInt("changedFiles") else null,
            additions = if (isPull) node.requiredInt("additions") else null,
            deletions = if (isPull) node.requiredInt("deletions") else null,
            mergeable = if (isPull) node.graphRequiredText("mergeable") else null,
            viewerCanEdit = node.flag("viewerCanUpdate"),
            viewerCanClose = node.flag("viewerCanClose"),
            viewerCanModerate =
                repo.graphText("viewerPermission") in setOf("WRITE", "MAINTAIN", "ADMIN"),
            viewerCanChangeBase =
                isPull && repo.graphText("viewerPermission") in setOf("WRITE", "MAINTAIN", "ADMIN"),
            viewerCanMerge =
                isPull && repo.graphText("viewerPermission") in setOf("WRITE", "MAINTAIN", "ADMIN"),
            mergeCommitAllowed = isPull && repo.flag("mergeCommitAllowed"),
            squashMergeAllowed = isPull && repo.flag("squashMergeAllowed"),
            rebaseMergeAllowed = isPull && repo.flag("rebaseMergeAllowed"),
            reviewDecision = if (isPull) node.graphText("reviewDecision") else null,
            mergeStateStatus = if (isPull) node.graphText("mergeStateStatus") else null,
            reviewRequestCount = if (isPull) reviewRequests?.requiredInt("totalCount") else null,
            requestedReviewers = reviewerEntries.map { it.first },
            requestedReviewerAvatars = reviewerEntries.map { it.second },
            viewerLatestReviewState =
                if (isPull) (node["viewerLatestReview"] as? JsonObject)?.graphText("state")
                else null,
            viewerReviewRequested = isPull && node["viewerLatestReviewRequest"] is JsonObject,
            requiredStatusCheckCount =
                if (isPull) node.graphObject("requiredStatusChecks").requiredInt("totalCount")
                else null,
            allStatusChecksPassed =
                isPull &&
                    node.graphObject("requiredStatusChecks").requiredInt("totalCount") > 0 &&
                    node.graphObject("requiredStatusChecks").graphNodes().all {
                        it.graphRequiredText("state") == "SUCCESS"
                    },
            requiredStatusChecks =
                if (isPull)
                    node.graphObject("requiredStatusChecks").graphNodes().map {
                        PullRequestStatusCheckDto(
                            name = it.graphRequiredText("context"),
                            state = it.graphRequiredText("state"),
                            description = it.graphText("description"),
                        )
                    }
                else emptyList(),
        )
    }

    suspend fun timeline(
        direction: ThreadDirection,
        cursor: String?,
    ): ThreadPage<WorkTimelineEntry> {
        val query =
            if (kind == CollaborationKind.Issue) IssueTimelineQuery else PullRequestTimelineQuery
        val data =
            session.execute(
                query,
                variables {
                    if (direction == ThreadDirection.Newer) {
                        put("first", 30)
                        put("after", requireNotNull(cursor))
                    } else {
                        put("last", 30)
                        cursor?.let { put("before", it) }
                    }
                },
            )
        val field = if (kind == CollaborationKind.Issue) "issue" else "pullRequest"
        val workItem = data.graphObject("repository").graphObject(field)
        val connection = workItem.graphObject("timelineItems")
        val info = connection.graphObject("pageInfo")
        return ThreadPage(
            connection.graphNodes().mapNotNull(::timelineEntry),
            info.threadCursor(previous = true),
            info.threadCursor(previous = false),
        )
    }

    suspend fun toggleReaction(
        subjectId: String,
        content: GithubReactionContent,
        reacted: Boolean,
    ): List<GithubReactionGroup> = session.toggleGithubReaction(subjectId, content, reacted)

    suspend fun comments(after: String?): GithubGraphQlPage<CollaborationCommentDto> {
        val data =
            session.execute(
                CommentsQuery,
                variables { put("after", after?.let(::JsonPrimitive) ?: JsonNull) },
            )
        val connection =
            data.graphObject("repository").graphObject("issueOrPullRequest").graphObject("comments")
        return GithubGraphQlPage(
            connection.graphNodes().map(::comment),
            connection.graphCursor(after),
        )
    }

    suspend fun reviews(after: String?): GithubGraphQlPage<PullRequestReviewDto> {
        val data =
            session.execute(
                ReviewsQuery,
                variables { put("after", after?.let(::JsonPrimitive) ?: JsonNull) },
            )
        val connection =
            data.graphObject("repository").graphObject("pullRequest").graphObject("reviews")
        return GithubGraphQlPage(
            connection.graphNodes().map(::review),
            connection.graphCursor(after),
        )
    }

    suspend fun commits(head: String, after: String?): GithubGraphQlPage<RepositoryActivityEntry> {
        val data =
            session.execute(
                """
            query PullRequestCommits(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    pullRequest(number: ${'$'}number) {
                        headRefOid commits(first: 30, after: ${'$'}after) {
                            $PageInfo nodes { commit { $CommitFields } }
                        }
                    }
                }
            }
        """
                    .trimIndent(),
                variables { put("after", after?.let(::JsonPrimitive) ?: JsonNull) },
            )
        val request = data.graphObject("repository").graphObject("pullRequest")
        if (request.graphRequiredText("headRefOid") != head)
            throw WorkDetailReviewChangedException()
        val connection = request.graphObject("commits")
        return GithubGraphQlPage(
            connection.graphNodes().map { commitEntry(it.graphObject("commit")) },
            connection.graphCursor(after),
        )
    }

    suspend fun checks(head: String, after: String?): GithubGraphQlPage<PullRequestCheckRunDto> {
        val data =
            session.execute(
                ChecksQuery,
                buildJsonObject {
                    put("owner", owner)
                    put("name", repository)
                    put("number", number)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        val request = data.graphObject("repository").graphObject("pullRequest")
        if (request.graphRequiredText("headRefOid") != head)
            throw WorkDetailReviewChangedException()
        val commit =
            request.graphObject("commits").graphNodes().singleOrNull()?.graphObject("commit")
                ?: return GithubGraphQlPage(emptyList(), null)
        if (commit.graphRequiredText("oid") != head) throw WorkDetailReviewChangedException()
        val rollup = commit["statusCheckRollup"]
        if (rollup == null || rollup == JsonNull) return GithubGraphQlPage(emptyList(), null)
        val connection =
            (rollup as? JsonObject ?: throw GithubPageException()).graphObject("contexts")
        val checks =
            connection.graphNodes().mapNotNull { node ->
                val type = node.graphRequiredText("__typename")
                val isRequired = (node["isRequired"] as? JsonPrimitive)?.booleanOrNull ?: false
                when (type) {
                    "CheckRun" -> {
                        val suite = node.graphObject("checkSuite")
                        val run = suite["workflowRun"] as? JsonObject
                        PullRequestCheckRunDto(
                            id = node.graphRequiredText("id"),
                            name = node.graphRequiredText("name"),
                            status = node.graphRequiredText("status").lowercase(Locale.ROOT),
                            conclusion = node.graphText("conclusion")?.lowercase(Locale.ROOT),
                            required = isRequired,
                            suiteId = suite.graphRequiredText("id"),
                            appName = (suite["app"] as? JsonObject)?.graphText("name"),
                            workflowName = run?.graphObject("workflow")?.graphText("name"),
                            runNumber = run?.requiredInt("runNumber"),
                            runUrl = run?.graphRequiredText("url"),
                        )
                    }
                    "StatusContext" ->
                        PullRequestCheckRunDto(
                            id = node.graphRequiredText("id"),
                            name = node.graphRequiredText("context"),
                            status = node.graphRequiredText("state").lowercase(Locale.ROOT),
                            required = isRequired,
                            description = node.graphText("description"),
                        )
                    else -> null
                }
            }
        return GithubGraphQlPage(checks, connection.graphCursor(after))
    }

    suspend fun mergeMessage(id: String, method: String): WorkMergeMessage {
        val node =
            session
                .execute(
                    """
                    query MergeRequirements(${'$'}id: ID!, ${'$'}method: PullRequestMergeMethod!) {
                        node(id: ${'$'}id) {
                            ... on PullRequest {
                                id
                                mergeRequirements(
                                    mergeAction: DIRECT_MERGE
                                    mergeMethod: ${'$'}method
                                    bypassRequirements: false
                                ) {
                                    commitMessageHeadline
                                    commitMessageBody
                                }
                            }
                        }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("id", id)
                        put("method", method)
                    },
                )
                .graphObject("node")
        if (node.graphRequiredText("id") != id) throw GithubPageException()
        val requirements = node.graphObject("mergeRequirements")
        return WorkMergeMessage(
            requirements.graphRequiredText("commitMessageHeadline"),
            requirements.graphText("commitMessageBody").orEmpty(),
        )
    }

    suspend fun merge(id: String, head: String, method: String, message: WorkMergeMessage?) {
        val data =
            session.execute(
                """
                mutation MergeWork(${'$'}input: MergePullRequestInput!) {
                    mergePullRequest(input: ${'$'}input) {
                        pullRequest { id merged }
                    }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    putJsonObject("input") {
                        put("pullRequestId", id)
                        put("expectedHeadOid", head)
                        put("mergeMethod", method)
                        if (message != null) {
                            put("commitHeadline", message.headline)
                            put("commitBody", message.body)
                        }
                    }
                },
                write = true,
            )
        val merged = data.graphObject("mergePullRequest").graphObject("pullRequest")
        if (merged.graphRequiredText("id") != id || !merged.flag("merged"))
            throw GithubPageException()
    }

    suspend fun createComment(id: String, body: String): CollaborationCommentDto {
        val data =
            session.execute(
                """
            mutation WorkComment(${'$'}id: ID!, ${'$'}body: String!) {
                addComment(input: {subjectId: ${'$'}id, body: ${'$'}body}) {
                    commentEdge { node { $CommentFields } }
                }
            }
        """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("body", body)
                },
                write = true,
            )
        return comment(
            data.graphObject("addComment").graphObject("commentEdge").graphObject("node"),
        )
    }

    suspend fun updateComment(id: String, body: String): CollaborationCommentDto {
        val data =
            session.execute(
                """
            mutation EditWorkComment(${'$'}id: ID!, ${'$'}body: String!) {
                updateIssueComment(input: {id: ${'$'}id, body: ${'$'}body}) {
                    issueComment { $CommentFields }
                }
            }
        """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("body", body)
                },
                write = true,
            )
        return comment(data.graphObject("updateIssueComment").graphObject("issueComment"))
    }

    suspend fun readComment(id: String): CollaborationCommentDto {
        val data =
            session.execute(
                """
            query WorkCommentVersion(${'$'}id: ID!) {
                node(id: ${'$'}id) { ... on IssueComment { $CommentFields } }
            }
        """
                    .trimIndent(),
                buildJsonObject { put("id", id) },
            )
        return comment(data.graphObject("node"))
    }

    suspend fun deleteComment(id: String) {
        session
            .execute(
                """
                mutation DeleteWorkComment(${'$'}id: ID!) {
                    deleteIssueComment(input: {id: ${'$'}id}) { clientMutationId }
                }
                """
                    .trimIndent(),
                buildJsonObject { put("id", id) },
                write = true,
            )
            .graphObject("deleteIssueComment")
    }

    suspend fun minimizeComment(id: String, classifier: String) {
        require(
            classifier in setOf("ABUSE", "DUPLICATE", "OFF_TOPIC", "OUTDATED", "RESOLVED", "SPAM"),
        )
        val data =
            session.execute(
                """
                mutation HideWorkComment(${'$'}id: ID!, ${'$'}classifier: ReportedContentClassifiers!) {
                    minimizeComment(input: {subjectId: ${'$'}id, classifier: ${'$'}classifier}) {
                        minimizedComment { isMinimized }
                    }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("classifier", classifier)
                },
                write = true,
            )
        if (
            !data.graphObject("minimizeComment").graphObject("minimizedComment").flag("isMinimized")
        ) {
            throw GithubPageException()
        }
    }

    suspend fun createReview(
        id: String,
        commit: String,
        event: String,
        body: String?,
    ): PullRequestReviewDto {
        val data =
            session.execute(
                """
            mutation WorkReview(${'$'}id: ID!, ${'$'}commit: GitObjectID!, ${'$'}event: PullRequestReviewEvent!, ${'$'}body: String) {
                addPullRequestReview(input: {pullRequestId: ${'$'}id, commitOID: ${'$'}commit, event: ${'$'}event, body: ${'$'}body}) {
                    pullRequestReview { $ReviewFields }
                }
            }
        """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("commit", commit)
                    put("event", event)
                    put("body", body?.let(::JsonPrimitive) ?: JsonNull)
                },
                write = true,
            )
        return review(data.graphObject("addPullRequestReview").graphObject("pullRequestReview"))
    }

    suspend fun createLineComment(
        id: String,
        body: String,
        path: String,
        line: Int,
        side: String,
    ): PullRequestReviewCommentDto {
        val data =
            session.execute(
                """
                mutation WorkReviewThread(${'$'}id: ID!, ${'$'}body: String!, ${'$'}path: String!, ${'$'}line: Int!, ${'$'}side: DiffSide!) {
                    addPullRequestReviewThread(input: {pullRequestId: ${'$'}id, body: ${'$'}body, path: ${'$'}path, line: ${'$'}line, side: ${'$'}side}) {
                        thread { comments(last: 1) { nodes {
                            id databaseId body bodyHTML(unfurlReferences: true) path line url
                            commit { oid } author { login avatarUrl __typename }
                        } } }
                    }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("id", id)
                    put("body", body)
                    put("path", path)
                    put("line", line)
                    put("side", side)
                },
                write = true,
            )
        val node =
            data
                .graphObject("addPullRequestReviewThread")
                .graphObject("thread")
                .graphObject("comments")
                .graphNodes()
                .singleOrNull() ?: throw GithubPageException()
        return pullRequestReviewComment(node, side)
    }
}

internal fun pullRequestReviewComment(
    node: JsonObject,
    side: String,
): PullRequestReviewCommentDto =
    PullRequestReviewCommentDto(
        id = node.databaseId(),
        nodeId = node.graphRequiredText("id"),
        body = node.graphText("body"),
        bodyHtml = node.graphText("bodyHTML"),
        path = node.graphRequiredText("path"),
        line = node.requiredInt("line"),
        side = side,
        htmlUrl = node.graphText("url"),
        commitId = node.graphObject("commit").graphRequiredText("oid"),
        user = node.actor(),
    )

private fun JsonObject.databaseId(): Long =
    (get("databaseId") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        ?: throw GithubPageException()

private fun JsonObject.requireNode(id: String): JsonObject {
    if (graphRequiredText("id") != id) throw GithubPageException()
    return this
}

private fun JsonObject.requiredInt(key: String): Int =
    (get(key) as? JsonPrimitive)?.intOrNull ?: throw GithubPageException()

private fun JsonObject.flag(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: throw GithubPageException()

private fun JsonObject.actor(): UserDto? =
    (get("author") as? JsonObject)?.let {
        UserDto(
            login = it.graphRequiredText("login"),
            avatarUrl = it.graphText("avatarUrl"),
            type = it.graphText("__typename"),
        )
    }

/** 字段缺失时按空连接处理：详情主查询不该因为某一项可选连接不可见而整页失败。 */
private fun JsonObject?.connectionNodes(): List<JsonObject> =
    ((this?.get("nodes")) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun comment(node: JsonObject) =
    CollaborationCommentDto(
        id = node.databaseId(),
        nodeId = node.graphRequiredText("id"),
        body = node.graphText("body"),
        bodyHtml = node.graphText("bodyHTML"),
        createdAt = node.graphText("createdAt"),
        updatedAt = node.graphText("updatedAt"),
        user = node.actor(),
        url = node.graphText("url"),
        isMinimized = node.flag("isMinimized"),
        minimizedReason = node.graphText("minimizedReason"),
        viewerCanMinimize = node.flag("viewerCanMinimize"),
        viewerCanReact = node.flag("viewerCanReact"),
        reactions = node.reactionGroups(),
    )

private fun review(node: JsonObject) =
    PullRequestReviewDto(
        id = node.databaseId(),
        nodeId = node.graphRequiredText("id"),
        state = node.graphRequiredText("state"),
        body = node.graphText("body"),
        bodyHtml = node.graphText("bodyHTML"),
        submittedAt = node.graphText("submittedAt"),
        commitUrl = (node["commit"] as? JsonObject)?.graphText("url"),
        user = node.actor(),
    )

private fun commitEntry(node: JsonObject): RepositoryActivityEntry {
    val oid = node.graphRequiredText("oid")
    val author = node["author"] as? JsonObject
    return RepositoryActivityEntry(
        id = oid,
        title = node.graphRequiredText("message").lineSequence().first(),
        subtitle = oid.take(7),
        url = node.graphRequiredText("url"),
        login =
            (author?.get("user") as? JsonObject)?.graphText("login") ?: author?.graphText("name"),
        avatarUrl = author?.graphText("avatarUrl"),
        timestamp = node.graphRequiredText("committedDate"),
    )
}

private fun timelineEntry(node: JsonObject): WorkTimelineEntry? {
    val type = node.graphRequiredText("__typename")
    return when (type) {
        "IssueComment" -> WorkTimelineEntry.Comment(comment(node))
        "PullRequestReview" ->
            if (node.graphRequiredText("state") == "PENDING") null
            else WorkTimelineEntry.Review(review(node))
        "PullRequestCommit" -> WorkTimelineEntry.Commit(commitEntry(node.graphObject("commit")))
        else -> {
            val kind = node.timelineEventKind() ?: return null
            val actor = node["actor"] as? JsonObject ?: node["enqueuer"] as? JsonObject
            WorkTimelineEntry.Event(
                id = node.graphRequiredText("id"),
                kind = kind,
                actor = actor?.graphText("login"),
                avatarUrl = actor?.graphText("avatarUrl"),
                timestamp = node.graphText("createdAt"),
                subject = node.timelineEventSubject(kind),
            )
        }
    }
}

private fun JsonObject.timelineEventKind(): WorkTimelineEventKind? =
    when (graphRequiredText("__typename")) {
        "RenamedTitleEvent" -> WorkTimelineEventKind.RenamedTitle
        "ClosedEvent" -> WorkTimelineEventKind.Closed
        "ReopenedEvent" -> WorkTimelineEventKind.Reopened
        "MergedEvent" -> WorkTimelineEventKind.Merged
        "LabeledEvent" -> WorkTimelineEventKind.Labeled
        "UnlabeledEvent" -> WorkTimelineEventKind.Unlabeled
        "AssignedEvent" -> WorkTimelineEventKind.Assigned
        "UnassignedEvent" -> WorkTimelineEventKind.Unassigned
        "LockedEvent" -> WorkTimelineEventKind.Locked
        "UnlockedEvent" -> WorkTimelineEventKind.Unlocked
        "MilestonedEvent" -> WorkTimelineEventKind.Milestoned
        "DemilestonedEvent" -> WorkTimelineEventKind.Demilestoned
        "CrossReferencedEvent" -> WorkTimelineEventKind.CrossReferenced
        "ReferencedEvent" -> WorkTimelineEventKind.Referenced
        "HeadRefDeletedEvent" -> WorkTimelineEventKind.HeadRefDeleted
        "HeadRefRestoredEvent" -> WorkTimelineEventKind.HeadRefRestored
        "ReviewRequestedEvent" -> WorkTimelineEventKind.ReviewRequested
        "ReviewRequestRemovedEvent" -> WorkTimelineEventKind.ReviewRequestRemoved
        "ReviewDismissedEvent" -> WorkTimelineEventKind.ReviewDismissed
        "ReadyForReviewEvent" -> WorkTimelineEventKind.ReadyForReview
        "ConvertToDraftEvent" -> WorkTimelineEventKind.ConvertedToDraft
        "CommentDeletedEvent" -> WorkTimelineEventKind.CommentDeleted
        "DeployedEvent" -> WorkTimelineEventKind.Deployed
        "HeadRefForcePushedEvent" -> WorkTimelineEventKind.HeadRefForcePushed
        "BaseRefForcePushedEvent" -> WorkTimelineEventKind.BaseRefForcePushed
        "BaseRefChangedEvent" -> WorkTimelineEventKind.BaseRefChanged
        "MarkedAsDuplicateEvent" -> WorkTimelineEventKind.MarkedAsDuplicate
        "DeploymentEnvironmentChangedEvent" -> WorkTimelineEventKind.DeploymentEnvironmentChanged
        "AutoMergeEnabledEvent" -> WorkTimelineEventKind.AutoMergeEnabled
        "AutoMergeDisabledEvent" -> WorkTimelineEventKind.AutoMergeDisabled
        "AutoRebaseEnabledEvent" -> WorkTimelineEventKind.AutoRebaseEnabled
        "AutoSquashEnabledEvent" -> WorkTimelineEventKind.AutoSquashEnabled
        "UserBlockedEvent" -> WorkTimelineEventKind.UserBlocked
        "AddedToMergeQueueEvent" -> WorkTimelineEventKind.AddedToMergeQueue
        "RemovedFromMergeQueueEvent" -> WorkTimelineEventKind.RemovedFromMergeQueue
        "ConnectedEvent" -> WorkTimelineEventKind.Connected
        "DisconnectedEvent" -> WorkTimelineEventKind.Disconnected
        "AutomaticBaseChangeSucceededEvent" -> WorkTimelineEventKind.AutomaticBaseChanged
        "AddedToProjectEvent",
        "AddedToProjectV2Event" -> WorkTimelineEventKind.AddedToProject
        "MovedColumnsInProjectEvent",
        "ProjectV2ItemStatusChangedEvent" -> WorkTimelineEventKind.MovedColumnsInProject
        "RemovedFromProjectEvent",
        "RemovedFromProjectV2Event" -> WorkTimelineEventKind.RemovedFromProject
        "PinnedEvent" -> WorkTimelineEventKind.Pinned
        "UnpinnedEvent" -> WorkTimelineEventKind.Unpinned
        "TransferredEvent" -> WorkTimelineEventKind.Transferred
        "ConvertedToDiscussionEvent" -> WorkTimelineEventKind.ConvertedToDiscussion
        "SubIssueAddedEvent" -> WorkTimelineEventKind.SubIssueAdded
        "SubIssueRemovedEvent" -> WorkTimelineEventKind.SubIssueRemoved
        "CopilotWorkStartedEvent" -> WorkTimelineEventKind.CopilotWorkStarted
        "CopilotWorkFinishedEvent" -> WorkTimelineEventKind.CopilotWorkFinished
        "CopilotWorkFinishedFailureEvent" -> WorkTimelineEventKind.CopilotWorkFinishedFailure
        else -> null
    }

private fun JsonObject.timelineEventSubject(kind: WorkTimelineEventKind): String? =
    when (kind) {
        WorkTimelineEventKind.Locked -> graphText("lockReason")
        WorkTimelineEventKind.CommentDeleted ->
            (get("deletedCommentAuthor") as? JsonObject)?.graphText("login")
        WorkTimelineEventKind.UserBlocked -> (get("userSubject") as? JsonObject)?.graphText("login")
        else -> null
    }

private const val CommentFields =
    "id databaseId body bodyHTML(unfurlReferences: true) createdAt updatedAt url isMinimized minimizedReason viewerCanMinimize viewerCanReact $ReactionFragment author { login avatarUrl __typename }"
private const val ReviewFields =
    "id databaseId state body bodyHTML(unfurlReferences: true) " +
        "submittedAt commit { url } author { login avatarUrl __typename }"
private const val PageInfo = "pageInfo { hasNextPage endCursor }"
private const val CommitFields =
    "oid message url committedDate author { name avatarUrl user { login } }"
private const val EventFields = "id createdAt actor { login avatarUrl }"
private const val CommonTimelineTypes =
    """
    ISSUE_COMMENT LOCKED_EVENT UNLOCKED_EVENT COMMENT_DELETED_EVENT USER_BLOCKED_EVENT
"""
private const val CommonTimelineFields =
    """
    __typename
    ... on IssueComment { $CommentFields }
    ... on LockedEvent { $EventFields lockReason }
    ... on UnlockedEvent { $EventFields }
    ... on CommentDeletedEvent {
        $EventFields deletedCommentAuthor { ... on Actor { login avatarUrl } }
    }
    ... on UserBlockedEvent {
        $EventFields userSubject: subject { ... on Actor { login avatarUrl } }
        blockDuration
    }
    """
private const val TimelineVariables = "\$before: String, \$last: Int, \$after: String, \$first: Int"
private const val TimelineArguments =
    "before: \$before, last: \$last, after: \$after, first: \$first"
private const val TimelinePageInfo =
    "pageInfo { hasPreviousPage startCursor hasNextPage endCursor }"
private val IssueTimelineQuery =
    """
    query IssueTimeline(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, $TimelineVariables) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            issue(number: ${'$'}number) {
                id
                timelineItems($TimelineArguments, itemTypes: [$CommonTimelineTypes]) {
                    $TimelinePageInfo nodes { $CommonTimelineFields }
                }
            }
        }
    }
"""
        .trimIndent()
private val PullRequestTimelineQuery =
    """
    query PullRequestTimeline(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, $TimelineVariables) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            pullRequest(number: ${'$'}number) {
                id
                timelineItems(
                    $TimelineArguments,
                    itemTypes: [$CommonTimelineTypes PULL_REQUEST_REVIEW PULL_REQUEST_COMMIT]
                ) {
                    $TimelinePageInfo nodes {
                        $CommonTimelineFields
                        ... on PullRequestReview { $ReviewFields }
                        ... on PullRequestCommit { commit { $CommitFields } }
                    }
                }
            }
        }
    }
"""
        .trimIndent()

private const val WorkFields =
    """
    id number title body bodyHTML(unfurlReferences: true) url createdAt updatedAt locked viewerCanUpdate viewerCanClose viewerCanReact $ReactionFragment
    author { login avatarUrl __typename }
    labels(first: 100) { pageInfo { hasNextPage endCursor } nodes { name color } }
"""
private val LabelsQuery =
    """
    query WorkLabels(${'$'}id: ID!, ${'$'}after: String) {
        node(id: ${'$'}id) {
            ... on Issue { updatedAt labels(first: 100, after: ${'$'}after) { $PageInfo nodes { name color } } }
            ... on PullRequest { updatedAt labels(first: 100, after: ${'$'}after) { $PageInfo nodes { name color } } }
        }
    }
"""
        .trimIndent()
private val DetailQuery =
    """
    query WorkDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            viewerPermission mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed
            viewerCanSeeIssueFields
            issueOrPullRequest(number: ${'$'}number) {
                __typename
                ... on Issue {
                    $WorkFields issueState: state viewerCanType
                    issueType { name color }
                    assignedActors(first: 10) { nodes { ... on Actor { login } } }
                }
                ... on PullRequest {
                    $WorkFields pullRequestState: state isDraft merged mergedAt
                    headRefOid headRefName baseRefOid baseRefName
                    commits { totalCount }
                    reviews(states: [APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED]) { totalCount }
                    changedFiles additions deletions mergeable reviewDecision mergeStateStatus
                    reviewRequests(first: 25) {
                        totalCount
                        nodes {
                            requestedReviewer {
                                ... on User { login avatarUrl }
                                ... on Team { name }
                                ... on EnterpriseTeam { name }
                            }
                        }
                    }
                    viewerLatestReview { state }
                    viewerLatestReviewRequest { id }
                    requiredStatusChecks(first: 25) { totalCount nodes { context state description } }
                }
            }
        }
    }
"""
        .trimIndent()
private val CommentsQuery =
    """
    query WorkComments(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            issueOrPullRequest(number: ${'$'}number) {
                ... on Issue { comments(first: 30, after: ${'$'}after) { $PageInfo nodes { $CommentFields } } }
                ... on PullRequest { comments(first: 30, after: ${'$'}after) { $PageInfo nodes { $CommentFields } } }
            }
        }
    }
"""
        .trimIndent()
private val ReviewsQuery =
    """
    query WorkReviews(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            pullRequest(number: ${'$'}number) {
                reviews(first: 30, after: ${'$'}after) { $PageInfo nodes { $ReviewFields } }
            }
        }
    }
"""
        .trimIndent()
private val ChecksQuery =
    """
    query WorkChecks(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            pullRequest(number: ${'$'}number) {
                headRefOid
                commits(last: 1) { nodes { commit { oid statusCheckRollup {
                    contexts(first: 30, after: ${'$'}after) {
                        $PageInfo nodes {
                            __typename
                            ... on CheckRun {
                                id name status conclusion isRequired(pullRequestNumber: ${'$'}number)
                                checkSuite { id app { name } workflowRun { url runNumber workflow { name } } }
                            }
                            ... on StatusContext { id context state description isRequired(pullRequestNumber: ${'$'}number) }
                        }
                    }
                } } } }
            }
        }
    }
"""
        .trimIndent()
