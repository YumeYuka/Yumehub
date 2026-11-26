package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import org.jsoup.Jsoup

/** 官方移动端 Feed 查询；不以 received_events 冒充包含关注、讨论和推荐的首页动态。 */
class DashboardFeedRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val cache: CachedDataRepository,
) {
    private fun pages(account: String) =
        cache.sessionResource(account, "feed-filters") { DashboardFeedLoader() }

    fun mutations(account: String) = cache.mutations(account)

    fun trackStar(account: String, slug: String, row: RepositoryDto?) =
        cache.starredRows(account).track(slug, row)

    fun effectiveFilters(account: String) = pages(account).filters

    fun filterCommand(account: String) = mutations(account).observeCommand(pages(account).filterKey)

    fun applyFilters(account: String, groups: Set<String>): Boolean {
        val generation = tokens.responseCacheSession.generation
        return pages(account).apply(groups, mutations(account)) { request, write ->
            execute(account, generation, request, write)
        }
    }

    suspend fun page(
        account: String,
        after: String?,
    ): DashboardFeedPage {
        val session = tokens.responseCacheSession
        val generation =
            synchronized(session.lock) {
                checkAccount(account)
                session.generation
            }
        val response =
            pages(account).load(after) { request ->
                execute(account, generation, request)
            }
        // 节点映射与 HTML 文本转换不占用服务器筛选锁，也不在主线程执行。
        return traceExplore("Explore.feed.map") {
            withContext(Dispatchers.Default) {
                val feed = response.feed()
                val connection = feed.obj("items") ?: throw DashboardFeedException(response.errors)
                val nodes =
                    connection["nodes"] as? JsonArray
                        ?: throw DashboardFeedException(response.errors)
                val info =
                    connection.obj("pageInfo") ?: throw DashboardFeedException(response.errors)
                val hasNext =
                    info.flag("hasNextPage") ?: throw DashboardFeedException(response.errors)
                val next =
                    if (hasNext) {
                        info.text("endCursor")?.takeIf { it.isNotBlank() && it != after }
                            ?: throw DashboardFeedException(response.errors)
                    } else null
                // 部分响应中可用节点仍展示；空数据伴随错误不能伪装成加载成功。
                val events =
                    nodes
                        .filterIsInstance<JsonObject>()
                        .flatMap { node ->
                            listOf(node) +
                                (node["relatedItems"] as? JsonArray)
                                    .orEmpty()
                                    .filterIsInstance<JsonObject>()
                        }
                        .mapNotNull(::dashboardFeedEvent)
                        .distinctBy { it.id }
                if (events.isEmpty() && response.errors.isNotEmpty()) {
                    throw DashboardFeedException(response.errors)
                }
                DashboardFeedPage(events, next)
            }
        }
    }

    private suspend fun execute(
        account: String,
        generation: Long,
        request: GraphQlRequest,
        write: Boolean = false,
    ): GraphQlResponse {
        currentCoroutineContext().ensureActive()
        val session = tokens.responseCacheSession
        synchronized(session.lock) {
            checkAccount(account)
            if (generation != session.generation)
                throw CancellationException("Dashboard session changed")
        }
        val result =
            traceExplore("Explore.${request.operationName ?: "GraphQL"}") {
                api.execute(
                    request,
                    headers = DashboardFeedHeaders + tokens.authorization(account, generation),
                    policy = if (write) SingleSubmission else null,
                )
            }
        currentCoroutineContext().ensureActive()
        synchronized(session.lock) {
            checkAccount(account)
            if (generation != session.generation)
                throw CancellationException("Dashboard session changed")
        }
        return result
    }

    /** 缺字段／手动重试合并成一次 GraphQL 查询，不逐项补 REST 资料、关系与贡献者。 */
    suspend fun refreshCard(account: String, event: FeedEventDto): FeedEventDto {
        val generation =
            synchronized(tokens.responseCacheSession.lock) {
                checkAccount(account)
                tokens.responseCacheSession.generation
            }
        val login = event.payload.target?.login
        val userCard =
            login != null && event.type in setOf("FollowEvent", "FollowRecommendationEvent")
        val request =
            if (userCard) {
                GraphQlRequest(
                    DashboardUserCardQuery,
                    buildJsonObject { put("login", login) },
                    "FeedUserCard",
                )
            } else {
                val fullName = event.repo?.name ?: throw DashboardFeedException()
                GraphQlRequest(
                    DashboardRepositoryCardQuery,
                    buildJsonObject {
                        put("owner", fullName.substringBefore('/'))
                        put("name", fullName.substringAfter('/'))
                    },
                    "FeedRepositoryCard",
                )
            }
        val response = execute(account, generation, request)
        if (response.errors.isNotEmpty()) throw DashboardFeedException(response.errors)
        val target =
            (response.data as? JsonObject)?.obj("target")
                ?: throw DashboardFeedException(response.errors)
        val id = target.text("id") ?: throw DashboardFeedException(response.errors)
        return if (userCard) {
            if (!target.text("login").equals(login, ignoreCase = true))
                throw DashboardFeedException()
            event.copy(
                payload = event.payload.copy(target = target.dashboardUser()),
                viewerSelected = target.flag("viewerIsFollowing"),
                subjectId = id,
            )
        } else {
            val repository = target.dashboardRepository() ?: throw DashboardFeedException()
            if (!repository.fullName.equals(event.repo?.name, ignoreCase = true))
                throw DashboardFeedException()
            event.copy(
                repositoryDetails = repository,
                viewerSelected = target.flag("viewerHasStarred"),
                contributorsCount = event.contributorsCount,
                subjectId = id,
            )
        }
    }

    /** 官方 Feed 带回的 Node ID 直接用于写入；老快照缺 ID 时才补一次 GraphQL。 */
    suspend fun setSelected(account: String, event: FeedEventDto, selected: Boolean): Boolean {
        val generation =
            synchronized(tokens.responseCacheSession.lock) {
                checkAccount(account)
                tokens.responseCacheSession.generation
            }
        val resolved = if (event.subjectId != null) event else refreshCard(account, event)
        val id = resolved.subjectId ?: throw DashboardFeedException()
        val user =
            resolved.payload.target?.takeIf {
                resolved.type in setOf("FollowEvent", "FollowRecommendationEvent")
            }
        val field: String
        val input: String
        val resultField: String
        val relationship: String
        when {
            user?.type == "Organization" -> {
                field = if (selected) "followOrganization" else "unfollowOrganization"
                input = "organizationId"
                resultField = "organization"
                relationship = "viewerIsFollowing"
            }
            user != null -> {
                field = if (selected) "followUser" else "unfollowUser"
                input = "userId"
                resultField = "user"
                relationship = "viewerIsFollowing"
            }
            else -> {
                field = if (selected) "addStar" else "removeStar"
                input = "starrableId"
                resultField = "starrable"
                relationship = "viewerHasStarred"
            }
        }
        val countField = if (user == null) " ... on Repository { stargazerCount }" else ""
        val response =
            execute(
                account,
                generation,
                GraphQlRequest(
                    "mutation FeedSelection(${'$'}id: ID!) { $field(input: {$input: ${'$'}id}) { $resultField { id $relationship$countField } } }",
                    buildJsonObject { put("id", id) },
                    "FeedSelection",
                ),
                write = true,
            )
        response.checkWriteErrors()
        val result =
            (response.data as? JsonObject)?.obj(field)?.obj(resultField)
                ?: throw DashboardFeedException(response.errors)
        if (result.text("id") != id) throw DashboardFeedException(response.errors)
        val actual = result.flag(relationship) ?: throw DashboardFeedException(response.errors)
        val key =
            if (user != null) "following-state:${user.login?.lowercase(Locale.ROOT)}"
            else "starred-state:${event.repo?.name?.lowercase(Locale.ROOT)}"
        val stateTag =
            if (user != null) relationshipCacheTag(user.login.orEmpty())
            else relationshipCacheTag(event.repo?.name.orEmpty())
        if (user == null) {
            val slug = requireNotNull(resolved.repo?.name)
            val stars =
                (result["stargazerCount"] as? JsonPrimitive)?.intOrNull
                    ?: throw DashboardFeedException()
            val overview =
                synchronized(tokens.responseCacheSession.lock) {
                    tokens.requireSession(account, generation)
                    cache.overviewResource(
                        account,
                        slug.substringBefore('/'),
                        slug.substringAfter('/'),
                    )
                }
            if (overview.cached == null) overview.restore()
            synchronized(tokens.responseCacheSession.lock) {
                tokens.requireSession(account, generation)
                overview.cached
                    ?.takeIf { it.id == id }
                    ?.let { overview.update(it.copy(starred = actual, stars = stars)) }
            }
        }
        synchronized(tokens.responseCacheSession.lock) {
            tokens.requireSession(account, generation)
            cache
                .resource(account, key, Boolean.serializer(), tags = setOf(stateTag))
                .update(actual)
        }
        return actual
    }

    suspend fun organizationFollowing(account: String, login: String): Boolean =
        organizationRelationship(account, login, selected = null)

    suspend fun setOrganizationFollowing(
        account: String,
        login: String,
        selected: Boolean,
    ): Boolean = organizationRelationship(account, login, selected)

    /** 组织关注使用官方 Organization mutation，不能走用户 REST following 接口。 */
    private suspend fun organizationRelationship(
        account: String,
        login: String,
        selected: Boolean?,
    ): Boolean {
        val session = tokens.responseCacheSession
        val generation =
            synchronized(session.lock) {
                checkAccount(account)
                session.generation
            }
        suspend fun execute(request: GraphQlRequest, write: Boolean = false): GraphQlResponse {
            suspend fun ensureSession() {
                currentCoroutineContext().ensureActive()
                synchronized(session.lock) {
                    checkAccount(account)
                    if (generation != session.generation)
                        throw CancellationException("Organization follow session changed")
                }
            }
            ensureSession()
            return api.execute(
                    request,
                    headers = DashboardFeedHeaders + tokens.authorization(account, generation),
                    policy = if (write) SingleSubmission else null,
                )
                .also { ensureSession() }
        }
        val response =
            execute(
                GraphQlRequest(
                    """
                    query FeedOrganization(${'$'}login: String!) {
                                    organization(login: ${'$'}login) { id viewerIsFollowing }
                                }
                    """
                        .trimIndent(),
                    buildJsonObject { put("login", login) },
                    operationName = "FeedOrganization",
                ),
            )
        if (response.errors.isNotEmpty()) throw DashboardFeedException(response.errors)
        val organization =
            (response.data as? JsonObject)?.obj("organization")
                ?: throw DashboardFeedException(response.errors)
        val id =
            organization.text("id")?.takeIf(String::isNotBlank)
                ?: throw DashboardFeedException(response.errors)
        val following =
            organization.flag("viewerIsFollowing") ?: throw DashboardFeedException(response.errors)
        if (selected == null || selected == following) return following
        val operation = if (selected) "FollowOrganization" else "UnfollowOrganization"
        val field = if (selected) "followOrganization" else "unfollowOrganization"
        val updated =
            execute(
                GraphQlRequest(
                    """mutation $operation(${'$'}organizationId: ID!) {
                $field(input: { organizationId: ${'$'}organizationId }) {
                    organization { id viewerIsFollowing }
                }
            }"""
                        .trimIndent(),
                    buildJsonObject { put("organizationId", id) },
                    operationName = operation,
                ),
                write = true,
            )
        updated.checkWriteErrors()
        val result =
            (updated.data as? JsonObject)?.obj(field)?.obj("organization")
                ?: throw DashboardFeedException(updated.errors)
        if (result.text("id") != id) throw DashboardFeedException(updated.errors)
        val actual =
            result.flag("viewerIsFollowing") ?: throw DashboardFeedException(updated.errors)
        synchronized(tokens.responseCacheSession.lock) {
            tokens.requireSession(account, generation)
            cache
                .resource(
                    account,
                    "following-state:${login.lowercase(Locale.ROOT)}",
                    Boolean.serializer(),
                    tags = setOf(relationshipCacheTag(login)),
                )
                .update(actual)
        }
        return actual
    }

    private fun checkAccount(account: String) {
        if (account.isBlank() || !tokens.accountLogin.value.equals(account, ignoreCase = true)) {
            throw CancellationException("Dashboard feed account changed")
        }
    }
}

