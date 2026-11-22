package moe.yumeyuka.yumehub.feature.profile

import android.text.Html
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

internal data class FollowUser(
    val id: String?,
    val login: String?,
    val name: String?,
    val avatar: String?,
    val bio: String?,
)

internal class FollowPagingSource(
    private val api: GitHubGraphQlApi,
    private val login: String,
    private val following: Boolean,
    private val tokens: TokenStore,
) : PagingSource<String, FollowUser>() {
    private val account = tokens.accountLogin.value
    private val generation = tokens.sessionGeneration.value

    override fun getRefreshKey(state: PagingState<String, FollowUser>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, FollowUser> =
        try {
            tokens.requireSession(account, generation)
            val field = if (following) "following" else "followers"
            val response =
                api.execute(
                    GraphQlRequest(
                        query =
                            """query FollowList(${'$'}login: String!, ${'$'}first: Int!, ${'$'}after: String) {
                user(login: ${'$'}login) { $field(first: ${'$'}first, after: ${'$'}after) {
                    pageInfo { hasNextPage endCursor }
                    nodes { id login name avatarUrl bioHTML }
                } }
            }"""
                                .trimIndent(),
                        variables =
                            buildJsonObject {
                                put("login", login)
                                put("first", 30)
                                params.key?.let { put("after", it) }
                            },
                    ),
                )
            tokens.requireSession(account, generation)
            // GraphQL 的部分错误不应抹掉同一页里仍可用的用户或空节点位置。
            val data =
                response.data as? JsonObject ?: throw IllegalStateException("Missing follow data")
            val user =
                data["user"] as? JsonObject ?: throw IllegalStateException("Missing follow user")
            val connection =
                user[field] as? JsonObject
                    ?: throw IllegalStateException("Missing follow connection")
            val entries =
                connection["nodes"] as? JsonArray
                    ?: throw IllegalStateException("Missing follow nodes")
            val nodes = entries.map { node ->
                val obj = node as? JsonObject
                fun value(key: String) =
                    (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                FollowUser(
                    id = value("id"),
                    login = value("login"),
                    name = value("name"),
                    avatar = value("avatarUrl"),
                    bio =
                        value("bioHTML")?.let {
                            Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        },
                )
            }
            val info =
                connection["pageInfo"] as? JsonObject
                    ?: throw IllegalStateException("Missing page info")
            val hasNext =
                (info["hasNextPage"] as? JsonPrimitive)?.booleanOrNull
                    ?: throw IllegalStateException("Missing next-page state")
            val next = (info["endCursor"] as? JsonPrimitive)?.contentOrNull
            check(!hasNext || (!next.isNullOrBlank() && next != params.key))
            LoadResult.Page(nodes, prevKey = null, nextKey = if (hasNext) next else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}
