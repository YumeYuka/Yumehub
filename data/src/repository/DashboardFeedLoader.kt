package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.cache.*

/**
 * Per-session feed queries never join or submit a write. Each response carries its own filter
 * evidence.
 */
internal class DashboardFeedLoader {
    private val remote = MutableStateFlow<Set<String>?>(null)
    val filters = remote.asStateFlow()
    val filterKey = MutationKey(MutationKind.FEED_FILTERS, "viewer", CommandAccepted.serializer())
    private var sequence = 0L

    @Synchronized private fun begin() = ++sequence

    @Synchronized
    private fun publish(stamp: Long, values: Map<String, Boolean>) {
        if (sequence == stamp)
            remote.value = values.filterValues { it }.keys.intersect(ManagedFeedGroups)
    }

    suspend fun load(
        after: String?,
        execute: suspend (GraphQlRequest) -> GraphQlResponse,
    ): GraphQlResponse {
        val stamp = begin()
        val result =
            execute(
                GraphQlRequest(
                    DashboardFeedQuery,
                    buildJsonObject {
                        put("first", 25)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                    "Feed",
                ),
            )
        val values = result.feed().filters(result)
        // Publish the account's real filters. A mismatch does not discard the page: the caller
        // syncs the selection to the account and invalidates the feed afterwards, so switching
        // accounts never leaves the feed stuck on a filter error.
        publish(stamp, values)
        return result
    }

    fun apply(
        groups: Set<String>,
        coord: MutationCoordinator,
        execute: suspend (GraphQlRequest, Boolean) -> GraphQlResponse,
    ): Boolean {
        require(groups.isNotEmpty() && ManagedFeedGroups.containsAll(groups))
        suspend fun readFilters(): Map<String, Boolean> {
            val stamp = begin()
            val result =
                execute(
                    GraphQlRequest(
                        "query FeedFilters { viewer { dashboard { feed { filters { filterGroup isEnabled } } } } }",
                        buildJsonObject {},
                        "FeedFilters",
                    ),
                    false,
                )
            if (result.errors.isNotEmpty()) throw DashboardFeedException(result.errors)
            return result.feed().filters(result).also { publish(stamp, it) }
        }
        return coord.submitCommand(
            filterKey,
            setOf(CacheTag(DataDomain.Profile, "viewer:feed-filters")),
            send = {
                val current = readFilters()
                if (!current.matches(groups)) {
                    val enabled = current.filterValues { it }.keys - ManagedFeedGroups + groups
                    val result =
                        execute(
                            GraphQlRequest(
                                DashboardFeedFiltersMutation,
                                buildJsonObject {
                                    putJsonArray("filterGroups") {
                                        enabled.sorted().forEach { add(it) }
                                    }
                                },
                                "UpdateFeedFilters",
                            ),
                            true,
                        )
                    result.checkWriteErrors()
                    val values =
                        ((result.data as? JsonObject)?.get("setDashboardFeedFilters")
                                as? JsonObject)
                            ?.filters(result) ?: throw DashboardFeedException()
                    publish(begin(), values)
                    if (!values.matches(groups)) throw DashboardFeedException()
                }
                CommandAccepted
            },
            readBack = {
                readFilters()
                null
            },
        ) != null
    }
}
