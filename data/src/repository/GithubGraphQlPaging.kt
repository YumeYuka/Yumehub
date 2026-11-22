package moe.yumeyuka.yumehub.data.repository

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

data class GithubGraphQlPage<T>(val items: List<T>, val nextCursor: String?)

/** 每份数据源绑定一次会话；失效后不允许用新账号的令牌继续旧页面请求。 */
internal class GithubGraphQlSession(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val account: String,
) {
    private val generation =
        synchronized(tokens.responseCacheSession.lock) { tokens.responseCacheSession.generation }

    init {
        ensureCurrent()
    }

    fun ensureCurrent() =
        synchronized(tokens.responseCacheSession.lock) {
            if (
                account.isBlank() ||
                    tokens.accessToken.value.isBlank() ||
                    !tokens.accountLogin.value.equals(account, true) ||
                    generation != tokens.responseCacheSession.generation
            )
                throw CancellationException("GraphQL account changed")
        }

    suspend fun execute(
        query: String,
        variables: JsonObject,
        write: Boolean = false,
        features: String? = null,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val response =
            api.execute(
                GraphQlRequest(query, variables),
                headers =
                    tokens.authorization(account, generation) +
                        (features?.let { mapOf("GraphQL-Features" to it) } ?: emptyMap()),
                policy = if (write) SingleSubmission else null,
            )
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        if (response.errors.isNotEmpty()) {
            val message =
                response.errors
                    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .distinct()
                    .joinToString("; ")
                    .takeIf(String::isNotBlank)
            throw GithubPageException(message)
        }
        return response.data as? JsonObject ?: throw GithubPageException()
    }
}

/** 页码仅作本地索引，网络翻页只使用真实 cursor；同一 Source 的加载串行化。 */
fun <T : Any> githubGraphQlPager(
    account: String,
    key: String,
    paging: PagingRepository,
    identity: (T) -> String,
    config: androidx.paging.PagingConfig = defaultPagingConfig(),
    loader: () -> suspend (String?) -> GithubGraphQlPage<T>,
): Flow<PagingData<T>> =
    paging.pages(account, key, config) { context ->
        object : PagingSource<Int, T>() {
            private val mutex = Mutex()
            private val cursors = mutableMapOf<Int, String?>(1 to null)
            private val seen = mutableMapOf<String, Int>()
            private var request: (suspend (String?) -> GithubGraphQlPage<T>)? = null

            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
                mutex.withLock {
                    try {
                        context.ensureCurrent()
                        val page = params.key ?: 1
                        if (page !in cursors || page !in 1..1000) throw GithubPageException()
                        val load = request ?: loader().also { request = it }
                        val result = load(cursors[page])
                        currentCoroutineContext().ensureActive()
                        context.ensureCurrent()
                        val next = result.nextCursor
                        if (
                            next != null &&
                                (page == 1000 ||
                                    cursors.any { (index, value) ->
                                        index != page + 1 && value == next
                                    })
                        )
                            throw GithubPageException()
                        if (page + 1 in cursors && cursors[page + 1] != next)
                            throw GithubPageException()
                        val local = mutableSetOf<String>()
                        val items =
                            result.items.filter {
                                val id =
                                    identity(it).takeIf(String::isNotBlank)
                                        ?: throw GithubPageException()
                                local.add(id) && seen.getOrPut(id) { page } == page
                            }
                        if (next != null) cursors[page + 1] = next
                        LoadResult.Page(
                            items,
                            (page - 1).takeIf { it >= 1 },
                            if (next == null) null else page + 1,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        LoadResult.Error(error)
                    }
                }

            override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
        }
    }

class GithubPageException(message: String? = null) : Exception(message)

internal fun JsonObject.graphObject(key: String): JsonObject =
    get(key) as? JsonObject ?: throw GithubPageException()

internal fun JsonObject.graphText(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.graphRequiredText(key: String): String =
    graphText(key)?.takeIf(String::isNotBlank) ?: throw GithubPageException()

internal fun JsonObject.graphNodes(): List<JsonObject> =
    (get("nodes") as? JsonArray ?: throw GithubPageException()).map {
        it as? JsonObject ?: throw GithubPageException()
    }

internal fun JsonObject.graphCursor(after: String?): String? {
    val info = graphObject("pageInfo")
    val more = (info["hasNextPage"] as? JsonPrimitive)?.booleanOrNull ?: throw GithubPageException()
    return if (more)
        info.graphRequiredText("endCursor").also { if (it == after) throw GithubPageException() }
    else null
}
