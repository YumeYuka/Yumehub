package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.diskDigest
import moe.yumeyuka.yumehub.data.cache.diskDomain
import moe.yumeyuka.yumehub.data.cache.diskIsGlobal
import moe.yumeyuka.yumehub.data.cache.diskValue

/**
 * 会话失效屏障的持久标记。快照按写入时的标签与 checkedAt 比对；只要相关标记更新，正文只能作为过期内容显示并强制校验。
 *
 * 标记只保存 domain 名、subject 摘要和时间，不保存账号、token 或正文。
 */
internal class CacheInvalidationStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getStorage("cache-invalidation-v1") }
    private val mutex = Mutex()
    private val marks = mutableMapOf<String, Marks>()

    @Serializable
    private data class Marks(
        val subjects: Map<String, Long> = emptyMap(),
        val domainAll: Map<String, Long> = emptyMap(),
        val domainAnySubject: Map<String, Long> = emptyMap(),
        val revision: Long = 0L,
    )

    suspend fun mark(account: String, tags: Set<CacheTag>, at: Long) =
        markDigests(account, tags.map { it.diskValue() }, at)

    /** 只持有磁盘标签摘要的恢复路径：按原摘要推进同一屏障。 */
    suspend fun markDigests(account: String, diskTags: List<String>, at: Long) {
        if (diskTags.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = marks[key] ?: load(key)
                val version = maxOf(at, current.revision + 1L)
                val subjects = current.subjects.toMutableMap()
                val all = current.domainAll.toMutableMap()
                val anySubject = current.domainAnySubject.toMutableMap()
                diskTags.forEach { value ->
                    val domain = diskDomain(value)
                    val digest = diskDigest(value)
                    subjects[digest] = version
                    if (diskIsGlobal(value)) {
                        all[domain] = version
                    } else {
                        anySubject[domain] = version
                    }
                }
                val next =
                    Marks(
                        subjects = subjects,
                        domainAll = all,
                        domainAnySubject = anySubject,
                        revision = version,
                    )
                check(storage.encode(key, json.encodeToString(Marks.serializer(), next))) {
                    "Unable to persist cache invalidation"
                }
                marks[key] = next
            }
        }
    }

    suspend fun stale(account: String, diskTags: List<String>, checkedAt: Long): Boolean =
        revision(account, diskTags) >= checkedAt

    suspend fun revision(account: String, diskTags: List<String>): Long =
        withContext(Dispatchers.IO) {
            val current = mutex.withLock {
                val key = accountKey(account)
                marks[key] ?: load(key).also { marks[key] = it }
            }
            diskTags.maxOfOrNull { value ->
                val domain = diskDomain(value)
                val digest = diskDigest(value)
                val subjectMark = current.subjects[digest] ?: 0L
                val globalMark = current.domainAll[domain] ?: 0L
                val anySubject = current.domainAnySubject[domain] ?: 0L
                maxOf(subjectMark, globalMark, if (diskIsGlobal(value)) anySubject else 0L)
            } ?: 0L
        }

    fun sync() = storage.sync()

    suspend fun clearAccount(account: String) {
        withContext(Dispatchers.IO) {
            val key = accountKey(account)
            mutex.withLock {
                storage.removeValueForKey(key)
                marks.remove(key)
            }
        }
    }

    private fun load(key: String): Marks =
        try {
            storage.decodeString(key)?.let { json.decodeFromString(Marks.serializer(), it) }
                ?: Marks()
        } catch (_: IllegalArgumentException) {
            Marks()
        }

    private fun accountKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(account.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return "marks:${digest.digest().toHexString()}"
    }
}
