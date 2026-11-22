package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

const val MyRepositoriesPageSize = 100

/** 列表、语言选项和星标排序共享原始页；打开菜单不再启动另一轮全量请求。 */
class MyRepositoriesRepository(
    private val api: GitHubGraphQlApi,
    private val cache: CachedDataRepository,
    private val tokens: TokenStore,
) {
    internal fun session(account: String) = GithubGraphQlSession(api, tokens, account)

    suspend fun pickerPage(account: String, after: String?): SearchSlice<RepositoryDto> {
        val page = page(session(account), account, type = "all", after = after)
        return SearchSlice(page.items, page.nextCursor)
    }

    internal suspend fun page(
        session: GithubGraphQlSession,
        account: String,
        type: String,
        after: String?,
        force: Boolean = false,
    ): MyRepositoriesPage {
        session.ensureCurrent()
        val resource =
            cache.resource(
                account,
                "my-repositories:raw:v2:type=$type:after=${after.orEmpty()}",
                MyRepositoriesPage.serializer(),
            )
        return resource
            .await(force) {
                val affiliations =
                    when (type) {
                        "owner" -> listOf("OWNER")
                        "member" -> listOf("COLLABORATOR", "ORGANIZATION_MEMBER")
                        else -> listOf("OWNER", "COLLABORATOR", "ORGANIZATION_MEMBER")
                    }
                val variables = buildJsonObject {
                    put("first", MyRepositoriesPageSize)
                    if (after != null) put("after", after)
                    putJsonArray("affiliations") { affiliations.forEach { add(it) } }
                    if (type == "public" || type == "private") {
                        put("privacy", type.uppercase(Locale.ROOT))
                    }
                }
                val viewer = session.execute(MyRepositoriesQuery, variables).graphObject("viewer")
                if (!viewer.graphRequiredText("login").equals(account, true))
                    throw GithubPageException()
                val connection = viewer.graphObject("repositories")
                MyRepositoriesPage(
                    connection.graphNodes().map(::repositoryItem),
                    connection.graphCursor(after),
                )
            }
            .also { session.ensureCurrent() }
    }

    suspend fun matching(
        account: String,
        type: String,
        sort: String,
        query: String,
        language: String?,
        force: Boolean = false,
    ): List<RepositoryDto> {
        val repositories = all(account, type, force)
        return withContext(Dispatchers.Default) {
            val matches = repositories.filter { repository ->
                (query.isBlank() ||
                    repository.fullName.orEmpty().contains(query, ignoreCase = true)) &&
                    (language == null || repository.language.equals(language, ignoreCase = true))
            }
            when (sort) {
                "stars" ->
                    matches.sortedWith(
                        compareByDescending<RepositoryDto> { it.stargazersCount ?: 0 }
                            .thenBy { it.fullName.orEmpty().lowercase(Locale.ROOT) },
                    )
                "full_name" -> matches.sortedBy { it.fullName.orEmpty().lowercase(Locale.ROOT) }
                else -> matches // all() 保留服务端 updated 降序。
            }
        }
    }

    suspend fun all(
        account: String,
        type: String = "all",
        force: Boolean = false,
    ): List<RepositoryDto> =
        cache
            .resource(
                account,
                "my-repositories:all:v2:type=$type",
                ListSerializer(RepositoryDto.serializer()),
            )
            .await(force) {
                buildList {
                    val session = session(account)
                    val cursors = mutableSetOf<String>()
                    val ids = mutableSetOf<String>()
                    var after: String? = null
                    for (pageNumber in 1..1000) {
                        val batch = page(session, account, type, after, force)
                        addAll(batch.items.filter { ids.add(requireNotNull(it.nodeId)) })
                        val next = batch.nextCursor ?: break
                        if (pageNumber == 1000 || !cursors.add(next)) throw GithubPageException()
                        after = next
                    }
                }
            }
}

@Serializable
internal data class MyRepositoriesPage(val items: List<RepositoryDto>, val nextCursor: String?)

private val MyRepositoriesQuery =
    """
    query MyRepositories(
        ${'$'}first: Int!, ${'$'}after: String, ${'$'}affiliations: [RepositoryAffiliation],
        ${'$'}privacy: RepositoryPrivacy
    ) {
        viewer {
            login
            repositories(first: ${'$'}first, after: ${'$'}after, affiliations: ${'$'}affiliations,
                privacy: ${'$'}privacy, orderBy: {field: UPDATED_AT, direction: DESC}) {
                pageInfo { hasNextPage endCursor }
                nodes { $RepoFields }
            }
        }
    }
    """
        .trimIndent()
