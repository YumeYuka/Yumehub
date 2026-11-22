package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/** 探索搜索只取列表展示与导航字段；正文由详情页按需读取。 */
class GraphQlSearchRepository(private val api: GitHubGraphQlApi, private val tokens: TokenStore) {
    suspend fun actors(
        query: String,
        organizations: Boolean,
        after: String?,
    ): SearchSlice<UserDto> {
        val qualifier = if (organizations) "type:org" else "type:user"
        val result =
            connection("ExploreActors", ActorSearchQuery, "$query $qualifier", after, "search")
        val expected = if (organizations) "Organization" else "User"
        val actors =
            result
                .searchNodes()
                .filter { it.searchText("__typename") == expected }
                .map { node ->
                    UserDto(
                        login = node.requiredSearchText("login"),
                        type = expected,
                        name = node.searchText("name"),
                        avatarUrl = node.searchText("avatarUrl"),
                    )
                }
        return SearchSlice(
            actors,
            result.nextSearchCursor(after),
            result.getValue("userCount").jsonPrimitive.int,
        )
    }

    suspend fun code(query: String, after: String?): SearchSlice<SearchCodeFile> =
        parseSearchCode(
            connection("GlobalCodeSearch", CodeSearchQuery, query, after, "codeSearch"),
            after,
        )