@Serializable
data class DashboardFeedPage(val items: List<FeedEventDto>, val nextCursor: String? = null)

class DashboardFeedException(val errors: List<GraphQlError> = emptyList()) :
    Exception("GitHub dashboard feed response was not usable")

internal fun GraphQlResponse.feed(): JsonObject =
    (data as? JsonObject)?.obj("viewer")?.obj("dashboard")?.obj("feed")
        ?: throw DashboardFeedException(errors)

internal fun JsonObject.filters(response: GraphQlResponse): Map<String, Boolean> {
    val filters = this["filters"] as? JsonArray ?: throw DashboardFeedException(response.errors)
    return filters.associate { element ->
        val filter = element as? JsonObject ?: throw DashboardFeedException(response.errors)
        val group = filter.text("filterGroup") ?: throw DashboardFeedException(response.errors)
        val enabled = filter.flag("isEnabled") ?: throw DashboardFeedException(response.errors)
        group to enabled
    }
}

internal fun Map<String, Boolean>.matches(selected: Set<String>): Boolean =
    ManagedFeedGroups.all { group ->
        this[group] == (group in selected)
    }

internal val ManagedFeedGroups =
    setOf("ANNOUNCEMENTS", "RELEASES", "STARS", "REPOSITORIES", "FOLLOWS", "RECOMMENDATIONS")

