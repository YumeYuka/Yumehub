package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.core.model.CollaborationSortOrder
import moe.yumeyuka.yumehub.core.model.CollaborationStateFilter
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.DiscussionCategoryDto
import moe.yumeyuka.yumehub.data.api.dto.DiscussionListItemDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

@Serializable
data class RepositoryActivityEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val url: String? = null,
    val login: String? = null,
    val avatarUrl: String? = null,
    val state: String? = null,
    val number: Int? = null,
    val targetId: String? = null,
    val timestamp: String? = null,
    val runCount: Int? = null,
    val event: String? = null,
    val durationSeconds: Int? = null,
    val workflowName: String? = null,
)

/** 仓库子列表统一走官方 APK 使用的 GraphQL connection，不新增 REST 路径。 */
class RepositoryActivityRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val cache: CachedDataRepository,
) {
    private val filterOptionsCache = FilterOptionsCache()

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun itemsKey(
        owner: String,
        name: String,
        kind: RepositoryActivityKind,
        nodeId: String? = null,
        projectNumber: Int? = null,
        organization: Boolean = false,
    ): String = "repository-activity.v1:$owner/$name:$kind:$nodeId:$projectNumber:$organization"

    fun discussionItemsKey(
        owner: String,
        name: String,
        personal: Boolean,
        filter: CollaborationListFilter,
    ): String = "discussion-search.v4:$owner/$name:$personal:$filter"

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun items(
        account: String,
        owner: String,
        name: String,
        kind: RepositoryActivityKind,
        nodeId: String? = null,
        projectNumber: Int? = null,
        organization: Boolean = false,
    ) =
        githubGraphQlPager(
            account,
            itemsKey(owner, name, kind, nodeId, projectNumber, organization),
            paging,
            RepositoryActivityEntry::id,
            serializer = RepositoryActivityEntry.serializer(),
            tags = setOf(repositoryCacheTag(owner, name)),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val load: suspend (String?) -> GithubGraphQlPage<RepositoryActivityEntry> = { after ->
                val variables = buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                    put("query", "author:$account sort:updated-desc")
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    nodeId?.let { put("id", it) }
                    projectNumber?.let { put("number", it) }
                }
                val data =
                    session.execute(
                        query(kind, organization),
                        variables,
                    )
                val connection =
                    when (kind) {
                        RepositoryActivityKind.PersonalProjects ->
                            data.graphObject("viewer").graphObject("allProjectsV2")
                        RepositoryActivityKind.PersonalDiscussions -> data.graphObject("search")
                        RepositoryActivityKind.CheckRuns ->
                            data.graphObject("node").graphObject("checkRuns")
                        RepositoryActivityKind.CheckSteps ->
                            data.graphObject("node").graphObject("steps")
                        RepositoryActivityKind.WorkflowRuns ->
                            data.graphObject("node").graphObject("runs")
                        RepositoryActivityKind.ProjectItems ->
                            data
                                .graphObject(if (organization) "organization" else "user")
                                .graphObject("projectV2")
                                .graphObject("items")
                        else -> data.graphObject("repository").graphObject(field(kind))
                    }
                val entries =
                    connection.graphNodes().map { node ->
                        when (kind) {
                            RepositoryActivityKind.CheckSteps ->
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("number"),
                                    title = node.graphRequiredText("name"),
                                    number = node["number"]?.jsonPrimitive?.intOrNull,
                                    state =
                                        node.graphText("conclusion") ?: node.graphText("status"),
                                    timestamp = node.graphText("startedAt"),
                                    durationSeconds =
                                        node["secondsToCompletion"]?.jsonPrimitive?.intOrNull,
                                )
                            RepositoryActivityKind.CheckRuns ->
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("id"),
                                    title = node.graphRequiredText("name"),
                                    timestamp = node.graphText("startedAt"),
                                    targetId = node.graphRequiredText("id"),
                                    state =
                                        node.graphText("conclusion") ?: node.graphText("status"),
                                )
                            RepositoryActivityKind.Actions -> {
                                val runs = node.graphObject("runs")
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("id"),
                                    title = node.graphRequiredText("name"),
                                    state = node.graphRequiredText("state"),
                                    runCount = runs.graphRequiredText("totalCount").toInt(),
                                    timestamp =
                                        runs.graphNodes().firstOrNull()?.graphText("createdAt"),
                                )
                            }
                            RepositoryActivityKind.Contributors,
                            RepositoryActivityKind.Watchers ->
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("id"),
                                    title =
                                        node.graphText("name")?.takeIf(String::isNotBlank)
                                            ?: node.graphRequiredText("login"),
                                    subtitle = node.graphRequiredText("login"),
                                    url = node.graphRequiredText("url"),
                                    login = node.graphRequiredText("login"),
                                    avatarUrl = node.graphText("avatarUrl"),
                                )
                            RepositoryActivityKind.ProjectItems -> {
                                val content = node["content"] as? JsonObject
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("id"),
                                    title = content?.graphText("title").orEmpty(),
                                    url = content?.graphText("url"),
                                    state =
                                        content?.graphText("issueState")
                                            ?: content?.graphText("pullRequestState"),
                                    subtitle =
                                        node
                                            .graphObject("fieldValues")
                                            .graphNodes()
                                            .mapNotNull { it.graphText("name") }
                                            .joinToString(" · ")
                                            .takeIf(String::isNotEmpty),
                                    number = content?.get("number")?.jsonPrimitive?.intOrNull,
                                )
                            }
                            RepositoryActivityKind.WorkflowRuns -> node.workflowRun()
                            else ->
                                RepositoryActivityEntry(
                                    id = node.graphRequiredText("id"),
                                    title =
                                        node.graphText("title") ?: node.graphRequiredText("name"),
                                    subtitle =
                                        node.graphText("shortDescription")
                                            ?: listOfNotNull(
                                                    (node["repository"] as? JsonObject)?.graphText(
                                                        "nameWithOwner",
                                                    ),
                                                    (node["category"] as? JsonObject)?.graphText(
                                                        "name",
                                                    ),
                                                )
                                                .joinToString(" · ")
                                                .takeIf(String::isNotEmpty),
                                    url = node.graphText("url"),
                                    state =
                                        node.graphText("state")
                                            ?: node["closed"]?.jsonPrimitive?.booleanOrNull?.let {
                                                if (it) "closed" else "open"
                                            },
                                    number = node["number"]?.jsonPrimitive?.intOrNull,
                                )
                        }
                    }
                session.ensureCurrent()
                GithubGraphQlPage(entries, connection.graphCursor(after))
            }
            load
        }

    /** 仓库与个人讨论统一走 GraphQL 搜索；状态、范围和排序只影响查询串，不改变分页协议。 */
    fun discussionItems(
        account: String,
        owner: String,
        name: String,
        personal: Boolean,
        filter: CollaborationListFilter,
    ) =
        githubGraphQlPager(
            account,
            discussionItemsKey(owner, name, personal, filter),
            paging,
            { it.nodeId ?: "${it.repository}#${it.number}" },
            serializer = DiscussionListItemDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Work, null)),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val query = discussionSearchQuery(account, owner, name, personal, filter)
            val load: suspend (String?) -> GithubGraphQlPage<DiscussionListItemDto> = { after ->
                val variables = buildJsonObject {
                    put("query", query)
                    put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                }
                val data = session.execute(DiscussionSearchQuery, variables)
                val search = data.graphObject("search")
                GithubGraphQlPage(
                    search.activitySafeNodes().mapNotNull { node ->
                        node.toDiscussionItem(includeRepository = personal)
                    },
                    search.graphCursor(after),
                )
            }
            load
        }

    suspend fun discussionFilterOptions(
        account: String,
        owner: String,
        name: String,
    ): CollaborationFilterOptions {
        if (owner.isBlank() || name.isBlank()) return CollaborationFilterOptions()
        val session = GithubGraphQlSession(api, tokens, account)
        return filterOptionsCache
            .get(account, owner, name, tokens.sessionGeneration.value) {
                val data =
                    session.execute(
                        DiscussionFilterOptionsQuery,
                        buildJsonObject {
                            put("owner", owner)
                            put("name", name)
                        },
                    )
                val repository = data.graphObject("repository")
                val labels =
                    (repository["labels"] as? JsonObject)
                        ?.activitySafeNodes()
                        ?.mapNotNull { node ->
                            node.graphText("name")?.let { label ->
                                CollaborationLabelDto(label, node.graphText("color"))
                            }
                        }
                        .orEmpty()
                val categories =
                    (repository["discussionCategories"] as? JsonObject)
                        ?.activitySafeNodes()
                        ?.mapNotNull { node ->
                            val id = node.graphText("id") ?: return@mapNotNull null
                            val name = node.graphText("name") ?: return@mapNotNull null
                            DiscussionCategoryDto(id, name)
                        }
                        .orEmpty()
                CollaborationFilterOptions(
                    labels = labels,
                    categories = categories.map { it.name },
                    discussionCategories = categories,
                    repositoryId = repository.graphRequiredText("id"),
                )
            }
            .also { session.ensureCurrent() }
    }

    suspend fun createDiscussion(
        account: String,
        repositoryId: String,
        categoryId: String,
        title: String,
        body: String,
    ): Int {
        require(repositoryId.isNotBlank() && categoryId.isNotBlank())
        require(title.isNotBlank())
        val session = GithubGraphQlSession(api, tokens, account)
        val created =
            session
                .execute(
                    """
                    mutation CreateDiscussion(
                        ${'$'}repositoryId: ID!, ${'$'}categoryId: ID!,
                        ${'$'}title: String!, ${'$'}body: String!
                    ) {
                        createDiscussion(input: {
                            repositoryId: ${'$'}repositoryId
                            categoryId: ${'$'}categoryId
                            title: ${'$'}title
                            body: ${'$'}body
                        }) { discussion { number } }
                    }
                    """
                        .trimIndent(),
                    buildJsonObject {
                        put("repositoryId", repositoryId)
                        put("categoryId", categoryId)
                        put("title", title)
                        put("body", body)
                    },
                    write = true,
                )
                .graphObject("createDiscussion")
                .graphObject("discussion")
        return created["number"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.also {
            cache.invalidateAndPersist(account, setOf(CacheTag(DataDomain.Work, null)))
            cache.changes(account).publish(DataDomain.Work)
        } ?: throw GithubPageException()
    }

    private fun field(kind: RepositoryActivityKind): String =
        when (kind) {
            RepositoryActivityKind.Projects -> "projectsV2"
            RepositoryActivityKind.Discussions -> "discussions"
            RepositoryActivityKind.Actions -> "workflows"
            RepositoryActivityKind.Contributors -> "contributors"
            RepositoryActivityKind.Watchers -> "watchers"
            else -> error("Connection is not owned by a repository")
        }

    private fun query(kind: RepositoryActivityKind, organization: Boolean): String {
        if (kind == RepositoryActivityKind.PersonalProjects)
            return """
            query PersonalProjects(${'$'}after: String) {
                viewer { allProjectsV2(first: 30, after: ${'$'}after, query: "", orderBy: {field: UPDATED_AT, direction: DESC}) {
                    $PageFields nodes { id title number shortDescription url closed }
                } }
            }
        """
                .trimIndent()
        if (kind == RepositoryActivityKind.PersonalDiscussions)
            return """
            query PersonalDiscussions(${'$'}query: String!, ${'$'}after: String) {
                search(first: 30, after: ${'$'}after, type: DISCUSSION, query: ${'$'}query) {
                    $PageFields nodes { ... on Discussion { id title number url closed category { name } repository { nameWithOwner } } }
                }
            }
        """
                .trimIndent()
        if (kind == RepositoryActivityKind.CheckRuns || kind == RepositoryActivityKind.CheckSteps) {
            val runs = kind == RepositoryActivityKind.CheckRuns
            return """
                query WorkflowChecks(${'$'}id: ID!, ${'$'}after: String) {
                    node(id: ${'$'}id) { ... on ${if (runs) "CheckSuite" else "CheckRun"} {
                        ${if (runs) "checkRuns" else "steps"}(first: 30, after: ${'$'}after) {
                            $PageFields nodes { name status conclusion startedAt ${if (runs) "id" else "number secondsToCompletion"} }
                        }
                    } }
                }
            """
                .trimIndent()
        }
        val connection =
            when (kind) {
                RepositoryActivityKind.PersonalProjects,
                RepositoryActivityKind.PersonalDiscussions,
                RepositoryActivityKind.CheckRuns,
                RepositoryActivityKind.CheckSteps -> error("Handled above")
                RepositoryActivityKind.Projects ->
                    """
                projectsV2(first: 30, after: ${'$'}after, orderBy: {field: RELEVANCE, direction: DESC}) {
                    $PageFields nodes { id title number shortDescription url closed }
                }
            """
                RepositoryActivityKind.Discussions ->
                    """
                discussions(first: 30, after: ${'$'}after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                    $PageFields nodes { id title number url closed category { name } }
                }
            """
                RepositoryActivityKind.Actions ->
                    """
                workflows(first: 30, after: ${'$'}after, orderBy: {field: NAME, direction: ASC}) {
                    $PageFields nodes { id name state runs(first: 1) { totalCount nodes { createdAt } } }
                }
            """
                RepositoryActivityKind.Contributors,
                RepositoryActivityKind.Watchers ->
                    """
                ${field(kind)}(first: 30, after: ${'$'}after) {
                    $PageFields nodes { id name login avatarUrl url }
                }
            """
                RepositoryActivityKind.WorkflowRuns ->
                    return """
                query RepositoryWorkflowRuns(${'$'}id: ID!, ${'$'}after: String) {
                    node(id: ${'$'}id) { ... on Workflow {
                        runs(first: 30, after: ${'$'}after) {
                            $PageFields nodes { $WorkflowRunFields }
                        }
                    } }
                }
            """
                        .trimIndent()
                RepositoryActivityKind.ProjectItems ->
                    return """
                query ProjectItems(${'$'}owner: String!, ${'$'}number: Int!, ${'$'}after: String) {
                    ${if (organization) "organization" else "user"}(login: ${'$'}owner) {
                        projectV2(number: ${'$'}number) { items(first: 30, after: ${'$'}after) {
                            $PageFields nodes {
                                id fieldValues(first: 30) { nodes { ... on ProjectV2ItemFieldSingleSelectValue { name } } }
                                content {
                                    ... on Issue { title number url issueState: state }
                                    ... on PullRequest { title number url pullRequestState: state }
                                    ... on DraftIssue { title }
                                }
                            }
                        } }
                    }
                }
            """
                        .trimIndent()
            }
        return """
            query RepositoryActivity(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) { $connection }
            }
        """
            .trimIndent()
    }
}

