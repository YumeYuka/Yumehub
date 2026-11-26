package moe.yumeyuka.yumehub.data.storage.mmkv

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.cache.DataFreshness
import okio.utf8Size
import moe.yumeyuka.yumehub.data.cache.CacheWriteQueue

/** 保存实际分页边界；不能从经过过滤、去重后的条数推断下一页。 */
@Serializable
data class PagingSnapshotPage<T>(
    val items: List<T>,
    val prevKey: Int?,
    val nextKey: Int?,
    val pageSize: Int,
    val updatedAt: Long,
    val page: Int = 1,
    val totalCount: Int? = null,
    val incomplete: Boolean? = null,
    val lastKey: Int? = null,
    val requestCursor: String? = null,
    val nextCursor: String? = null,
    val boundaryInvalid: Boolean = false,
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        !boundaryInvalid && updatedAt > 0L && now - updatedAt in 0 until DataFreshness.ActiveMillis
}

class PagingSnapshotStore(provider: MMKVProvider, private val json: Json) {
    internal val disk = ResponseCacheStore(provider, json, "paging-snapshot", 8L * 1024 * 1024)

    internal suspend fun <T : Any> load(
        session: String, key: String, serializer: KSerializer<T>, revision: Long, valid: () -> Boolean,
    ): PagingSnapshotPage<T>? {
        val response = disk.load(session, key, valid) ?: return null
        val page = json.decodeFromString(PagingSnapshotPage.serializer(serializer), response.payload)
        return if (response.tags.isEmpty() || response.checkedAt != revision) page.copy(boundaryInvalid = true) else page
    }

    internal fun <T : Any> save(
        queue: CacheWriteQueue, session: String, key: String, serializer: KSerializer<T>,
        page: PagingSnapshotPage<T>, tags: List<String>, revision: Long, valid: () -> Boolean,
    ) {
        queue.snapshot("page:$key") {
            if (!valid()) return@snapshot null
            val payload = json.encodeToString(PagingSnapshotPage.serializer(serializer), page)
            val bytes = payload.utf8Size()
            suspend { disk.save(session, key, payload, bytes, page.updatedAt, revision, null, tags, valid) }
        }
    }

    internal fun sync() = disk.sync()
}