internal fun dashboardFeedEvent(node: JsonObject): FeedEventDto? {
    val sourceType = node.text("__typename") ?: return null
    val type =
        when (sourceType) {
            "CreatedDiscussionFeedItem" -> "DiscussionEvent"
            "CreatedRepositoryFeedItem" -> "CreateEvent"
            "FollowRecommendationFeedItem" -> "FollowRecommendationEvent"
            "FollowedUserFeedItem" -> "FollowEvent"
            "ForkedRepositoryFeedItem" -> "ForkEvent"
            "MergedPullRequestFeedItem" -> "PullRequestEvent"
            "PublishedReleaseFeedItem" -> "ReleaseEvent"
            "RepositoryRecommendationFeedItem" -> "RepositoryRecommendationEvent"
            "StarredRepositoryFeedItem" -> "WatchEvent"
            else -> return null
        }
    val id = node.text("identifier")?.takeIf(String::isNotBlank) ?: return null
    val discussion = node.obj("discussion")
    val work = discussion ?: node.obj("pullRequest")
    val release = node.obj("release")
    val repository = node.obj("repository") ?: work?.obj("repository") ?: release?.obj("repository")
    val repo = repository?.dashboardRepository()
    val followee = node.obj("followee")?.dashboardUser()
    val actor = (node.obj("actor") ?: node.obj("follower"))?.dashboardUser()
    if (repo == null && followee == null) return null
    return FeedEventDto(
        id = "dashboard:$id",
        type = type,
        actor = actor,
        repo = repo?.fullName?.let(::FeedRepositoryDto),
        payload =
            FeedPayloadDto(
                action = if (type == "PullRequestEvent") "closed" else "opened",
                target = followee,
                refType = if (type == "CreateEvent") "repository" else null,
                pullRequest = if (discussion == null) work?.work(merged = true) else null,
                discussion = discussion?.work(),
                release =
                    release?.let {
                        FeedReleaseDto(
                            name = it.text("name"),
                            tagName = it.text("tagName"),
                            htmlUrl = it.text("url"),
                            body =
                                it.text("shortDescriptionHTML")?.let { html ->
                                    Jsoup.parseBodyFragment(html).body().text()
                                },
                            socialPreviewImageUrl = it.obj("repository")?.text("openGraphImageUrl"),
                            usesCustomOpenGraphImage =
                                it.obj("repository")?.flag("usesCustomOpenGraphImage") == true,
                        )
                    },
            ),
        createdAt = node.text("createdAt"),
        sourceType = sourceType,
        repositoryDetails = repo,
        viewerSelected =
            if (followee != null) node.obj("followee")?.flag("viewerIsFollowing")
            else repository?.flag("viewerHasStarred"),
        contributorsCount = repository?.number("contributorsCount"),
        previewImageUrl = node.text("previewImageUrl"),
        subjectId = (node.obj("followee") ?: repository)?.text("id"),
    )
}