private fun discussionSearchQuery(
    account: String,
    owner: String,
    name: String,
    personal: Boolean,
    filter: CollaborationListFilter,
): String = buildList {
    if (!personal) add("repo:$owner/$name")
    filter.searchQuery.trim().takeIf(String::isNotEmpty)?.let {
        add("in:title,body ${activitySearchQuote(it)}")
    }
    when (filter.state) {
        CollaborationStateFilter.Open -> add("is:open")
        CollaborationStateFilter.Closed -> add("is:closed")
        CollaborationStateFilter.All -> Unit
    }
    when (filter.scope) {
        CollaborationScopeFilter.All -> Unit
        CollaborationScopeFilter.CreatedByMe -> add("author:$account")
        CollaborationScopeFilter.Commented -> add("commenter:$account")
        CollaborationScopeFilter.AssignedToMe,
        CollaborationScopeFilter.ReviewRequested,
        CollaborationScopeFilter.Mentioned,
        CollaborationScopeFilter.Involved -> add("author:$account")
    }
    if (filter.unansweredOnly) add("is:unanswered")
    filter.label?.let { add("label:${activitySearchQuote(it)}") }
    filter.category?.let { add("category:${activitySearchQuote(it)}") }
    add(
        when (filter.sort) {
            CollaborationSortOrder.Updated -> "sort:updated-desc"
            CollaborationSortOrder.Created -> "sort:created-desc"
            CollaborationSortOrder.Comments -> "sort:comments-desc"
        },
    )
}
    .joinToString(" ")