    private suspend fun connection(
        operation: String,
        document: String,
        query: String,
        after: String?,
        field: String,
    ): JsonObject {
        val response =
            api.execute(
                GraphQlRequest(
                    document,
                    buildJsonObject {
                        put("query", query)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                    operation,
                ),
            )
        currentCoroutineContext().ensureActive()
        if (response.errors.isNotEmpty()) throw DashboardFeedException(response.errors)
        return (response.data as? JsonObject)?.get(field) as? JsonObject
            ?: throw DashboardFeedException()
    }

    suspend fun page(
        query: String,
        category: ExploreCategory,
        after: String?,
        account: String? = null,
    ): GraphQlSearchPage {
        val generation = tokens.responseCacheSession.generation
        val headers = account?.let { tokens.authorization(it, generation) }.orEmpty()
        val repositories = category == ExploreCategory.Repositories
        val qualified =
            when (category) {
                ExploreCategory.Repositories -> query
                ExploreCategory.Issues -> "$query is:issue"
                ExploreCategory.PullRequests -> "$query is:pr"
                else -> error("Unexpected work/repository search type: $category")
            }
        val response =
            traceExplore("Explore.search") {
                api.execute(
                    GraphQlRequest(
                        SearchQuery,
                        buildJsonObject {
                            put("query", qualified)
                            put("type", if (repositories) "REPOSITORY" else "ISSUE")
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                        "ExploreSearch",
                    ),
                    headers = headers,
                )
            }
        currentCoroutineContext().ensureActive()
        if (account != null) tokens.requireSession(account, generation)
        return parseGraphQlSearch(response, after, repositories)
    }
}

data class GraphQlSearchPage(
    val repositories: List<RepositoryDto>,
    val work: List<CollaborationItemDto>,
    val totalCount: Int,
    val nextCursor: String?,
)

internal fun parseGraphQlSearch(
    response: GraphQlResponse,
    after: String?,
    repositorySearch: Boolean,
): GraphQlSearchPage {
    if (response.errors.isNotEmpty()) throw DashboardFeedException(response.errors)
    val search =
        (response.data as? JsonObject)?.get("search") as? JsonObject
            ?: throw DashboardFeedException()
    val cursor = search.nextSearchCursor(after)
    val nodes = search["nodes"] as? JsonArray ?: throw DashboardFeedException()
    val repositories = mutableListOf<RepositoryDto>()
    val work = mutableListOf<CollaborationItemDto>()
    nodes.filterIsInstance<JsonObject>().forEach { node ->
        fun text(key: String) = node[key]?.jsonPrimitive?.contentOrNull
        when (text("__typename")) {
            "Repository" -> {
                val repository = node.dashboardRepository()
                if (repository != null) repositories.add(repository.copy(nodeId = text("id")))
            }
            "Issue",
            "PullRequest" ->
                work.add(
                    CollaborationItemDto(
                        number =
                            node["number"]?.jsonPrimitive?.intOrNull
                                ?: throw DashboardFeedException(),
                        title = text("title") ?: throw DashboardFeedException(),
                        state =
                            (text("issueState") ?: text("pullRequestState"))?.lowercase(Locale.ROOT)
                                ?: throw DashboardFeedException(),
                        htmlUrl = text("url"),
                        nodeId = text("id"),
                        createdAt = text("createdAt"),
                        updatedAt = text("updatedAt"),
                        bodyPreview = node.workCardBodyPreview(),
                        participantAvatars = node.workCardParticipants(),
                        additions = node["additions"]?.jsonPrimitive?.intOrNull,
                        deletions = node["deletions"]?.jsonPrimitive?.intOrNull,
                        headRefName = text("headRefName"),
                        baseRefName = text("baseRefName"),
                        reviewDecision = text("reviewDecision"),
                        checkState = node.workCardCheckState(),
                        stateReason = text("stateReason"),
                        repositoryName =
                            (node["repository"] as? JsonObject)
                                ?.get("nameWithOwner")
                                ?.jsonPrimitive
                                ?.contentOrNull,
                        participantsCount =
                            (node["participants"] as? JsonObject)
                                ?.get("totalCount")
                                ?.jsonPrimitive
                                ?.intOrNull,
                        user =
                            (node["author"] as? JsonObject)?.let { author ->
                                UserDto(
                                    login = author["login"]?.jsonPrimitive?.contentOrNull,
                                    avatarUrl = author["avatarUrl"]?.jsonPrimitive?.contentOrNull,
                                )
                            },
                        labels =
                            (node["labels"] as? JsonObject)
                                ?.let { it["nodes"] as? JsonArray }
                                ?.mapNotNull { it as? JsonObject }
                                ?.mapNotNull { label ->
                                    label["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                                        CollaborationLabelDto(
                                            name = name,
                                            color = label["color"]?.jsonPrimitive?.contentOrNull,
                                        )
                                    }
                                }
                                .orEmpty(),
                        commentsCount =
                            (node["comments"] as? JsonObject)
                                ?.get("totalCount")
                                ?.jsonPrimitive
                                ?.intOrNull
                                ?: node["totalCommentsCount"]?.jsonPrimitive?.intOrNull
                                ?: 0,
                        draft = node["isDraft"]?.jsonPrimitive?.booleanOrNull ?: false,
                        merged = node["merged"]?.jsonPrimitive?.booleanOrNull ?: false,
                    ),
                )
        }
    }
    val countKey = if (repositorySearch) "repositoryCount" else "issueCount"
    val count = search[countKey]?.jsonPrimitive?.intOrNull ?: throw DashboardFeedException()
    return GraphQlSearchPage(repositories, work, count, cursor)
}

internal val SearchQuery =
    """
    query ExploreSearch(${'$'}query: String!, ${'$'}type: SearchType!, ${'$'}after: String) {
        search(query: ${'$'}query, type: ${'$'}type, first: 30, after: ${'$'}after) {
            repositoryCount issueCount
            pageInfo { hasNextPage endCursor }
            nodes {
                __typename
                ... on Repository {
                    id name url description stargazerCount forkCount
                    primaryLanguage { name }
                    owner { login avatarUrl __typename }
                }
                ... on Issue {
                    id number title bodyText issueState: state stateReason url createdAt updatedAt
                    author { login avatarUrl }
                    repository { nameWithOwner }
                    labels(first: 6) { nodes { name color } }
                    comments { totalCount }
                    participants(first: 3) { totalCount nodes { login avatarUrl } }
                }
                ... on PullRequest {
                    id number title bodyText pullRequestState: state url createdAt updatedAt
                    isDraft merged totalCommentsCount additions deletions reviewDecision
                    headRefName baseRefName
                    author { login avatarUrl }
                    repository { nameWithOwner }
                    labels(first: 6) { nodes { name color } }
                    participants(first: 3) { totalCount nodes { login avatarUrl } }
                    $WorkCardCheckFields
                }
            }
        }
    }
    """
        .trimIndent()
