package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryOwnerDto
import moe.yumeyuka.yumehub.data.auth.SessionFailures
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import retrofit2.HttpException

@Serializable
data class RepositoryOverview(
    val id: String,
    val starred: Boolean,
    val subscription: String?,
    val permission: String?,
    val stars: Int,
    val forks: Int,
    val watchers: Int,
    val issues: Int,
    val pullRequests: Int,
    val totalIssues: Int,
    val totalPullRequests: Int,
    val discussions: Int,
    val releases: Int,
    val hasProjects: Boolean,
    val hasWorkflows: Boolean,
    val hasContributors: Boolean,
    val issuesEnabled: Boolean,
    val discussionsEnabled: Boolean,
    val archived: Boolean,
    val license: String?,
    /** 仓库设置里的网站地址；未填写时为 null。 */
    val homepage: String? = null,
    val subscriptionTypes: Set<String> = emptySet(),
) {
    val canEditDescription: Boolean
        get() = permission == "ADMIN" && !archived
}

/** 仓库首页的关系、统计与管理操作；正文、目录仍由 RepositoryContentRepository 提供。 */
class RepositoryOverviewRepository(
    private val api: GitHubGraphQlApi,
    private val cache: CachedDataRepository,
    private val tokens: TokenStore,
    private val contents: RepositoryContentRepository,
    private val favorites: HomeFavoritesRepository,
    private val sessionFailures: SessionFailures,
) {
    private val pinsMutex = Mutex()
    val account: String
        get() = tokens.accountLogin.value

    fun resource(account: String, owner: String, name: String) =
        cache.resource(
            account,
            "repository-home-v3:${owner.lowercase(Locale.ROOT)}/${name.lowercase(Locale.ROOT)}",
            RepositoryOverview.serializer(),
        )

    suspend fun load(account: String, owner: String, name: String): RepositoryOverview {
        val session = session(account)
        val data =
            execute(
                session,
                RepositoryHomeQuery,
                buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                },
            )
        return parseRepositoryOverview(data.requiredObject("repository"))
    }

    suspend fun setStarred(
        account: String,
        owner: String,
        name: String,
        selected: Boolean,
    ): RepositoryOverview {
        val resource = resource(account, owner, name)
        val state = resource.cached ?: throw RepositoryOverviewException()
        val field = if (selected) "addStar" else "removeStar"
        val data =
            execute(
                session(account),
                "mutation RepositoryStar(${'$'}id: ID!) { $field(input: {starrableId: ${'$'}id}) { starrable { id viewerHasStarred ... on Repository { stargazerCount } } } }",
                buildJsonObject { put("id", state.id) },
                true,
            )
        val result = data.requiredObject(field).requiredObject("starrable")
        if (
            result.requiredText("id") != state.id ||
                result.requiredBoolean("viewerHasStarred") != selected
        )
            throw RepositoryOverviewException()
        val updated =
            (resource.cached ?: state).copy(
                starred = selected,
                stars = result.requiredInt("stargazerCount"),
            )
        resource.update(updated)
        cache
            .resource(
                account,
                "starred-state:${owner.lowercase(Locale.ROOT)}/${name.lowercase(Locale.ROOT)}",
                Boolean.serializer(),
            )
            .update(selected)
        cache.changes(account).publish(DataDomain.Repositories, DataDomain.Relationships)
        return updated
    }

    suspend fun setSubscription(
        account: String,
        owner: String,
        name: String,
        value: String,
        types: Set<String> = emptySet(),
    ) {
        require(value in setOf("UNSUBSCRIBED", "SUBSCRIBED", "IGNORED", "CUSTOM"))
        require(
            types.all {
                it in setOf("DISCUSSION", "ISSUE", "PULL_REQUEST", "RELEASE", "SECURITY_ALERT")
            },
        )
        require(value == "CUSTOM" || types.isEmpty())
        require(value != "CUSTOM" || types.isNotEmpty())
        val resource = resource(account, owner, name)
        val state = resource.cached ?: throw RepositoryOverviewException()
        val data =
            execute(
                session(account),
                RepositorySubscriptionMutation,
                buildJsonObject {
                    put("id", state.id)
                    put("state", value)
                    if (value == "CUSTOM")
                        put("types", JsonArray(types.sorted().map(::JsonPrimitive)))
                },
                true,
            )
        val result = data.requiredObject("updateSubscription").requiredObject("subscribable")
        if (
            result.requiredText("id") != state.id ||
                result.requiredText("viewerSubscription") != value
        )
            throw RepositoryOverviewException()
        val confirmedTypes = result.subscriptionTypes()
        if (value == "CUSTOM" && confirmedTypes != types) throw RepositoryOverviewException()
        resource.update(
            (resource.cached ?: state).copy(
                subscription = value,
                subscriptionTypes = confirmedTypes,
            ),
        )
        cache.invalidateMatching(account) { it.startsWith("work-subscription.") }
        cache.changes(account).publish(DataDomain.Work, DataDomain.Notifications)
    }

    suspend fun saveDescription(account: String, owner: String, name: String, description: String) {
        val resource = resource(account, owner, name)
        val state = resource.cached ?: throw RepositoryOverviewException()
        check(state.canEditDescription)
        val details = contents.details(account, owner, name)
        val data =
            execute(
                session(account),
                "mutation RepositoryDescription(${'$'}id: ID!, ${'$'}description: String!) { updateRepository(input: {repositoryId: ${'$'}id, description: ${'$'}description}) { repository { id description } } }",
                buildJsonObject {
                    put("id", state.id)
                    put("description", description)
                },
                true,
            )
        val result = data.requiredObject("updateRepository").requiredObject("repository")
        if (
            result.requiredText("id") != state.id ||
                result.text("description").orEmpty() != description
        )
            throw RepositoryOverviewException()
        details.cached?.let { details.update(it.copy(description = description)) }
            ?: details.invalidate()
        cache.changes(account).publish(DataDomain.Repositories)
    }

    suspend fun isPinned(account: String, id: String): Boolean =
        readPins(session(account)).ids.contains(id)

    suspend fun dashboardFavorites(account: String): List<RepositoryDto> =
        readPins(session(account)).repositories

    suspend fun setPinned(
        account: String,
        repository: RepositoryDto,
        id: String,
        selected: Boolean,
    ) =
        updateDashboardFavorites(account, listOf(repository.copy(nodeId = id))) { current ->
            updatedDashboardPins(current, id, selected)
        }

    suspend fun saveDashboardFavorites(
        account: String,
        original: List<String>,
        selected: List<RepositoryDto>,
    ) =
        updateDashboardFavorites(account, selected) { current ->
            mergeDashboardFavorites(current, original, selected.map { requireNotNull(it.nodeId) })
        }

    private suspend fun updateDashboardFavorites(
        account: String,
        metadata: List<RepositoryDto>,
        update: (List<String>) -> List<String>,
    ) {
        val session = session(account)
        val favoritesResource = favorites.resource(account)
        pinsMutex.withLock {
            // 每次保存基于最新完整集合，保留其他仓库、节点类型和原顺序。
            val current = readPins(session)
            val ids = update(current.ids)
            val confirmed =
                if (ids == current.ids) current
                else {
                    val data =
                        execute(
                            session,
                            "mutation RepositoryPin(${'$'}ids: [ID!]!) { updateUserDashboardPins(input: {itemIds: ${'$'}ids}) { user { login $PinsFields } } }",
                            buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) },
                            true,
                        )
                    parsePins(
                        data.requiredObject("updateUserDashboardPins").requiredObject("user"),
                        account,
                    )
                }
            if (confirmed.ids != ids) throw RepositoryOverviewException()
            val metadataById = metadata.associateBy { it.nodeId }
            favoritesResource.update(
                confirmed.repositories.map { repository ->
                    val extra = metadataById[repository.nodeId]
                    if (extra == null) repository
                    else repository.copy(description = extra.description, language = extra.language)
                },
            )
            cache.changes(account).publish(DataDomain.Repositories)
        }
    }

    private suspend fun readPins(session: Session): Pins =
        parsePins(
            execute(session, "query RepositoryPins { viewer { login $PinsFields } }")
                .requiredObject("viewer"),
            session.account,
        )

    private fun session(account: String): Session =
        synchronized(tokens.responseCacheSession.lock) {
            if (account.isBlank() || !tokens.accountLogin.value.equals(account, true))
                throw CancellationException("Repository account changed")
            Session(account, tokens.responseCacheSession.generation, tokens.accessToken.value)
                .also { checkSession(it) }
        }

    private fun checkSession(session: Session) {
        if (
            session.token.isBlank() ||
                tokens.responseCacheSession.generation != session.generation ||
                !tokens.accountLogin.value.equals(session.account, true)
        )
            throw CancellationException("Repository session changed")
    }

    private suspend fun execute(
        session: Session,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        write: Boolean = false,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        synchronized(tokens.responseCacheSession.lock) { checkSession(session) }
        val response =
            try {
                api.execute(
                    GraphQlRequest(query, variables),
                    headers = tokens.authorization(session.account, session.generation),
                    policy = if (write) SingleSubmission else null,
                )
            } catch (error: HttpException) {
                if (error.code() == 401 && tokens.accessToken.value == session.token) {
                    sessionFailures.reportUnauthorized(session.token)
                }
                throw error
            }
        currentCoroutineContext().ensureActive()
        synchronized(tokens.responseCacheSession.lock) { checkSession(session) }
        if (response.errors.isNotEmpty()) throw RepositoryOverviewException()
        return response.data as? JsonObject ?: throw RepositoryOverviewException()
    }

    private data class Session(val account: String, val generation: Long, val token: String)

    private data class Pins(val ids: List<String>, val repositories: List<RepositoryDto>)

    private fun parsePins(viewer: JsonObject, account: String): Pins {
        checkViewer(viewer, account)
        val connection = viewer.requiredObject("dashboardPinnedItems")
        if (connection.nextCursor() != null) throw RepositoryOverviewException()
        val nodes = connection.requiredNodes()
        val ids = nodes.map { it.requiredText("id") }
        if (ids.distinct().size != ids.size) throw RepositoryOverviewException()
        val repositories =
            nodes
                .filter { it.requiredText("__typename") == "Repository" }
                .map { node ->
                    val owner = node.requiredObject("owner")
                    RepositoryDto(
                        nodeId = node.requiredText("id"),
                        id = node["databaseId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        name = node.requiredText("name"),
                        fullName = "${owner.requiredText("login")}/${node.requiredText("name")}",
                        htmlUrl = node.requiredText("url"),
                        owner =
                            RepositoryOwnerDto(
                                owner.text("__typename"),
                                owner.requiredText("login"),
                                owner.text("avatarUrl"),
                            ),
                    )
                }
        return Pins(ids, repositories)
    }
}

