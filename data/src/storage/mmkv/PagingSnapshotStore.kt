package moe.yumeyuka.yumehub.data.storage.mmkv

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.cache.DataFreshness
import okio.utf8Size

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
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        updatedAt > 0L && now - updatedAt in 0 until DataFreshness.ActiveMillis
}

@Serializable
private data class SnapshotDiskEntry(
    val accessedAt: Long,
    val bytes: Int,
    /** 写入时的失效标签摘要；旧索引为空，按过期快照处理。 */
    val tags: List<String> = emptyList(),
)

/** 首屏快照只作为 PagingSource 的初始化数据，不额外发布一个 PagingData generation。 */
class PagingSnapshotStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getStorage("paging-snapshot") }
    private val lock = Any()
    private val memory = LinkedHashMap<String, Pair<Any, Long>>(16, 0.75f, true)
    private var memoryBytes = 0L
    private val memoryTags = mutableMapOf<String, List<String>>()
    private val prepared = mutableMapOf<String, String>()
    private val indexSerializer = MapSerializer(String.serializer(), SnapshotDiskEntry.serializer())
    private var diskBytes = 0L
    private val diskIndex by lazy {
        runCatching {
            storage.decodeString(DiskIndexKey)?.let { json.decodeFromString(indexSerializer, it) }
        }
            .getOrNull()
            .orEmpty()
            .toMutableMap()
            .also { index ->
                storage
                    .allKeys()
                    ?.filter { it.startsWith("v2:") && it !in index }
                    ?.forEach { key ->
                        index[key] = SnapshotDiskEntry(0L, storage.getValueActualSize(key))
                    }
                diskBytes = index.values.sumOf { it.bytes.toLong() }
            }
    }

    @Suppress("UNCHECKED_CAST")
    internal suspend fun <T : Any> loadPageNow(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        ensureCurrent: () -> Unit,
        stale: suspend (List<String>, Long) -> Boolean,
    ): PagingSnapshotPage<T>? {
        val storageKey = pageKey(account, key)
        val memoryKey = memoryKey(storageKey, serializer)
        val remembered = synchronized(memory) { memory[memoryKey]?.first as? PagingSnapshotPage<T> }
        if (remembered != null) {
            val tags = synchronized(memory) { memoryTags[memoryKey] }.orEmpty()
            if (tags.isEmpty()) return null
            if (stale(tags, remembered.updatedAt)) {
                synchronized(memory) {
                    memory.remove(memoryKey)?.let { memoryBytes -= it.second }
                }
                return null
            }
            return remembered
        }
        val raw =
            synchronized(lock) {
                ensureCurrent()
                prepare(account, key)
                val payload = storage.decodeString(storageKey) ?: return null
                diskIndex[storageKey]?.let {
                    diskIndex[storageKey] = it.copy(accessedAt = System.currentTimeMillis())
                }
                payload
            }
        // 不把解码结果写回内存：并发保存可能已发布更新的一页。
        val page = json.decodeFromString(PagingSnapshotPage.serializer(serializer), raw)
        val tags = synchronized(lock) { diskIndex[storageKey]?.tags }.orEmpty()
        // 旧快照没有标签，不能当作新鲜内容。
        if (tags.isEmpty() || stale(tags, page.updatedAt)) return null
        return page
    }

    internal fun <T : Any> savePageNow(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        page: PagingSnapshotPage<T>,
        tags: List<String>,
        publish: (() -> Unit) -> Unit,
    ) {
        val payload = json.encodeToString(PagingSnapshotPage.serializer(serializer), page)
        val bytes = payload.utf8Size()
        val storageKey = pageKey(account, key)
        val memoryKey = memoryKey(storageKey, serializer)
        synchronized(lock) {
            // 存储锁决定提交顺序；账号与分页锁只保护内存发布，不包围索引或 MMKV。
            publish {
                synchronized(memory) {
                    if (bytes <= MaxMemoryBytes) {
                        remember(memoryKey, page, bytes)
                        memoryTags[memoryKey] = tags
                    } else {
                        memory.remove(memoryKey)?.let { memoryBytes -= it.second }
                        memoryTags.remove(memoryKey)
                    }
                }
            }
            prepare(account, key)
            if (bytes <= MaxEntryBytes && storage.encode(storageKey, payload)) {
                putDiskEntry(storageKey, SnapshotDiskEntry(System.currentTimeMillis(), bytes.toInt(), tags))
                trimDisk()
            } else {
                storage.removeValueForKey(storageKey)
                removeDiskEntry(storageKey)
            }
            persistIndex()
        }
    }

    internal fun clearMemory(account: String) =
        synchronized(memory) {
            val prefix = "v2:${normalize(account)}:"
            val it = memory.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key.startsWith(prefix)) {
                    memoryBytes -= entry.value.second
                    it.remove()
                }
            }
            memoryTags.keys.removeAll { it.startsWith(prefix) }
        }

    internal fun sync() {
        storage.sync()
    }

    /** 仅在 IO 入口调用；同账号旧会话及未验证的旧版快照不参与本会话恢复。 */
    private fun prepare(account: String, key: String) {
        if (!key.startsWith("session:")) return
        val normalized = normalize(account)
        val session = key.substringAfter("session:").substringBefore(":revision:")
        val prefix = "v2:$normalized:session:$session:"
        if (prepared[normalized] == prefix) return
        val accountPrefix = "v2:$normalized:"
        val legacyPrefix = "v1:$normalized:"
        val obsolete =
            storage
                .allKeys()
                .orEmpty()
                .filter {
                    it.startsWith(legacyPrefix) ||
                        (it.startsWith(accountPrefix) && !it.startsWith(prefix))
                }
                .toSet()
        obsolete.forEach { obsoleteKey ->
            storage.removeValueForKey(obsoleteKey)
            removeDiskEntry(obsoleteKey)
        }
        prepared[normalized] = prefix
        trimDisk()
        persistIndex()
    }

    private fun putDiskEntry(key: String, entry: SnapshotDiskEntry) {
        val prev = diskIndex.put(key, entry)
        if (prev != null) diskBytes -= prev.bytes
        diskBytes += entry.bytes
    }

    private fun removeDiskEntry(key: String) {
        val removed = diskIndex.remove(key) ?: return
        diskBytes -= removed.bytes
    }

    private fun trimDisk() {
        if (diskIndex.size <= MaxDiskEntries && diskBytes <= MaxDiskBytes) return
        val oldest = diskIndex.entries.sortedBy { it.value.accessedAt }
        for ((key, _) in oldest) {
            if (diskIndex.size <= MaxDiskEntries && diskBytes <= MaxDiskBytes) break
            storage.removeValueForKey(key)
            removeDiskEntry(key)
        }
    }

    private fun persistIndex() {
        runCatching {
            storage.encode(DiskIndexKey, json.encodeToString(indexSerializer, diskIndex))
        }
    }

    private fun pageKey(account: String, key: String): String = "v2:${normalize(account)}:$key"

    private fun memoryKey(key: String, serializer: KSerializer<*>): String =
        "$key:${serializer.descriptor}"

    private fun remember(key: String, value: Any, bytes: Long) {
        memory.remove(key)?.let { memoryBytes -= it.second }
        memory[key] = value to bytes
        memoryBytes += bytes
        while (memory.size > 64 || memoryBytes > MaxMemoryBytes) {
            val oldestKey = memory.keys.firstOrNull() ?: break
            memory.remove(oldestKey)?.let { memoryBytes -= it.second }
            memoryTags.remove(oldestKey)
        }
    }

    private fun normalize(account: String): String = account.lowercase(Locale.ROOT)

    private companion object {
        const val DiskIndexKey = "v2-lru-index"
        const val MaxDiskEntries = 128
        const val MaxDiskBytes = 8L * 1024 * 1024
        const val MaxMemoryBytes = 8L * 1024 * 1024
        const val MaxEntryBytes = 2 * 1024 * 1024
    }
}
