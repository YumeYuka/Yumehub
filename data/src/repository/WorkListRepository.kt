package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationReviewFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationSortOrder
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTemplateOptionDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTypeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.MilestoneOptionDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.api.dto.WorkCreationDraft
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.RemoteFetch
import moe.yumeyuka.yumehub.data.cache.workListCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

data class WorkCardContent(
    val initial: CollaborationItemDto,
    val updates: Flow<CollaborationItemDto>,
)

class WorkListRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val cache: CachedDataRepository,
) {
    private val filterOptionsCache = FilterOptionsCache()

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    suspend fun create(
        account: String,
        owner: String,
        repository: String,
        kind: CollaborationKind,
        draft: WorkCreationDraft,
    ): Int =
        if (kind == CollaborationKind.Issue)
            createIssue(
                account,
                owner,
                repository,
                draft.title.trim(),
                draft.body,
                draft.issueTypeId,
                draft.milestoneId,
                draft.labelIds,
                draft.assignees.map { it.id },
                draft.issueTemplate,
            )
        else
            createPullRequest(
                account,
                owner,
                repository,
                draft.base.trim(),
                draft.head.trim(),
                draft.title.trim(),
                draft.body,
            )

    private suspend fun createIssue(
        account: String,
        owner: String,
        repository: String,
        title: String,
        body: String,
        issueTypeId: String? = null,
        milestoneId: String? = null,
        labelIds: List<String> = emptyList(),
        assigneeIds: List<String> = emptyList(),
        issueTemplate: String? = null,
    ): Int {
        require(title.isNotBlank())
        val session = GithubGraphQlSession(api, tokens, account)
        val repositoryId = repositoryId(session, owner, repository)
        return session
            .execute(
                """
                mutation CreateIssue(${'$'}createIssueInput: CreateIssueInput!) {
                    createIssue(input: ${'$'}createIssueInput) { issue { number } }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put(
                        "createIssueInput",
                        buildJsonObject {
                            put("repositoryId", repositoryId)
                            put("title", title)
                            put("body", body)
                            issueTemplate?.takeIf(String::isNotBlank)?.let {
                                put("issueTemplate", it)
                            }
                            issueTypeId?.takeIf(String::isNotBlank)?.let { put("issueTypeId", it) }
                            milestoneId?.takeIf(String::isNotBlank)?.let { put("milestoneId", it) }
                            if (labelIds.isNotEmpty()) {
                                put("labelIds", buildJsonArray { labelIds.forEach(::add) })
                            }
                            if (assigneeIds.isNotEmpty()) {
                                put("assigneeIds", buildJsonArray { assigneeIds.forEach(::add) })
                            }
                        },
                    )
                },
                write = true,
            )
            .graphObject("createIssue")
            .graphObject("issue")
            .get("number")
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?.also { filterOptionsCache.invalidate(owner, repository) }
            ?: throw GithubPageException()
    }

    suspend fun searchAssignableUsers(
        account: String,
        owner: String,
        repository: String,
        query: String,
        after: String? = null,
    ): GithubGraphQlPage<AssigneeOptionDto> {
        val session = GithubGraphQlSession(api, tokens, account)
        val data =
            session.execute(
                AssignableUsersQuery,
                buildJsonObject {
                    put("owner", owner)
                    put("repo", repository)
                    put("query", query)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        val connection = data.graphObject("repository").graphObject("assignableUsers")
        val users =
            connection.safeNodes().mapNotNull { node ->
                val id = node.graphText("id") ?: return@mapNotNull null
                val login = node.graphText("login") ?: return@mapNotNull null
                AssigneeOptionDto(id, login, node.graphText("name"))
            }
        return GithubGraphQlPage(users, connection.graphCursor(after)).also {
            session.ensureCurrent()
        }
    }

    private suspend fun createPullRequest(
        account: String,
        owner: String,
        repository: String,
        base: String,
        head: String,
        title: String,
        body: String,
    ): Int {
        require(title.isNotBlank() && base.isNotBlank() && head.isNotBlank() && base != head)
        val session = GithubGraphQlSession(api, tokens, account)
        val repositoryId = repositoryId(session, owner, repository)
        return session
            .execute(
                """
                mutation CreatePullRequest(
                    ${'$'}repositoryId: ID!, ${'$'}baseRefName: String!, ${'$'}headRefName: String!,
                    ${'$'}title: String!, ${'$'}body: String
                ) {
                    createPullRequest(input: {
                        repositoryId: ${'$'}repositoryId
                        baseRefName: ${'$'}baseRefName
                        headRefName: ${'$'}headRefName
                        title: ${'$'}title
                        body: ${'$'}body
                    }) { pullRequest { number } }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("repositoryId", repositoryId)
                    put("baseRefName", base)
                    put("headRefName", head)
                    put("title", title)
                    put("body", body)
                },
                write = true,
            )
            .graphObject("createPullRequest")
            .graphObject("pullRequest")
            .get("number")
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?.also { filterOptionsCache.invalidate(owner, repository) }
            ?: throw GithubPageException()
    }

    private suspend fun repositoryId(
        session: GithubGraphQlSession,
        owner: String,
        repository: String,
    ): String =
        session
            .execute(
                """
                query WorkCreationRepository(${'$'}owner: String!, ${'$'}name: String!) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) { id }
                }
                """
                    .trimIndent(),
                buildJsonObject {
                    put("owner", owner)
                    put("name", repository)
                },
            )
            .graphObject("repository")
            .graphRequiredText("id")

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun personalItemsKey(
        account: String,
        kind: CollaborationKind,
        filter: CollaborationListFilter,
    ): String =
        when {
            kind == CollaborationKind.Issue &&
                filter ==
                    CollaborationListFilter(
                        state = CollaborationStateFilter.All,
                        scope = CollaborationScopeFilter.AssignedToMe,
                    ) -> "work-viewer-issues-graphql.v1:assignee"
            kind == CollaborationKind.Issue &&
                filter ==
                    CollaborationListFilter(
                        state = CollaborationStateFilter.All,
                        scope = CollaborationScopeFilter.CreatedByMe,
                    ) -> "work-viewer-issues-graphql.v1:createdBy"
            else ->
                "work-search-graphql.v1:${workSearchQuery(account, kind, filter, repository = null)}"
        }

    fun repositoryItemsKey(
        owner: String,
        name: String,
        kind: CollaborationKind,
        filter: CollaborationListFilter,
    ): String =
        "repository-work.v3:${owner.lowercase(Locale.ROOT)}/${name.lowercase(Locale.ROOT)}:${kind.name}:$filter:size=30"

    fun personalItems(
        account: String,
        kind: CollaborationKind,
        filter: CollaborationListFilter,
    ) =
        when {
            kind == CollaborationKind.Issue &&
                filter ==
                    CollaborationListFilter(
                        state = CollaborationStateFilter.All,
                        scope = CollaborationScopeFilter.AssignedToMe,
                    ) -> viewerIssues(account, "assignee")
            kind == CollaborationKind.Issue &&
                filter ==
                    CollaborationListFilter(
                        state = CollaborationStateFilter.All,
                        scope = CollaborationScopeFilter.CreatedByMe,
                    ) -> viewerIssues(account, "createdBy")
            else ->
                personalSearch(
                    account,
                    workSearchQuery(account, kind, filter, repository = null),
                    filter.state,
                )
        }

    /** 未叠加筛选的个人议题可直接读取 viewer 连接。 */
    private fun viewerIssues(
        account: String,
        filterField: String,
    ) =
        githubGraphQlPager(
            account,
            "work-viewer-issues-graphql.v1:$filterField",
            paging,
            identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
            serializer = CollaborationItemDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Work, null)),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val query =
                """
                query WorkViewerIssues(${'$'}login: String!, ${'$'}after: String) {
                    viewer {
                        issues(first: 30, after: ${'$'}after, states: [OPEN, CLOSED],
                            orderBy: {field: UPDATED_AT, direction: DESC},
                            filterBy: {$filterField: ${'$'}login}) {
                            pageInfo { hasNextPage endCursor }
                            nodes { $IssueRowFields }
                        }
                    }
                }
            """
                    .trimIndent()
            val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
                val data =
                    session.execute(
                        query,
                        buildJsonObject {
                            put("login", account)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                val connection = data.graphObject("viewer").graphObject("issues")
                val items =
                    connection.graphNodes().map { node ->
                        node.toCollaborationItem(CollaborationKind.Issue)
                            ?: throw GithubPageException()
                    }
                GithubGraphQlPage(
                    items,
                    connection.graphCursor(after),
                )
            }
            load
        }

    private fun personalSearch(
        account: String,
        query: String,
        state: CollaborationStateFilter,
    ) =
        githubGraphQlPager(
            account,
            "work-search-graphql.v1:$query",
            paging,
            identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
            serializer = CollaborationItemDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Work, null)),
            mapItem = { item ->
                val merged = item.workTarget()?.let { cache.changes(account).isMerged(it) } == true
                when {
                    !merged -> item
                    state == CollaborationStateFilter.Open -> null
                    else -> item.copy(merged = true, state = "closed")
                }
            },
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
                val variables = buildJsonObject {
                    put("query", query)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                }
                val data = session.execute(WorkSearchQuery, variables)
                val search = data.graphObject("search")
                val items = search.safeNodes().mapNotNull { it.toCollaborationItem() }
                GithubGraphQlPage(items, search.graphCursor(after))
            }
            load
        }

    /**
     * Simple repository lists use the ordered repository connection; search owns complex filters.
     */
    fun repositoryItems(
        account: String,
        owner: String,
        name: String,
        kind: CollaborationKind,
        filter: CollaborationListFilter,
    ) =
        githubGraphQlPager(
            account,
            repositoryItemsKey(owner, name, kind, filter),
            paging,
            identity = { it: CollaborationItemDto -> requireNotNull(it.nodeId) },
            serializer = CollaborationItemDto.serializer(),
            tags = setOf(workListCacheTag(owner, name)),
            mapItem = { item ->
                val merged =
                    cache.changes(account).isMerged(WorkTarget(owner, name, item.number, kind))
                when {
                    !merged -> item
                    filter.state == CollaborationStateFilter.Open -> null
                    else -> item.copy(merged = true, state = "closed")
                }
            },
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val direct =
                filter.sort != CollaborationSortOrder.Comments &&
                    filter == CollaborationListFilter(state = filter.state, sort = filter.sort)
            val query =
                if (direct) null
                else workSearchQuery(account, kind, filter, repository = "$owner/$name")
            val pull = kind == CollaborationKind.PullRequest
            val field = if (pull) "pullRequests" else "issues"
            val order =
                if (filter.sort == CollaborationSortOrder.Created) "CREATED_AT" else "UPDATED_AT"
            val states =
                when (filter.state) {
                    CollaborationStateFilter.All ->
                        if (pull) "[OPEN,CLOSED,MERGED]" else "[OPEN,CLOSED]"
                    CollaborationStateFilter.Open -> "[OPEN]"
                    CollaborationStateFilter.Closed -> if (pull) "[CLOSED,MERGED]" else "[CLOSED]"
                }
            val connectionQuery =
                if (direct)
                    """query WorkRows(${'$'}owner:String!, ${'$'}name:String!, ${'$'}after:String) {
            repository(owner:${'$'}owner,name:${'$'}name) { $field(first:30,after:${'$'}after,states:$states,
            orderBy:{field:$order,direction:DESC}) { pageInfo { hasNextPage endCursor }
            nodes { ${if (pull) PullRowFields else IssueRowFields} } } } }"""
                else WorkSearchQuery
            val load: suspend (String?) -> GithubGraphQlPage<CollaborationItemDto> = { after ->
                val variables = buildJsonObject {
                    if (direct) {
                        put("owner", owner)
                        put("name", name)
                    } else put("query", query)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                }
                val data = session.execute(connectionQuery, variables)
                val search =
                    if (direct) data.graphObject("repository").graphObject(field)
                    else data.graphObject("search")
                val items =
                    search.graphNodes().map {
                        it.toCollaborationItem(kind) ?: throw GithubPageException()
                    }
                GithubGraphQlPage(items, search.graphCursor(after))
            }
            load
        }

    fun card(account: String, row: CollaborationItemDto): WorkCardContent {
        val id = row.nodeId ?: return WorkCardContent(row, kotlinx.coroutines.flow.emptyFlow())
        val tag = CacheTag(DataDomain.Work, row.repositoryName?.lowercase())
        val resource =
            cache.resource(
                account,
                "work-card.v1:$id:${row.updatedAt}",
                CollaborationItemDto.serializer(),
                tags = setOf(tag),
            )
        fun enrich(rich: CollaborationItemDto?): CollaborationItemDto? =
            rich
                ?.takeIf { it.updatedAt == row.updatedAt }
                ?.let {
                    row.copy(
                        bodyPreview = it.bodyPreview,
                        participantsCount = it.participantsCount,
                        participantAvatars = it.participantAvatars,
                        subIssuesTotal = it.subIssuesTotal,
                        subIssuesCompleted = it.subIssuesCompleted,
                    )
                }
        val initial = enrich(resource.cached) ?: row
        val updates =
            resource
                .observe {
                    val batch =
                        cache.sessionResource(account, "work-card-batch") { scope ->
                            WorkCardBatch(scope, GithubGraphQlSession(api, tokens, account))
                        }
                    RemoteFetch.Fresh(batch.read(id))
                }
                .mapNotNull { enrich(it.data) }
        return WorkCardContent(initial, updates)
    }

    /** 筛选菜单按账号与仓库短时缓存；读取失败由调用方保留空选项。 */
    suspend fun filterOptions(
        account: String,
        owner: String,
        name: String,
    ): CollaborationFilterOptions {
        val session = GithubGraphQlSession(api, tokens, account)
        return filterOptionsCache
            .get(account, owner, name, tokens.sessionGeneration.value) {
                val data =
                    session.execute(
                        FilterOptionsQuery,
                        buildJsonObject {
                            put("owner", owner)
                            put("name", name)
                        },
                    )
                val repository = data.graphObject("repository")
                val labels =
                    (repository["labels"] as? JsonObject)
                        ?.safeNodes()
                        ?.mapNotNull { node ->
                            val id = node.graphText("id") ?: return@mapNotNull null
                            val name = node.graphText("name") ?: return@mapNotNull null
                            CollaborationLabelDto(name, node.graphText("color"), id)
                        }
                        .orEmpty()
                val milestones =
                    (repository["milestones"] as? JsonObject)
                        ?.safeNodes()
                        ?.mapNotNull { node ->
                            val id = node.graphText("id") ?: return@mapNotNull null
                            val title = node.graphText("title") ?: return@mapNotNull null
                            MilestoneOptionDto(id, title)
                        }
                        .orEmpty()
                val issueTypes =
                    (repository["issueTypes"] as? JsonObject)
                        ?.safeNodes()
                        ?.mapNotNull { node ->
                            val id = node.graphText("id") ?: return@mapNotNull null
                            val name = node.graphText("name") ?: return@mapNotNull null
                            IssueTypeOptionDto(id, name, node.graphText("color"))
                        }
                        .orEmpty()
                val issueTemplates =
                    (repository["issueTemplates"] as? JsonArray)
                        ?.mapNotNull { value ->
                            val node = value as? JsonObject ?: return@mapNotNull null
                            val templateName = node.graphText("name") ?: return@mapNotNull null
                            val labels =
                                (node["labels"] as? JsonObject)
                                    ?.safeNodes()
                                    ?.mapNotNull { label ->
                                        val id = label.graphText("id") ?: return@mapNotNull null
                                        val labelName =
                                            label.graphText("name") ?: return@mapNotNull null
                                        CollaborationLabelDto(
                                            labelName,
                                            label.graphText("color"),
                                            id,
                                        )
                                    }
                                    .orEmpty()
                            val assignees =
                                (node["assignees"] as? JsonObject)
                                    ?.safeNodes()
                                    ?.mapNotNull { user ->
                                        val id = user.graphText("id") ?: return@mapNotNull null
                                        val login =
                                            user.graphText("login") ?: return@mapNotNull null
                                        AssigneeOptionDto(id, login, user.graphText("name"))
                                    }
                                    .orEmpty()
                            IssueTemplateOptionDto(
                                name = templateName,
                                title = node.graphText("title"),
                                body = node.graphText("body"),
                                labels = labels,
                                assignees = assignees,
                                issueTypeId = (node["type"] as? JsonObject)?.graphText("id"),
                            )
                        }
                        .orEmpty()
                CollaborationFilterOptions(
                    labels = labels,
                    milestones = milestones.map(MilestoneOptionDto::title),
                    issueTypes = issueTypes.map { CollaborationLabelDto(it.name, it.color) },
                    issueTypeOptions = issueTypes,
                    milestoneOptions = milestones,
                    issueTemplates = issueTemplates,
                )
            }
            .also { session.ensureCurrent() }
    }
}