private fun activitySearchQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun JsonObject.activitySafeNodes(): List<JsonObject> =
    (get("nodes") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.toDiscussionItem(
    includeRepository: Boolean,
): DiscussionListItemDto? {
    if (graphText("__typename") != "Discussion") return null
    val author = get("author") as? JsonObject
    val category = get("category") as? JsonObject
    val repository = get("repository") as? JsonObject
    return DiscussionListItemDto(
        number = (get("number") as? JsonPrimitive)?.intOrNull ?: return null,
        title = graphText("title") ?: return null,
        closed = (get("closed") as? JsonPrimitive)?.booleanOrNull ?: false,
        url = graphText("url"),
        nodeId = graphText("id"),
        author =
            author?.let {
                UserDto(
                    login = it.graphText("login"),
                    avatarUrl = it.graphText("avatarUrl"),
                    type = it.graphText("__typename"),
                )
            },
        category = category?.graphText("name"),
        repository = if (includeRepository) repository?.graphText("nameWithOwner") else null,
        labels =
            (get("labels") as? JsonObject)
                ?.activitySafeNodes()
                ?.mapNotNull { label ->
                    label.graphText("name")?.let { name ->
                        CollaborationLabelDto(name = name, color = label.graphText("color"))
                    }
                }
                .orEmpty(),
        commentsCount =
            (get("comments") as? JsonObject)?.let {
                (it["totalCount"] as? JsonPrimitive)?.intOrNull
            } ?: 0,
        commentAuthors =
            (get("comments") as? JsonObject)
                ?.activitySafeNodes()
                ?.mapNotNull { comment ->
                    val commentAuthor = comment["author"] as? JsonObject ?: return@mapNotNull null
                    val login = commentAuthor.graphText("login")
                    val avatarUrl = commentAuthor.graphText("avatarUrl")
                    if (login.isNullOrBlank() && avatarUrl.isNullOrBlank()) return@mapNotNull null
                    UserDto(login = login, avatarUrl = avatarUrl)
                }
                .orEmpty(),
        answered = graphText("answerChosenAt") != null,
        bodyPreview = graphText("bodyText")?.trim()?.take(480),
        authorAssociation = graphText("authorAssociation"),
        upvoteCount = (get("upvoteCount") as? JsonPrimitive)?.intOrNull ?: 0,
        createdAt = graphText("createdAt"),
        updatedAt = graphText("updatedAt"),
    )
}

private val DiscussionSearchQuery =
    """
    query RepositoryDiscussionSearch(${'$'}query: String!, ${'$'}after: String) {
        search(query: ${'$'}query, type: DISCUSSION, first: 30, after: ${'$'}after) {
            discussionCount
            pageInfo { hasNextPage endCursor }
            nodes {
                __typename
                ... on Discussion {
                    id number title url closed createdAt updatedAt
                    answerChosenAt bodyText authorAssociation upvoteCount
                    author { login avatarUrl __typename }
                    category { name }
                    repository { nameWithOwner }
                    labels(first: 6) { nodes { name color } }
                    comments(last: 3) {
                        totalCount
                        nodes { author { login avatarUrl } }
                    }
                }
            }
        }
    }
    """
        .trimIndent()

private val DiscussionFilterOptionsQuery =
    """
    query DiscussionFilterOptions(${'$'}owner: String!, ${'$'}name: String!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            id
            labels(first: 100) { nodes { name color } }
            discussionCategories(first: 25) { nodes { id name } }
        }
    }
    """
        .trimIndent()

private const val PageFields = "pageInfo { hasNextPage endCursor }"
