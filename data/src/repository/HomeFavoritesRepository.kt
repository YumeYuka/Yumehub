package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryOwnerDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.RemoteFetch

/** 官方 GitHub Mobile 1.283.0 的首页收藏，区别于个人资料 pinnedItems 和 starredRepositories */
class HomeFavoritesRepository(
    private val graphQlApi: GitHubGraphQlApi,
    private val json: Json,
    private val cache: CachedDataRepository,
) {
    fun resource(account: String): CachedResource<List<RepositoryDto>> =
        cache.resource(
            account,
            "home.favorites.v1",
            ListSerializer(RepositoryDto.serializer()),
            tags = setOf(CacheTag(DataDomain.Repositories, "dashboard-pins")),
        )

    /** 同步读取内存；磁盘快照由资源在 IO 线程恢复。 */
    fun cached(account: String): List<RepositoryDto>? = resource(account).cached

    fun refresh(account: String, force: Boolean = false) =
        resource(account).refresh(force) { loadRemote(account) }

    /** 收集期间持续校验；收集方结束时取消仅由它发起的请求，已被其他调用方加入的请求继续完成。 */
    fun observe(account: String): Flow<CachedDataState<List<RepositoryDto>>> =
        resource(account).observe { RemoteFetch.Fresh(loadRemote(account)) }

    suspend fun load(account: String, force: Boolean = false): List<RepositoryDto> =
        resource(account).await(force) { loadRemote(account) }

    private suspend fun loadRemote(account: String): List<RepositoryDto> {
        val response = graphQlApi.execute(GraphQlRequest(PinnedItemsQuery))
        if (response.errors.isNotEmpty()) {
            // 不把服务端错误当作空列表，也不向界面回显查询或认证相关错误原文
            throw HomeFavoritesException("暂时无法加载收藏，请重试")
        }
        val data = response.data ?: throw HomeFavoritesException("暂时无法加载收藏，请重试")
        val payload = json.decodeFromJsonElement<PinnedItemsPayload>(data)
        val viewer = payload.viewer ?: throw HomeFavoritesException("暂时无法加载收藏，请重试")
        // 查询结果由当前令牌决定，写入快照前确认它与界面传下来的账号一致。
        val viewerLogin =
            viewer.login?.takeIf { it.isNotBlank() } ?: throw HomeFavoritesException("暂时无法加载收藏，请重试")
        if (!viewerLogin.equals(account, ignoreCase = true)) {
            throw HomeFavoritesException("收藏账号不一致，请重试")
        }
        val nodes =
            viewer.dashboardPinnedItems?.nodes ?: throw HomeFavoritesException("暂时无法加载收藏，请重试")
        val repositories =
            nodes
                .mapNotNull { node ->
                    if (node == null || node.type != "Repository") return@mapNotNull null
                    val ownerNode = node.owner ?: throw HomeFavoritesException("收藏仓库信息不完整，请重试")
                    val owner =
                        ownerNode.login?.takeIf { it.isNotBlank() }
                            ?: throw HomeFavoritesException("收藏仓库信息不完整，请重试")
                    val name =
                        node.name?.takeIf { it.isNotBlank() }
                            ?: throw HomeFavoritesException("收藏仓库信息不完整，请重试")
                    RepositoryDto(
                        id = node.databaseId,
                        name = name,
                        fullName = "$owner/$name",
                        htmlUrl = node.url,
                        owner =
                            RepositoryOwnerDto(
                                login = owner,
                                avatarUrl = ownerNode.avatarUrl,
                                type = ownerNode.type,
                            ),
                    )
                }
                .distinctBy { it.fullName }
        val cachedByName = resource(account).cached.orEmpty().associateBy { it.fullName }
        return repositories.map { repository ->
            val cached = cachedByName[repository.fullName]
            if (repository.owner?.avatarUrl == null && cached?.owner?.avatarUrl != null) {
                repository.copy(owner = repository.owner?.copy(avatarUrl = cached.owner.avatarUrl))
            } else {
                repository
            }
        }
    }
}

class HomeFavoritesException(message: String) : Exception(message)

@Serializable private data class PinnedItemsPayload(val viewer: PinnedItemsViewer? = null)

@Serializable
private data class PinnedItemsViewer(
    val login: String? = null,
    val dashboardPinnedItems: PinnedItemsConnection? = null,
)

@Serializable private data class PinnedItemsConnection(val nodes: List<PinnedRepository?>? = null)

@Serializable
private data class PinnedRepository(
    @SerialName("__typename") val type: String? = null,
    val databaseId: Long? = null,
    val name: String? = null,
    val url: String? = null,
    val owner: PinnedRepositoryOwner? = null,
)

@Serializable
private data class PinnedRepositoryOwner(
    @SerialName("__typename") val type: String? = null,
    val login: String? = null,
    val avatarUrl: String? = null,
)

// 来源：官方 APK 内 PinnedItems / HomePinnedItems / SimpleRepositoryFragment，保留其读取顺序与 25 条上限
// 只读首页收藏，不调用 updateUserDashboardPins 修改官方收藏
private val PinnedItemsQuery =
    """
    query PinnedItems(${'$'}pinnedItemsCount: Int = 25) {
      viewer {
        login
        dashboardPinnedItems(first: ${'$'}pinnedItemsCount) {
          nodes {
            __typename
            ... on Repository {
              id
              databaseId
              name
              url
              owner { __typename login avatarUrl }
            }
          }
        }
      }
    }
    """
        .trimIndent()