private fun workSearchQuery(
    account: String,
    kind: CollaborationKind,
    filter: CollaborationListFilter,
    repository: String?,
): String = buildList {
    repository?.let { add("repo:$it") }
    add(if (kind == CollaborationKind.Issue) "is:issue" else "is:pr")
    filter.searchQuery.trim().takeIf(String::isNotEmpty)?.let {
        add("in:title,body ${workSearchQuote(it)}")
    }
    when (filter.state) {
        CollaborationStateFilter.Open -> add("is:open")
        CollaborationStateFilter.Closed -> add("is:closed")
        CollaborationStateFilter.All -> Unit
    }
    when (filter.scope) {
        CollaborationScopeFilter.All -> if (repository == null) add("involves:$account")
        CollaborationScopeFilter.CreatedByMe -> add("author:$account")
        CollaborationScopeFilter.AssignedToMe -> add("assignee:$account")
        CollaborationScopeFilter.ReviewRequested -> add("review-requested:$account")
        CollaborationScopeFilter.Commented -> add("commenter:$account")
        CollaborationScopeFilter.Mentioned -> add("mentions:$account")
        CollaborationScopeFilter.Involved -> add("involves:$account")
    }
    if (kind == CollaborationKind.PullRequest && filter.draftOnly) add("is:draft")
    filter.label?.let { add("label:${workSearchQuote(it)}") }
    if (filter.unlabeledOnly) add("no:label")
    if (filter.unassignedOnly) add("no:assignee")
    filter.milestone?.let { add("milestone:${workSearchQuote(it)}") }
    if (kind == CollaborationKind.Issue) {
        filter.issueType?.let { add("type:${workSearchQuote(it)}") }
    }
    if (kind == CollaborationKind.PullRequest) {
        when (filter.review) {
            CollaborationReviewFilter.Any -> Unit
            CollaborationReviewFilter.NoReviews -> add("review:none")
            CollaborationReviewFilter.Required -> add("review:required")
            CollaborationReviewFilter.Approved -> add("review:approved")
            CollaborationReviewFilter.ChangesRequested -> add("review:changes-requested")
            CollaborationReviewFilter.ReviewedByMe -> add("reviewed-by:@me")
            CollaborationReviewFilter.NotReviewedByMe -> add("-reviewed-by:@me")
            CollaborationReviewFilter.AwaitingMyTeamReview -> add("review-requested:@me")
            CollaborationReviewFilter.AwaitingMyReview -> add("user-review-requested:@me")
        }
    }
    add(
        when (filter.sort) {
            CollaborationSortOrder.Updated -> "sort:updated-desc"
            CollaborationSortOrder.Created -> "sort:created-desc"
            CollaborationSortOrder.Comments -> "sort:comments-desc"
        },
    )
}
    .joinToString(" ")

