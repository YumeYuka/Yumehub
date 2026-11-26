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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlError
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.GraphQlResponse
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import retrofit2.HttpException

enum class GraphQlRequestStage { PREPARE, SEND, RESPONSE }

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
            try {
                api.execute(
                    GraphQlRequest(query, variables),
                    headers =
                        tokens.authorization(account, generation) +
                            (features?.let { mapOf("GraphQL-Features" to it) } ?: emptyMap()),
                    policy = if (write) SingleSubmission else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                if (write) {
                    val definite = error.code() in DefiniteHttpRejectionCodes
                    throw GithubPageException(
                        message = error.message(),
                        stage = GraphQlRequestStage.SEND,
                        definiteRejection = definite,
                        cause = error,
                    )
                }
                // A failed read, including read-back after a write, is not proof of a rejected mutation.
                if (moe.yumeyuka.yumehub.data.cache.ConfirmedWrites.current.get() == null) throw error
                throw GithubPageException(message = error.message(), stage = GraphQlRequestStage.RESPONSE, cause = error)
            }
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        if (response.errors.isNotEmpty()) {
            val message =
                response.errors
                    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .distinct()
                    .joinToString("; ")
                    .takeIf(String::isNotBlank)
            val definite = write && response.definitelyRejected()
            throw GithubPageException(
                message = message,
                stage = GraphQlRequestStage.RESPONSE,
                errors = response.errors,
                definiteRejection = definite,
            )
        }
        return response.data as? JsonObject
            ?: throw GithubPageException(stage = GraphQlRequestStage.RESPONSE)
    }

    private companion object {
        val DefiniteHttpRejectionCodes = setOf(400, 401, 403, 404, 409, 422)
    }
}

private val rejectionTypes = setOf("FORBIDDEN", "UNPROCESSABLE", "BAD_USER_INPUT", "NOT_FOUND",
    "UNAUTHORIZED", "UNAUTHENTICATED", "INSUFFICIENT_SCOPES", "VALIDATION_ERROR", "CONFLICT")

