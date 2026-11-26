package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only an indexed immutable body belongs to a session. Both snapshot stores use this format. */
internal class ResponseCacheStore(
    provider: MMKVProvider,
    private val json: Json,
    storageId: String = "response-cache-v1",
    private val maxBytes: Long = 16L * 1024 * 1024,
) {
    private val storage by lazy { provider.getStorage(storageId) }
    private val mutex = Mutex()
    private var indexedSession: String? = null
    private val entries = LinkedHashMap<String, Metadata>(128, 0.75f, true)
    private var totalBytes = 0L

    internal data class Response(
        val payload: String, val updatedAt: Long, val etag: String?, val checkedAt: Long,
        val tags: List<String>, val bytes: Int,
    )

    @Serializable
    private data class Metadata(
        val body: String, val bytes: Int, val updatedAt: Long, val checkedAt: Long,
        val etag: String?, val tags: List<String>,
    )

    @Serializable
    private data class Index(val session: String, val records: Map<String, Metadata>)

    suspend fun load(session: String, key: String, valid: () -> Boolean): Response? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!valid()) return@withLock null
                prepare(session)
                val meta = entries[key] ?: return@withLock null
                val payload = storage.decodeString(meta.body) ?: return@withLock null
                if (!valid()) return@withLock null
                Response(payload, meta.updatedAt, meta.etag, meta.checkedAt, meta.tags, meta.bytes)
            }
        }

    suspend fun save(
        session: String, key: String, payload: String, bytes: Long, updatedAt: Long,
        checkedAt: Long, etag: String?, tags: List<String>, valid: () -> Boolean,
    ) = mutex.withLock {
        if (!valid()) return@withLock
        prepare(session)
        val old = entries[key]
        val body = if (bytes <= MaxEntryBytes) "$key:${UUID.randomUUID()}" else null
        if (body != null) check(storage.encode(body, payload)) { "Snapshot body write failed" }
        if (!valid()) {
            body?.let(storage::removeValueForKey)
            return@withLock
        }
        val retired = ArrayList<String>()
        old?.let { totalBytes -= it.bytes; retired.add(it.body) }
        if (body == null) entries.remove(key) else {
            entries[key] = Metadata(body, bytes.toInt(), updatedAt, checkedAt, etag, tags)
            totalBytes += bytes
        }
        val iterator = entries.entries.iterator()
        while (entries.size > MaxEntries || totalBytes > maxBytes) {
            val victim = iterator.next().value
            totalBytes -= victim.bytes
            retired.add(victim.body)
            iterator.remove()
        }
        try {
            commit(session)
        } catch (error: Exception) {
            body?.let(storage::removeValueForKey)
            throw error
        }
        retired.forEach(storage::removeValueForKey)
    }

    suspend fun touch(session: String, key: String, checkedAt: Long, etag: String?, valid: () -> Boolean): Boolean = mutex.withLock {
        if (!valid()) return@withLock true
        prepare(session)
        val old = entries[key] ?: return@withLock false
        if (old.checkedAt != checkedAt || old.etag != etag) {
            entries[key] = old.copy(checkedAt = checkedAt, etag = etag)
            commit(session)
        }
        true
    }

    suspend fun invalidate(session: String, key: String, valid: () -> Boolean) = mutex.withLock {
        if (!valid()) return@withLock
        prepare(session)
        val old = entries[key] ?: return@withLock
        entries[key] = old.copy(checkedAt = 0L)
        commit(session)
    }

    suspend fun clearOtherSessions(session: String, valid: () -> Boolean) = mutex.withLock {
        if (valid()) prepare(session)
    }

    fun sync() = storage.sync()

    private fun prepare(session: String) {
        if (indexedSession == session) return
        val index = storage.decodeString(IndexKey)?.let { raw ->
            try { json.decodeFromString(Index.serializer(), raw) } catch (_: IllegalArgumentException) { null }
        }
        entries.clear()
        totalBytes = 0L
        if (index?.session == session) index.records.forEach { (key, meta) ->
            if (meta.body.startsWith("$key:") && meta.bytes in 1..MaxEntryBytes && storage.getValueActualSize(meta.body) > 0) {
                entries[key] = meta
                totalBytes += meta.bytes
            }
        }
        val kept = entries.values.mapTo(HashSet()) { it.body }
        // Never adopt orphaned bodies (including the old random-namespace format).
        storage.allKeys()?.forEach { if (it != IndexKey && it !in kept) storage.removeValueForKey(it) }
        indexedSession = session
    }

    private fun commit(session: String) {
        try {
            check(storage.encode(IndexKey, json.encodeToString(Index.serializer(), Index(session, entries)))) {
                "Snapshot index write failed"
            }
        } catch (error: Exception) {
            // Restore from the last committed pointer on the next access.
            indexedSession = null
            throw error
        }
    }
    companion object {
        private const val IndexKey = "snapshot-index-v3"
        private const val MaxEntries = 128
        private const val MaxEntryBytes = 2 * 1024 * 1024

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
