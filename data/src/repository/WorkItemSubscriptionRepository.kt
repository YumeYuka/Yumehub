package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationRejectedException
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

@Serializable
data class WorkItemSubscription(
    val id: String,
    val state: String,
    val canSubscribe: Boolean,
    val repositoryState: String?,
    val inherited: Boolean,
) {
    val subscribed: Boolean
        get() = state == "SUBSCRIBED" || state == "UNSUBSCRIBED" && inherited
}

class WorkItemSubscriptionRepository(
    private val api: GitHubGraphQlApi,
    private val cache: CachedDataRepository,
    private val tokens: TokenStore,
) {
    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    suspend fun load(account: String, id: String, force: Boolean = false): WorkItemSubscription {
        require(id.isNotBlank())
        val session = session(account)
        val resource =
            cache.resource(
                account,
                "work-subscription.v1:$id",
                WorkItemSubscription.serializer(),
                tags = setOf(CacheTag(DataDomain.Work, null)),
            )
        return resource.await(force) { read(session, id) }.also { checkSession(session) }
    }

    suspend fun setSubscribed(
        account: String,
        id: String,
        subscribed: Boolean,
    ): WorkItemSubscription {
        val session = session(account)
        val resource =
            cache.resource(
                account,
                "work-subscription.v1:$id",
                WorkItemSubscription.serializer(),
                tags = setOf(CacheTag(DataDomain.Work, null)),
            )
        val current = read(session, id)
        if (!current.canSubscribe) throw MutationRejectedException("Subscription unavailable")
        val next =
            when {
                subscribed -> "SUBSCRIBED"
                current.inherited || current.state == "IGNORED" -> "IGNORED"
                else -> "UNSUBSCRIBED"
            }
        val data =
            execute(
                session,
                SubscriptionMutation,
                buildJsonObject {
                    put("id", id)
                    put("state", next)
                },
                write = true,
            )
        val result =
            (data["updateSubscription"] as? JsonObject)?.get("subscribable") as? JsonObject
                ?: throw WorkItemSubscriptionException()
        if (result.text("id") != id || result.text("viewerSubscription") != next) {
            throw WorkItemSubscriptionException()
        }
        val confirmed = current.copy(state = next)
        synchronized(tokens.responseCacheSession.lock) {
            checkSession(session)
            resource.update(confirmed)
            cache.changes(account).publish(DataDomain.Work, DataDomain.Notifications)
        }
        return confirmed
    }

    private suspend fun read(session: Session, id: String): WorkItemSubscription {
        val data = execute(session, SubscriptionQuery, buildJsonObject { put("id", id) })
        val subject = data["node"] as? JsonObject ?: throw WorkItemSubscriptionException()
        val type = subject.text("__typename")
        if (subject.text("id") != id || type !in setOf("Issue", "PullRequest")) {
            throw WorkItemSubscriptionException()
        }
        val repository =
            subject["repository"] as? JsonObject ?: throw WorkItemSubscriptionException()
        val repositoryState = repository.text("viewerSubscription")
        val event = if (type == "Issue") "ISSUE" else "PULL_REQUEST"
        val types =
            (repository["viewerSubscriptionTypes"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
        return WorkItemSubscription(
            id = id,
            state = subject.text("viewerSubscription") ?: throw WorkItemSubscriptionException(),
            canSubscribe =
                (subject["viewerCanSubscribe"] as? JsonPrimitive)?.booleanOrNull
                    ?: throw WorkItemSubscriptionException(),
            repositoryState = repositoryState,
            inherited =
                repositoryState == "SUBSCRIBED" || repositoryState == "CUSTOM" && event in types,
        )
    }

    private fun session(account: String): Session =
        synchronized(tokens.responseCacheSession.lock) {
            Session(account, tokens.responseCacheSession.generation, tokens.accessToken.value)
                .also(::checkSession)
        }

    private fun checkSession(session: Session) {
        synchronized(tokens.responseCacheSession.lock) {
            if (
                session.account.isBlank() ||
                    session.token.isBlank() ||
                    !tokens.accountLogin.value.equals(session.account, true) ||
                    tokens.responseCacheSession.generation != session.generation
            )
                throw CancellationException("Work subscription session changed")
        }
    }

    private suspend fun execute(
        session: Session,
        query: String,
        variables: JsonObject,
        write: Boolean = false,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        synchronized(tokens.responseCacheSession.lock) { checkSession(session) }
        val response =
            api.execute(
                GraphQlRequest(query, variables),
                headers = tokens.authorization(session.account, session.generation),
                policy = if (write) SingleSubmission else null,
            )
        currentCoroutineContext().ensureActive()
        synchronized(tokens.responseCacheSession.lock) { checkSession(session) }
        if (response.errors.isNotEmpty()) throw WorkItemSubscriptionException()
        return response.data as? JsonObject ?: throw WorkItemSubscriptionException()
    }

    private data class Session(val account: String, val generation: Long, val token: String)
}

class WorkItemSubscriptionException : Exception()

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private val SubscriptionQuery =
    """
    query WorkItemSubscription(${'$'}id: ID!) {
        node(id: ${'$'}id) {
            __typename
            ... on Issue {
                id viewerSubscription viewerCanSubscribe
                repository { viewerSubscription viewerSubscriptionTypes }
            }
            ... on PullRequest {
                id viewerSubscription viewerCanSubscribe
                repository { viewerSubscription viewerSubscriptionTypes }
            }
        }
    }
    """
        .trimIndent()

// 与现有仓库订阅及官方 un60.java 使用同一 mutation；不带完成通知的组合操作。
private val SubscriptionMutation =
    """
    mutation WorkItemSubscriptionUpdate(${'$'}id: ID!, ${'$'}state: SubscriptionState!) {
        updateSubscription(input: {subscribableId: ${'$'}id, state: ${'$'}state}) {
            subscribable { id viewerSubscription }
        }
    }
    """
        .trimIndent()
