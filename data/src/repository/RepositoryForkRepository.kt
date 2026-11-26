package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubApi
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.ForkRepositoryRequest
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.auth.SessionFailures
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import retrofit2.HttpException

/** 与官方 ForkRepositoryInput 一致，只允许向当前个人账号创建复刻。 */
class RepositoryForkRepository(
    private val api: GitHubApi,
    private val tokens: TokenStore,
    private val sessionFailures: SessionFailures,
    private val cache: CachedDataRepository,
    private val graphQlApi: GitHubGraphQlApi,
) {
    class Session
    internal constructor(
        val account: String,
        internal val generation: Long,
        internal val token: String,
    )

    fun session(): Session? =
        synchronized(tokens.responseCacheSession.lock) {
            Session(
                    tokens.accountLogin.value,
                    tokens.responseCacheSession.generation,
                    tokens.accessToken.value,
                )
                .takeIf(::isActive)
        }

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun isActive(session: Session): Boolean =
        synchronized(tokens.responseCacheSession.lock) {
            session.account.isNotBlank() &&
                session.token.isNotBlank() &&
                tokens.accountLogin.value.equals(session.account, true) &&
                tokens.responseCacheSession.generation == session.generation &&
                tokens.accessToken.value == session.token
        }

    /** 仅当前账号命名空间的明确 NOT_FOUND 表示没有同名仓库。 */
    suspend fun nameExists(session: Session, name: String): Boolean =
        request(session) {
            val response =
                graphQlApi.execute(
                    GraphQlRequest(
                        ForkNameQuery,
                        buildJsonObject {
                            put("ownerLogin", session.account)
                            put("repositoryName", name)
                        },
                    ),
                    headers = tokens.authorization(session.account, session.generation),
                )
            val node = (response.data as? JsonObject)?.get("repository")
            when {
                response.errors.isEmpty() && node is JsonObject -> {
                    node.graphRequiredText("id")
                    true
                }
                node == JsonNull &&
                    response.errors.size == 1 &&
                    response.errors.single().let {
                        (it.type ?: it.extensions?.graphText("type")) == "NOT_FOUND"
                    } -> false
                else -> throw GithubPageException(response.errors.firstOrNull()?.message)
            }
        }

    suspend fun create(
        session: Session,
        owner: String,
        repository: String,
        name: String,
        defaultBranchOnly: Boolean,
    ): RepositoryDto =
        request(session) {
                require(name.isNotBlank() && name.length <= 100)
                api.forkRepository(
                        owner,
                        repository,
                        ForkRepositoryRequest(name, defaultBranchOnly),
                        authorization =
                            tokens
                                .authorization(session.account, session.generation)
                                .getValue("Authorization"),
                    )
                    .also {
                        check(
                            it.owner?.login.equals(session.account, true) &&
                                it.name.equals(name, true) &&
                                it.fork == true,
                        )
                    }
            }

    private fun checkSession(session: Session) {
        if (!isActive(session)) throw CancellationException("Fork repository session changed")
    }

    private suspend fun <T> request(session: Session, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkSession(session)
        val result =
            try {
                block()
            } catch (error: HttpException) {
                if (error.code() == 401 && isActive(session))
                    sessionFailures.reportUnauthorized(session.token)
                throw error
            }
        currentCoroutineContext().ensureActive()
        checkSession(session)
        return result
    }
}

private val ForkNameQuery =
    """
    query CheckRepositoryReady(${'$'}ownerLogin: String!, ${'$'}repositoryName: String!) {
        repository(owner: ${'$'}ownerLogin, name: ${'$'}repositoryName) { id }
    }
    """
        .trimIndent()
