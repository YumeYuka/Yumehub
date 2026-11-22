package moe.yumeyuka.yumehub.data.storage.mmkv

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
)

@Serializable private data class SnapshotDiskEntry(val accessedAt: Long, val bytes: Int)

/** 首屏快照只作为 PagingSource 的初始化数据，不额外发布一个 PagingData generation。 */
class PagingSnapshotStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getMMKV("paging-snapshot") }
    private val lock = Any()
    private val memory = LinkedHashMap<String, Any>(16, 0.75f, true)
    private val prepared = mutableMapOf<String, String>()
    private val indexSerializer = MapSerializer(String.serializer(), SnapshotDiskEntry.serializer())
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
            }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> loadPageNow(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        ensureCurrent: () -> Unit,
    ): PagingSnapshotPage<T>? {
        val storageKey = pageKey(account, key)
        val memoryKey = memoryKey(storageKey, serializer)
        synchronized(memory) { memory[memoryKey] as? PagingSnapshotPage<T> }
            ?.let {
                return it
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
        return json.decodeFromString(PagingSnapshotPage.serializer(serializer), raw)
    }

    internal fun <T : Any> savePageNow(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        page: PagingSnapshotPage<T>,
        publish: (() -> Unit) -> Unit,
    ) {
        val payload = json.encodeToString(PagingSnapshotPage.serializer(serializer), page)
        val bytes = payload.utf8Size()
        val storageKey = pageKey(account, key)
        val memoryKey = memoryKey(storageKey, serializer)
        synchronized(lock) {
            // 存储锁决定提交顺序；账号与分页锁只保护内存发布，不包围索引或 MMKV。
            publish { synchronized(memory) { remember(memoryKey, page) } }
            prepare(account, key)
            if (bytes <= MaxEntryBytes && storage.encode(storageKey, payload)) {
                diskIndex[storageKey] = SnapshotDiskEntry(System.currentTimeMillis(), bytes.toInt())
                trimDisk()
            } else {
                storage.removeValueForKey(storageKey)
                diskIndex.remove(storageKey)
            }
            persistIndex()
        }
    }

    internal fun clearMemory(account: String) =
        synchronized(memory) {
            val prefix = "v2:${normalize(account)}:"
            memory.keys.removeAll { it.startsWith(prefix) }
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
        obsolete.forEach(storage::removeValueForKey)
        diskIndex.keys.removeAll { it in obsolete }
        prepared[normalized] = prefix
        trimDisk()
        persistIndex()
    }

    private fun trimDisk() {
        var bytes = diskIndex.values.sumOf { it.bytes.toLong() }
        if (diskIndex.size <= MaxDiskEntries && bytes <= MaxDiskBytes) return
        val oldest = diskIndex.entries.sortedBy { it.value.accessedAt }
        for ((key, entry) in oldest) {
            if (diskIndex.size <= MaxDiskEntries && bytes <= MaxDiskBytes) break
            storage.removeValueForKey(key)
            diskIndex.remove(key)
            bytes -= entry.bytes
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

    private fun remember(key: String, value: Any) {
        memory[key] = value
        while (memory.size > 64) memory.remove(memory.keys.first())
    }

    private fun normalize(account: String): String = account.lowercase(Locale.ROOT)

    private companion object {
        const val DiskIndexKey = "v2-lru-index"
        const val MaxDiskEntries = 128
        const val MaxDiskBytes = 8L * 1024 * 1024
        const val MaxEntryBytes = 2 * 1024 * 1024
    }
}