internal fun mergeDashboardFavorites(
    current: List<String>,
    original: List<String>,
    selected: List<String>,
): List<String> {
    val removed = original.toSet() - selected.toSet()
    val added = selected.filterNot { it in original }
    val merged = added.fold(current.filterNot { it in removed }) { ids, id ->
        updatedDashboardPins(ids, id, selected = true)
    }
    if (selected == original.filterNot { it in removed } + added) return merged
    // 只交换草稿中仓库所占的位置，保留其他类型、并发新增和并发移除的项目。
    val order = selected.filter { it in merged }.iterator()
    return merged.map { if (it in selected) order.next() else it }
}

internal fun updatedDashboardPins(
    current: List<String>,
    id: String,
    selected: Boolean,
): List<String> {
    require(id.isNotBlank() && current.size <= 25 && current.distinct().size == current.size)
    if (!selected) return current.filterNot { it == id }
    if (id in current) return current
    if (current.size == 25) throw RepositoryFavoritesLimitException()
    return current + id
}

internal fun parseRepositoryOverview(node: JsonObject): RepositoryOverview =
    RepositoryOverview(
        id = node.requiredText("id"),
        starred = node.requiredBoolean("viewerHasStarred"),
        subscription = node.text("viewerSubscription"),
        subscriptionTypes = node.subscriptionTypes(),
        permission = node.text("viewerPermission"),
        stars = node.requiredInt("stargazerCount"),
        forks = node.requiredInt("forkCount"),
        watchers = node.requiredObject("watchers").requiredInt("totalCount"),
        issues = node.requiredObject("issues").requiredInt("totalCount"),
        pullRequests = node.requiredObject("pullRequests").requiredInt("totalCount"),
        totalIssues = node.requiredObject("allIssues").requiredInt("totalCount"),
        totalPullRequests = node.requiredObject("allPullRequests").requiredInt("totalCount"),
        discussions = node.requiredObject("discussions").requiredInt("totalCount"),
        releases = node.requiredObject("releases").requiredInt("totalCount"),
        hasProjects = node.hasEntry("projectsV2", "url"),
        hasWorkflows = node.hasEntry("workflows", "id"),
        hasContributors = node.hasEntry("contributors", "id"),
        issuesEnabled = node.requiredBoolean("hasIssuesEnabled"),
        discussionsEnabled = node.requiredBoolean("hasDiscussionsEnabled"),
        archived = node.requiredBoolean("isArchived"),
        // 官方仓库 fragment 同样使用 homepageUrl（仓库 Settings 里的 Website）。
        homepage = node.text("homepageUrl")?.takeIf(String::isNotBlank),
        license =
            (node["licenseInfo"] as? JsonObject)?.let {
                it.text("spdxId")?.takeUnless { id -> id == "NOASSERTION" } ?: it.text("name")
            },
    )

