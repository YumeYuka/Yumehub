package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.utf8Size

/** 专用响应存储；所有序列化、MMKV 访问与淘汰都只在 IO 调度器执行。 */
internal class ResponseCacheStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getMMKV("response-cache-v1") }
    private val mutex = Mutex()
    private var indexedSession: String? = null
    private val entries = mutableMapOf<String, Metadata>()

    internal data class Response(val payload: String, val updatedAt: Long)

    @Serializable
    private data class Entry(
        val session: String,
        val payload: String,
        val updatedAt: Long,
        val accessedAt: Long,
    )

    @Serializable
    private data class DiskIndex(val session: String, val accessedAt: Map<String, Long>)

    private data class Metadata(val bytes: Int, val accessedAt: Long)

    suspend fun load(session: String, key: String, valid: () -> Boolean): Response? =
        withContext(Dispatchers.IO) {
            if (!valid()) return@withContext null
            val entry = storage.decodeString(key)?.let(::decode) ?: return@withContext null
            if (entry.session != session || !valid()) return@withContext null
            // 命中只更新内存淘汰权重，不等待索引扫描或重写响应体。
            if (mutex.tryLock()) {
                try {
                    if (indexedSession == session)
                        entries[key]?.let {
                            entries[key] = it.copy(accessedAt = System.currentTimeMillis())
                        }
                } finally {
                    mutex.unlock()
                }
            }
            Response(entry.payload, entry.updatedAt)
        }

    suspend fun save(
        session: String,
        key: String,
        payload: String,
        updatedAt: Long,
        valid: () -> Boolean,
    ) =
        withContext(Dispatchers.IO) {
            if (!valid()) return@withContext
            val entry = Entry(session, payload, updatedAt, System.currentTimeMillis())
            val raw =
                if (payload.length <= MaxEntryBytes) json.encodeToString(Entry.serializer(), entry)
                else null
            val bytes = raw?.utf8Size() ?: 0L
            mutex.withLock {
                if (!valid()) return@withLock
                prepare(session)
                if (!valid()) return@withLock
                // 超限新值也应移除旧快照，避免下次恢复成过时内容。
                if (raw == null || bytes > MaxEntryBytes) remove(key)
                else writeRaw(key, entry, raw, bytes.toInt())
                trim()
                persistIndex(session)
            }
        }

    suspend fun invalidate(session: String, key: String, valid: () -> Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!valid()) return@withLock
                prepare(session)
                val entry = storage.decodeString(key)?.let(::decode) ?: return@withLock
                if (entry.session == session) write(key, entry.copy(updatedAt = 0L))
            }
        }

    suspend fun clearOtherSessions(session: String, valid: () -> Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock { if (valid()) prepare(session) } }

    private fun prepare(session: String) {
        if (indexedSession == session) return
        if (indexedSession == null) {
            val index =
                storage.decodeString(IndexKey)?.let { raw ->
                    try {
                        json.decodeFromString(DiskIndex.serializer(), raw)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            // 磁盘键的摘要包含会话。索引落盘前进程退出时，仅未登记的键需要读正文。
            for (key in storage.allKeys().orEmpty()) {
                if (key == IndexKey) continue
                val bytes = storage.getValueActualSize(key)
                val known = index?.accessedAt?.get(key)
                val accessedAt =
                    when {
                        bytes !in 1..MaxEntryBytes -> null
                        known != null -> known.takeIf { index.session == session }
                        else ->
                            storage
                                .decodeString(key)
                                ?.let(::decode)
                                ?.takeIf { it.session == session }
                                ?.accessedAt
                    }
                if (accessedAt == null) remove(key) else entries[key] = Metadata(bytes, accessedAt)
            }
        } else {
            storage.removeValuesForKeys(entries.keys.toTypedArray())
            entries.clear()
        }
        trim()
        persistIndex(session)
        indexedSession = session
    }

    private fun persistIndex(session: String) {
        val index = DiskIndex(session, entries.mapValues { it.value.accessedAt })
        storage.encode(IndexKey, json.encodeToString(DiskIndex.serializer(), index))
    }

    private fun decode(raw: String): Entry? =
        try {
            json.decodeFromString(Entry.serializer(), raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun write(key: String, entry: Entry) {
        val raw = json.encodeToString(Entry.serializer(), entry)
        writeRaw(key, entry, raw, raw.utf8Size().toInt())
    }

    private fun writeRaw(key: String, entry: Entry, raw: String, bytes: Int) {
        check(storage.encode(key, raw)) { "Unable to persist response cache" }
        entries[key] = Metadata(bytes, entry.accessedAt)
    }

    private fun trim() {
        var bytes = entries.values.sumOf { it.bytes.toLong() }
        if (entries.size <= MaxEntries && bytes <= MaxBytes) return
        val oldest = entries.entries.sortedBy { it.value.accessedAt }.iterator()
        while ((entries.size > MaxEntries || bytes > MaxBytes) && oldest.hasNext()) {
            val entry = oldest.next()
            bytes -= entry.value.bytes
            remove(entry.key)
        }
    }

    private fun remove(key: String) {
        storage.removeValueForKey(key)
        entries.remove(key)
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