private fun workSearchQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun JsonObject.safeNodes(): List<JsonObject> =
    (get("nodes") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.count(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.connectionCount(key: String): Int? =
    (get(key) as? JsonObject)?.count("totalCount")

private fun JsonObject.author(): UserDto? =
    (get("author") as? JsonObject)?.let {
        UserDto(
            login = it.graphText("login"),
            avatarUrl = it.graphText("avatarUrl"),
            type = it.graphText("__typename"),
        )
    }

private fun JsonObject.labels(): List<CollaborationLabelDto> =
    (get("labels") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { node ->
            node.graphText("name")?.let { name ->
                CollaborationLabelDto(name = name, color = node.graphText("color"))
            }
        }
        .orEmpty()

private fun JsonObject.assignedActors(): List<String> =
    (get("assignedActors") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { it.graphText("login") }
        .orEmpty()

internal fun JsonObject.workCardParticipants(): List<UserDto> =
    (get("participants") as? JsonObject)
        ?.safeNodes()
        ?.mapNotNull { actor ->
            val login = actor.graphText("login") ?: return@mapNotNull null
            UserDto(login = login, avatarUrl = actor.graphText("avatarUrl"))
        }
        ?.take(3)
        .orEmpty()

internal fun JsonObject.workCardBodyPreview(): String? = graphText("bodyText")?.trim()?.take(480)

internal const val WorkCardCheckFields =
    "commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }"

internal fun JsonObject.workCardCheckState(): String? =
    (get("commits") as? JsonObject)
        ?.safeNodes()
        ?.firstOrNull()
        ?.let { it["commit"] as? JsonObject }
        ?.let { it["statusCheckRollup"] as? JsonObject }
        ?.graphText("state")

private fun JsonObject.toCollaborationItem(
    expected: CollaborationKind? = null,
): CollaborationItemDto? {
    val type = graphRequiredText("__typename")
    val pull = type == "PullRequest"
    if (type != "Issue" && !pull) return null
    if (expected != null && ((expected == CollaborationKind.Issue) == pull)) return null
    val state =
        (graphText("issueState") ?: graphText("pullRequestState") ?: graphText("state"))?.lowercase(
            Locale.ROOT,
        ) ?: return null
    val issueType = get("issueType") as? JsonObject
    val subIssues = get("subIssuesSummary") as? JsonObject
    return CollaborationItemDto(
        number = count("number").takeIf { it > 0 } ?: return null,
        title = graphText("title") ?: return null,
        state = state,
        nodeId = graphText("id") ?: return null,
        htmlUrl = graphText("url"),
        createdAt = graphText("createdAt"),
        updatedAt = graphText("updatedAt"),
        user = author(),
        bodyPreview = workCardBodyPreview(),
        labels = labels(),
        commentsCount = if (pull) count("totalCommentsCount") else connectionCount("comments") ?: 0,
        participantsCount = connectionCount("participants"),
        participantAvatars = workCardParticipants(),
        repositoryName = (get("repository") as? JsonObject)?.graphText("nameWithOwner"),
        assignedActors = assignedActors(),
        stateReason = graphText("stateReason"),
        issueType = issueType?.graphText("name"),
        issueTypeColor = issueType?.graphText("color"),
        subIssuesTotal = subIssues?.count("total"),
        subIssuesCompleted = subIssues?.count("completed"),
        linkedPullRequests = connectionCount("closedByPullRequestsReferences"),
        closingIssues = connectionCount("closingIssuesReferences"),
        headRefName = if (pull) graphText("headRefName") else null,
        baseRefName = if (pull) graphText("baseRefName") else null,
        checkState = workCardCheckState(),
        additions = get("additions")?.jsonPrimitive?.intOrNull,
        deletions = get("deletions")?.jsonPrimitive?.intOrNull,
        draft = pull && (get("isDraft")?.jsonPrimitive?.booleanOrNull ?: false),
        merged = pull && (get("merged")?.jsonPrimitive?.booleanOrNull ?: false),
        reviewDecision = if (pull) graphText("reviewDecision") else null,
    )
}

// Keep the paged response lightweight. Card summaries and statistics are fetched by visible node
// ID.
private const val CommonRowFields =
    "__typename id number title url createdAt updatedAt author { login avatarUrl __typename } repository { nameWithOwner }"
private const val IssueRowFields =
    "$CommonRowFields issueState: state stateReason comments { totalCount }"
private const val PullRowFields =
    "$CommonRowFields pullRequestState: state isDraft merged headRefName baseRefName totalCommentsCount"

private class WorkCardBatch(scope: CoroutineScope, private val session: GithubGraphQlSession) {
    private class Request(val id: String, val result: CompletableDeferred<CollaborationItemDto>)

    private val requests = Channel<Request>(64)

    init {
        scope
            .launch {
                for (first in requests) {
                    val batch = arrayListOf(first)
                    delay(16)
                    while (batch.size < 30) batch.add(requests.tryReceive().getOrNull() ?: break)
                    batch.removeAll { !it.result.isActive }
                    if (batch.isEmpty()) continue
                    try {
                        val nodes =
                            session
                                .execute(
                                    WorkCardDetailsQuery,
                                    buildJsonObject {
                                        putJsonArray("ids") { batch.forEach { add(it.id) } }
                                    },
                                )["nodes"]
                                as? JsonArray ?: throw GithubPageException()
                        val byId =
                            nodes
                                .mapNotNull { (it as? JsonObject)?.toCollaborationItem() }
                                .associateBy { it.nodeId }
                        batch.forEach { req ->
                            val row = byId[req.id]
                            if (row != null) req.result.complete(row)
                            else req.result.completeExceptionally(GithubPageException())
                        }
                    } catch (error: Exception) {
                        batch.forEach { it.result.completeExceptionally(error) }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            .invokeOnCompletion { requests.cancel() }
    }

    suspend fun read(id: String): CollaborationItemDto {
        val reply = CompletableDeferred<CollaborationItemDto>()
        try {
            requests.send(Request(id, reply))
            return reply.await()
        } finally {
            reply.cancel()
        }
    }
}

/** `assignedActors` 的节点是 Assignee union，字段必须放进 Actor 片段内。 */
private const val WorkIssueListItemFields =
    """
    __typename
    id number title bodyText issueState: state url createdAt updatedAt
    author { login avatarUrl __typename }
    repository { nameWithOwner }
    labels(first: 6) { nodes { name color } }
    comments { totalCount }
    participants(first: 3) { totalCount nodes { login avatarUrl } }
    assignedActors(first: 10) {
        nodes { __typename ... on Actor { login avatarUrl } }
    }
    issueType { name color }
    subIssuesSummary { total completed }
    stateReason
    closedByPullRequestsReferences { totalCount }
"""

private const val WorkPullListItemFields =
    """
    __typename
    id number title bodyText pullRequestState: state url createdAt updatedAt
    isDraft merged reviewDecision headRefName baseRefName totalCommentsCount additions deletions
    author { login avatarUrl __typename }
    repository { nameWithOwner }
    labels(first: 6) { nodes { name color } }
    participants(first: 3) { totalCount nodes { login avatarUrl } }
    assignedActors(first: 10) {
        nodes { __typename ... on Actor { login avatarUrl } }
    }
    closingIssuesReferences { totalCount }
    $WorkCardCheckFields
"""

private val WorkSearchQuery =
    """
    query RepositoryWorkSearch(${'$'}query: String!, ${'$'}after: String) {
        search(query: ${'$'}query, type: ISSUE, first: 30, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            nodes {
                __typename
                ... on Issue {
                    $IssueRowFields
                }
                ... on PullRequest {
                    $PullRowFields
                }
            }
        }
    }
"""
        .trimIndent()

private const val WorkCardDetailsQuery =
    """query WorkCardDetails(${'$'}ids:[ID!]!) {
    nodes(ids:${'$'}ids) { __typename ... on Issue { $WorkIssueListItemFields } ... on PullRequest { $WorkPullListItemFields } }
}"""

private val FilterOptionsQuery =
    """
    query WorkFilterOptions(${'$'}owner: String!, ${'$'}name: String!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            labels(first: 100) { nodes { id name color } }
            milestones(first: 100, states: [OPEN, CLOSED]) { nodes { id title } }
            issueTypes(first: 50) { nodes { id name color } }
            issueTemplates {
                name title body
                assignees(first: 30) { nodes { id login name } }
                labels(first: 30) { nodes { id name color } }
                type { id }
            }
        }
    }
    """
        .trimIndent()

private val AssignableUsersQuery =
    """
    query RepositoryAssignableUsers(
        ${'$'}owner: String!, ${'$'}repo: String!, ${'$'}query: String, ${'$'}after: String
    ) {
        repository(owner: ${'$'}owner, name: ${'$'}repo) {
            assignableUsers(first: 50, query: ${'$'}query, after: ${'$'}after) {
                pageInfo { hasNextPage endCursor }
                nodes { id login name }
            }
        }
    }
    """
        .trimIndent()