class RepositoryOverviewException : Exception("Repository operation failed")

class RepositoryFavoritesLimitException : Exception("Dashboard pins limit reached")

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.hasEntry(connection: String, field: String): Boolean {
    val nodes = (this[connection] as? JsonObject)?.get("nodes") as? JsonArray
    return nodes?.any { (it as? JsonObject)?.text(field)?.isNotBlank() == true } == true
}

private fun JsonObject.requiredText(key: String) =
    text(key)?.takeIf(String::isNotBlank) ?: throw RepositoryOverviewException()

private fun JsonObject.requiredObject(key: String) =
    this[key] as? JsonObject ?: throw RepositoryOverviewException()

private fun JsonObject.requiredInt(key: String) =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        ?: throw RepositoryOverviewException()

private fun JsonObject.requiredBoolean(key: String) =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: throw RepositoryOverviewException()

private fun JsonObject.subscriptionTypes(): Set<String> =
    when (val types = this["viewerSubscriptionTypes"]) {
        null,
        JsonNull -> emptySet()
        is JsonArray ->
            types
                .map {
                    (it as? JsonPrimitive)
                        ?.takeIf { value -> value.isString }
                        ?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: throw RepositoryOverviewException()
                }
                .toSet()
        else -> throw RepositoryOverviewException()
    }