internal fun JsonObject.dashboardUser() =
    UserDto(
        type = text("__typename"),
        login = text("login"),
        avatarUrl = text("avatarUrl"),
        name = text("name"),
        bio = text("bio") ?: text("description"),
        htmlUrl = text("url"),
        followers = obj("followers")?.number("totalCount"),
        publicRepos = obj("repositories")?.number("totalCount"),
    )

internal fun JsonObject.dashboardRepository(): RepositoryDto? {
    val owner = obj("owner") ?: return null
    val login = owner.text("login") ?: return null
    val name = text("name") ?: return null
    return RepositoryDto(
        name = name,
        fullName = "$login/$name",
        owner = RepositoryOwnerDto(owner.text("__typename"), login, owner.text("avatarUrl")),
        htmlUrl = text("url"),
        description = text("description"),
        openGraphImageUrl = text("openGraphImageUrl"),
        language = obj("primaryLanguage")?.text("name"),
        stargazersCount = number("stargazerCount"),
        forksCount = number("forkCount"),
    )
}

private fun JsonObject.work(merged: Boolean = false) =
    FeedWorkDto(
        number = number("number"),
        title = text("title"),
        body = workCardBodyPreview(),
        author = obj("author")?.dashboardUser(),
        state = text("state")?.lowercase(),
        merged = merged,
        draft = flag("isDraft") ?: false,
        htmlUrl = text("url"),
    )

private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
