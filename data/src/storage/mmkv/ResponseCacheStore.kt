package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.utf8Size

/** 专用响应存储；每次写新不可变正文，再原子替换索引指针并清理孤儿正文。 */
internal class ResponseCacheStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getStorage("response-cache-v1") }
    private val mutex = Mutex()
    private var indexedSession: String? = null
    private val entries = mutableMapOf<String, Metadata>()
    private var totalBytes = 0L

    internal data class Response(
        val payload: String,
        val updatedAt: Long,
        val etag: String?,
        val checkedAt: Long,
        val tags: List<String>?,
    )

    @Serializable
    private data class Entry(
        val session: String,
        val payload: String,
        val updatedAt: Long,
        val accessedAt: Long,
        // 与 payload 为同一份服务端表示的校验符；旧快照没有该字段，解码为 null。
        val etag: String? = null,
        // 最近一次成功校验的时间；旧快照缺省时以 updatedAt 作为检查锚点。
        val checkedAt: Long = 0L,
        // 写入时的失效标签摘要。旧快照为 null，按缓存未命中处理。
        val tags: List<String>? = null,
    )

    @Serializable
    private data class DiskIndexEntry(
        val bodyKey: String,
        val accessedAt: Long,
        val bytes: Int = 0,
    )

    @Serializable
    private data class DiskIndex(
        val session: String,
        val accessedAt: Map<String, Long> = emptyMap(),
        val records: Map<String, DiskIndexEntry> = emptyMap(),
    )

    private data class Metadata(val bodyKey: String, val bytes: Int, val accessedAt: Long)

    suspend fun load(session: String, key: String, valid: () -> Boolean): Response? =
        withContext(Dispatchers.IO) {
            if (!valid()) return@withContext null
            val bodyKey =
                if (mutex.tryLock()) {
                    try {
                        if (indexedSession == session) {
                            entries[key]?.let {
                                entries[key] = it.copy(accessedAt = System.currentTimeMillis())
                                it.bodyKey
                            }
                        } else null
                    } finally {
                        mutex.unlock()
                    }
                } else null
            val resolvedKey =
                bodyKey
                    ?: storage.decodeString(IndexKey)?.let(::decodeIndex)?.takeIf {
                        it.session == session
                    }?.records?.get(key)?.bodyKey
                    ?: key
            val entry = storage.decodeString(resolvedKey)?.let(::decode) ?: return@withContext null
            if (entry.session != session || !valid()) return@withContext null
            Response(entry.payload, entry.updatedAt, entry.etag, entry.checkedAt, entry.tags)
        }

    suspend fun save(
        session: String,
        key: String,
        payload: String,
        updatedAt: Long,
        checkedAt: Long,
        etag: String?,
        tags: List<String>,
        valid: () -> Boolean,
    ) =
        withContext(Dispatchers.IO) {
            if (!valid()) return@withContext
            val entry =
                Entry(
                    session,
                    payload,
                    updatedAt,
                    System.currentTimeMillis(),
                    etag,
                    checkedAt,
                    tags,
                )
            val raw =
                if (payload.length <= MaxEntryBytes) json.encodeToString(Entry.serializer(), entry)
                else null
            val bytes = raw?.utf8Size() ?: 0L
            val newBodyKey = if (raw != null && bytes <= MaxEntryBytes) "$key:${UUID.randomUUID()}" else null
            if (newBodyKey != null && raw != null) {
                check(storage.encode(newBodyKey, raw)) { "Unable to persist response cache body" }
            }
            var oldBodyKeyToRemove: String? = null
            try {
                mutex.withLock {
                    if (!valid()) {
                        newBodyKey?.let(storage::removeValueForKey)
                        return@withLock
                    }
                    prepare(session)
                    if (!valid()) {
                        newBodyKey?.let(storage::removeValueForKey)
                        return@withLock
                    }
                    oldBodyKeyToRemove = entries[key]?.bodyKey
                    if (newBodyKey == null) {
                        removeMetadata(key)
                    } else {
                        putMetadata(key, Metadata(newBodyKey, bytes.toInt(), entry.accessedAt))
                    }
                    trim()
                    persistIndex(session)
                }
            } catch (error: Throwable) {
                newBodyKey?.let { runCatching { storage.removeValueForKey(it) } }
                throw error
            }
            oldBodyKeyToRemove?.takeIf { it != newBodyKey }?.let { storage.removeValueForKey(it) }
        }

    suspend fun invalidate(session: String, key: String, valid: () -> Boolean) =
        withContext(Dispatchers.IO) {
            var oldBodyKey: String? = null
            var newBodyKey: String? = null
            mutex.withLock {
                if (!valid()) return@withLock
                prepare(session)
                val existing = entries[key]
                val readKey = existing?.bodyKey ?: key
                val entry = storage.decodeString(readKey)?.let(::decode) ?: return@withLock
                if (entry.session != session) return@withLock
                val updated = entry.copy(updatedAt = 0L)
                val raw = json.encodeToString(Entry.serializer(), updated)
                val bytes = raw.utf8Size().toInt()
                val nextKey = "$key:${UUID.randomUUID()}"
                check(storage.encode(nextKey, raw)) { "Unable to persist invalidated response" }
                newBodyKey = nextKey
                oldBodyKey = existing?.bodyKey ?: key.takeIf { it != nextKey }
                putMetadata(key, Metadata(nextKey, bytes, updated.accessedAt))
                trim()
                persistIndex(session)
            }
            oldBodyKey?.takeIf { it != newBodyKey }?.let { storage.removeValueForKey(it) }
        }

    suspend fun clearOtherSessions(session: String, valid: () -> Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock { if (valid()) prepare(session) } }

    fun sync() {
        storage.sync()
    }

    private fun prepare(session: String) {
        if (indexedSession == session) return
        if (indexedSession == null) {
            val index = storage.decodeString(IndexKey)?.let(::decodeIndex)
            entries.clear()
            totalBytes = 0L
            val allKeys = storage.allKeys().orEmpty().toSet()
            val orphans = mutableListOf<String>()
            if (index != null && index.session == session && index.records.isNotEmpty()) {
                val referencedBodies = HashSet<String>(index.records.size * 2)
                for ((key, record) in index.records) {
                    if (record.bodyKey in allKeys && record.bytes in 1..MaxEntryBytes) {
                        putMetadata(key, Metadata(record.bodyKey, record.bytes, record.accessedAt))
                        referencedBodies.add(record.bodyKey)
                    }
                }
                for (diskKey in allKeys) {
                    if (diskKey != IndexKey && diskKey !in referencedBodies) {
                        orphans.add(diskKey)
                    }
                }
            } else {
                // 旧版索引或无索引：只保留当前 session 的单键记录，清理旧会话及孤儿正文。
                for (key in allKeys) {
                    if (key == IndexKey) continue
                    val bytes = storage.getValueActualSize(key)
                    val known = index?.accessedAt?.get(key)
                    val accessedAt =
                        when {
                            bytes !in 1..MaxEntryBytes -> null
                            known != null -> known.takeIf { index?.session == session }
                            else ->
                                storage
                                    .decodeString(key)
                                    ?.let(::decode)
                                    ?.takeIf { it.session == session }
                                    ?.accessedAt
                        }
                    if (accessedAt == null) orphans.add(key)
                    else {
                        val logicalKey = key.substringBefore(':')
                        putMetadata(logicalKey, Metadata(key, bytes, accessedAt))
                    }
                }
            }
            if (orphans.isNotEmpty()) storage.removeValuesForKeys(orphans.toTypedArray())
        } else {
            val keysToDelete =
                (entries.values.map { it.bodyKey } + entries.keys).distinct().toTypedArray()
            if (keysToDelete.isNotEmpty()) storage.removeValuesForKeys(keysToDelete)
            entries.clear()
            totalBytes = 0L
        }
        trim()
        persistIndex(session)
        indexedSession = session
    }

    private fun persistIndex(session: String) {
        val records =
            entries.mapValues { (_, meta) ->
                DiskIndexEntry(meta.bodyKey, meta.accessedAt, meta.bytes)
            }
        val index = DiskIndex(session = session, records = records)
        storage.encode(IndexKey, json.encodeToString(DiskIndex.serializer(), index))
    }

    private fun decodeIndex(raw: String): DiskIndex? =
        try {
            json.decodeFromString(DiskIndex.serializer(), raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun decode(raw: String): Entry? =
        try {
            json.decodeFromString(Entry.serializer(), raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun putMetadata(key: String, meta: Metadata) {
        val previous = entries.put(key, meta)
        if (previous != null) totalBytes -= previous.bytes
        totalBytes += meta.bytes
    }

    private fun removeMetadata(key: String) {
        val removed = entries.remove(key) ?: return
        totalBytes -= removed.bytes
        storage.removeValueForKey(removed.bodyKey)
    }

    private fun trim() {
        if (entries.size <= MaxEntries && totalBytes <= MaxBytes) return
        val oldest = entries.entries.sortedBy { it.value.accessedAt }.iterator()
        while ((entries.size > MaxEntries || totalBytes > MaxBytes) && oldest.hasNext()) {
            val entry = oldest.next()
            removeMetadata(entry.key)
        }
    }

    companion object {
        private const val IndexKey = "response-index-v1"
        private const val MaxEntries = 128
        private const val MaxEntryBytes = 2 * 1024 * 1024
        private const val MaxBytes = 16L * 1024 * 1024

        // 长度前缀避免分隔符碰撞；磁盘键不暴露账号、URL 或资源名。
        fun key(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                digest.update("${part.length}:".toByteArray(Charsets.UTF_8))
                digest.update(part.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().toHexString()
        }
    }
}