private fun JsonObject.requiredNodes() =
    (this["nodes"] as? JsonArray)?.map { it as? JsonObject ?: throw RepositoryOverviewException() }
        ?: throw RepositoryOverviewException()

private fun JsonObject.nextCursor(): String? {
    val page = requiredObject("pageInfo")
    return if (page.requiredBoolean("hasNextPage")) page.requiredText("endCursor") else null
}

private fun checkViewer(viewer: JsonObject, account: String) {
    if (!viewer.requiredText("login").equals(account, true)) throw RepositoryOverviewException()
}

private const val RepositoryHomeQuery =
    """
query RepositoryHome(${'$'}owner: String!, ${'$'}name: String!) {
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    id viewerHasStarred viewerSubscription viewerSubscriptionTypes viewerPermission stargazerCount forkCount
    homepageUrl
    hasIssuesEnabled hasDiscussionsEnabled isArchived
    watchers { totalCount } issues(states: OPEN) { totalCount }
    pullRequests(states: OPEN) { totalCount } discussions { totalCount } releases { totalCount }
    allIssues: issues(first: 1) { totalCount }
    allPullRequests: pullRequests(first: 1) { totalCount }
    projectsV2(first: 1, orderBy: {field: RELEVANCE, direction: DESC}) { nodes { url } }
    workflows(first: 1, orderBy: {field: NAME, direction: ASC}) { nodes { id } }
    contributors(first: 1) { nodes { id } }
    licenseInfo { spdxId name }
  }
}
"""
private const val RepositorySubscriptionMutation =
    """
mutation UpdateSubscription(${'$'}id: ID!, ${'$'}state: SubscriptionState!, ${'$'}types: [CustomSubscriptionType!]) {
  updateSubscription(input: {subscribableId: ${'$'}id, state: ${'$'}state, types: ${'$'}types}) {
    subscribable {
      id viewerSubscription
      ... on Repository { viewerSubscriptionTypes }
    }
  }
}
"""
private const val PinsFields =
    """
dashboardPinnedItems(first: 25) {
  nodes { __typename ... on Node { id } ... on Repository { name databaseId url owner { __typename login avatarUrl } } }
  pageInfo { hasNextPage endCursor }
}
"""