internal fun GraphQlResponse.definitelyRejected(): Boolean {
    if (errors.isEmpty() || !(data == null || data == JsonNull ||
            (data is JsonObject && data.values.all { it == JsonNull }))) return false
    return errors.all { error ->
        (error.type ?: (error.extensions?.get("type") as? JsonPrimitive)?.contentOrNull
            ?: (error.extensions?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (error.extensions?.get("classification") as? JsonPrimitive)?.contentOrNull) in rejectionTypes
    }
}

internal fun GraphQlResponse.checkWriteErrors() {
    if (errors.isNotEmpty()) throw GithubPageException(errors = errors, definiteRejection = definitelyRejected())
}

/** 页码仅作本地索引，网络翻页只使用真实 cursor；同一 Source 的加载串行化。 */
fun <T : Any> githubGraphQlPager(
    account: String,
    key: String,
    paging: PagingRepository,
    identity: (T) -> String,
    config: androidx.paging.PagingConfig = defaultPagingConfig(),
    serializer: KSerializer<T>? = null,
    mapItem: (T) -> T? = { it },
    tags: Set<CacheTag> = emptySet(),
    loader: () -> suspend (String?) -> GithubGraphQlPage<T>,
): Flow<PagingData<T>> =
    paging.pages(account, key, config, tags) { context ->
        val firstPageCache = serializer?.let {
            val snapshot = paging.snapshot(account, key, it, context, tags)
            GithubGraphQlFirstPageCache(
                restore = { snapshot.initialPage(config.pageSize) },
                save = snapshot::saveInBackground,
                discardDenied = snapshot::discardDenied,
                revalidate = { source, refresh ->
                    context.revalidate(source) {
                        val (page, changed) = refresh()
                        snapshot.save(page)
                        changed
                    }
                },
            )
        }
        GithubGraphQlPagingSource(
            config.pageSize, identity, mapItem, loader, context::ensureCurrent, firstPageCache,
        )
    }

internal class GithubGraphQlFirstPageCache<T : Any>(
    val restore: suspend () -> PagingSnapshotPage<T>?,
    val save: (PagingSnapshotPage<T>) -> Unit,
    val revalidate: (PagingSource<Int, T>, suspend () -> Pair<PagingSnapshotPage<T>, Boolean>) -> Unit,
    val discardDenied: suspend (Throwable) -> Unit,
)

internal class GithubGraphQlPagingSource<T : Any>(
    private val pageSize: Int,
    private val identity: (T) -> String,
    private val mapItem: (T) -> T?,
    private val loader: () -> suspend (String?) -> GithubGraphQlPage<T>,
    private val ensureCurrent: () -> Unit,
    private val firstPageCache: GithubGraphQlFirstPageCache<T>?,
) : PagingSource<Int, T>() {
    private val mutex = Mutex()
    private val cursors = mutableMapOf<Int, String?>(1 to null)
    private val seen = mutableMapOf<String, Int>()
    private val request by lazy(LazyThreadSafetyMode.NONE, loader)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> =
        mutex.withLock {
            try {
                ensureCurrent()
                val page = params.key ?: 1
                if (page !in cursors || page !in 1..1000) throw GithubPageException()
                val cached =
                    if (page == 1 && params is LoadParams.Refresh) {
                        firstPageCache?.restore?.invoke()?.takeIf {
                            it.page == 1 && it.requestCursor == null && it.nextCursor?.isBlank() != true
                        }
                    } else null
                val result =
                    if (cached == null) {
                        pageRead { requestPage(cursors[page], request) }
                    } else GithubGraphQlPage(cached.items, cached.nextCursor)
                currentCoroutineContext().ensureActive()
                ensureCurrent()
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
                    result.items.mapNotNull {
                        val id =
                            identity(it).takeIf(String::isNotBlank)
                                ?: throw GithubPageException()
                        if (local.add(id) && seen.getOrPut(id) { page } == page) mapItem(it)
                        else null
                    }
                if (next != null) cursors[page + 1] = next
                if (cached == null && page == 1) firstPageCache?.save?.invoke(firstPage(result))
                if (cached != null && firstPageCache != null && !cached.isFresh()) {
                    firstPageCache.revalidate(this) {
                        // A validation read owns its loader, never the active cursor chain or its mutex.
                        val updated = requestPage(null, loader())
                        firstPage(updated) to (cached.boundaryInvalid || updated != result)
                    }
                }
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

    private suspend fun requestPage(after: String?, load: suspend (String?) -> GithubGraphQlPage<T>): GithubGraphQlPage<T> {
        ensureCurrent()
        val page = try {
            load(after)
        } catch (error: Exception) {
            firstPageCache?.discardDenied?.invoke(error)
            throw error
        }
        return page.also { result ->
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (result.nextCursor != null &&
                (result.nextCursor.isBlank() || result.nextCursor == after)
            ) throw GithubPageException()
            if (result.items.any { identity(it).isBlank() }) throw GithubPageException()
        }
    }

    private fun firstPage(result: GithubGraphQlPage<T>): PagingSnapshotPage<T> =
        PagingSnapshotPage(
            items = result.items,
            prevKey = null,
            nextKey = result.nextCursor?.let { 2 },
            pageSize = pageSize,
            updatedAt = System.currentTimeMillis(),
            nextCursor = result.nextCursor,
        )

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}

class GithubPageException(
    message: String? = null,
    val stage: GraphQlRequestStage = GraphQlRequestStage.RESPONSE,
    val errors: List<GraphQlError> = emptyList(),
    val definiteRejection: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A denied read invalidates its cached representation. Network and rate-limit failures retain it. */
fun Throwable.invalidatesCachedContent(): Boolean = when (this) {
    is HttpException -> when (code()) {
        401, 404, 410 -> true
        403 -> response()?.headers()?.let { it["Retry-After"] == null && it["X-RateLimit-Remaining"] != "0" } == true
        else -> false
    }
    is ProfileUnavailableException, is WorkDetailTypeMismatchException, is WorkspacesFileNotFound -> true
    is WorkspacesGraphQlException -> cause?.invalidatesCachedContent() == true
    is GithubPageException -> cause?.invalidatesCachedContent() == true || errors.any { error ->
        val type = error.type ?: (error.extensions?.get("type") as? JsonPrimitive)?.contentOrNull
            ?: (error.extensions?.get("code") as? JsonPrimitive)?.contentOrNull
        type in accessFailureTypes
    }
    else -> false
}

private val accessFailureTypes = setOf("FORBIDDEN", "UNAUTHORIZED", "UNAUTHENTICATED", "INSUFFICIENT_SCOPES", "NOT_FOUND")

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
