package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.dto.*

/** 仅串行服务器筛选与 Feed 请求；已确认的筛选按登录代际保留，切换时省掉一次旧筛选全文查询。 */
internal class DashboardFeedLoader {
    private val lock = Mutex()
    private var generation: Long? = null
    private var knownFilters: Map<String, Boolean>? = null

    suspend fun load(
        session: Long,
        after: String?,
        groups: Set<String>,
        execute: suspend (GraphQlRequest, Boolean) -> GraphQlResponse,
    ): GraphQlResponse = lock.withLock {
        require(groups.isNotEmpty() && ManagedFeedGroups.containsAll(groups))
        if (generation != session) {
            generation = session
            knownFilters = null
        }
        suspend fun synchronizeFilters(remote: Map<String, Boolean>) {
            val enabled = remote.filterValues { it }.keys - ManagedFeedGroups + groups
            // 写操作被取消或结果不明时，下一次必须重读真实服务器状态。
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
        knownFilters?.takeUnless { it.matches(groups) }?.let { synchronizeFilters(it) }
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
