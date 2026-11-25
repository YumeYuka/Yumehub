package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase

/** 仅串行服务器筛选与 Feed 请求；已确认的筛选按登录代际保留，切换时省掉一次旧筛选全文查询。 */
internal class DashboardFeedLoader {
    private val lock = Mutex()
    private var generation: Long? = null
    private var knownFilters: Map<String, Boolean>? = null
    private val filterKey =
        MutationKey(MutationKind.FEED_FILTERS, "viewer", CommandAccepted.serializer())

    suspend fun load(
        session: Long,
        after: String?,
        groups: Set<String>,
        coordinator: MutationCoordinator? = null,
        execute: suspend (GraphQlRequest, Boolean) -> GraphQlResponse,
    ): GraphQlResponse = lock.withLock {
        require(groups.isNotEmpty() && ManagedFeedGroups.containsAll(groups))
        if (generation != session) {
            generation = session
            knownFilters = null
        }
        suspend fun query(): GraphQlResponse =
            execute(
                GraphQlRequest(
                    DashboardFeedQuery,
                    buildJsonObject {
                        put("first", 25)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                    "Feed",
                ),
                false,
            )
        suspend fun sendFilterMutation(remote: Map<String, Boolean>) {
            val enabled = remote.filterValues { it }.keys - ManagedFeedGroups + groups
            knownFilters = null
            val response =
                execute(
                    GraphQlRequest(
                        DashboardFeedFiltersMutation,
                        buildJsonObject {
                            put("filterGroups", JsonArray(enabled.sorted().map(::JsonPrimitive)))
                        },
                        "UpdateFeedFilters",
                    ),
                    true,
                )
            if (response.errors.isNotEmpty()) throw DashboardFeedException(response.errors)
            val payload =
                (response.data as? JsonObject)?.get("setDashboardFeedFilters") as? JsonObject
                    ?: throw DashboardFeedException(response.errors)
            val updated = payload.filters(response)
            if (!updated.matches(groups)) throw DashboardFeedException(response.errors)
            knownFilters = updated
        }
        suspend fun synchronizeFilters(remote: Map<String, Boolean>) {
            val coord = coordinator
            if (coord == null) {
                sendFilterMutation(remote)
                return
            }
            // 上次结果未知时先重读服务器筛选状态（不依赖 cached knownFilters），再按最新非受管筛选集合合并写入。
            if (coord.currentCommand(filterKey).phase == WritePhase.UNCERTAIN) {
                knownFilters = null
                val fresh = query().feed().filters(GraphQlResponse())
                val opId =
                    coord.pending.value.firstOrNull {
                        it.kind == MutationKind.FEED_FILTERS && it.target == filterKey.target
                    }?.operationId
                if (opId != null) coord.acknowledge(opId).join()
                if (fresh.matches(groups)) {
                    knownFilters = fresh
                    return
                }
            }
            var capturedError: Exception? = null
            val accepted =
                coord.submitCommand(
                    key = filterKey,
                    tags =
                        setOf(
                            CacheTag(DataDomain.Profile, "viewer:feed-filters"),
                        ),
                    send = {
                        try {
                            sendFilterMutation(remote)
                            CommandAccepted
                        } catch (error: Exception) {
                            capturedError = error
                            throw error
                        }
                    },
                    readBack = { null },
                )
            if (!accepted) {
                knownFilters = null
                throw DashboardFeedException()
            }
            val terminal = coord.observeCommand(filterKey).first { it.phase != WritePhase.PENDING }
            if (terminal.phase != WritePhase.IDLE) {
                knownFilters = null
                throw capturedError ?: DashboardFeedException()
            }
        }
        if (coordinator?.currentCommand(filterKey)?.phase == WritePhase.UNCERTAIN) {
            knownFilters = null
        }
        knownFilters?.takeUnless { it.matches(groups) }?.let { synchronizeFilters(it) }
        var response = query()
        var remote = response.feed().filters(response)
        if (!remote.matches(groups)) {
            synchronizeFilters(remote)
            response = query()
            remote = response.feed().filters(response)
            if (!remote.matches(groups)) throw DashboardFeedException(response.errors)
        }
        knownFilters = remote
        response
    }
}
