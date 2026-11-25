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
import moe.yumeyuka.yumehub.data.cache.tagDigest

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
    )

    suspend fun mark(account: String, tags: Set<CacheTag>, at: Long) {
        if (tags.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = marks[key] ?: load(key)
                val subjects = current.subjects.toMutableMap()
                val all = current.domainAll.toMutableMap()
                val anySubject = current.domainAnySubject.toMutableMap()
                tags.forEach { tag ->
                    subjects[tag.digest] = maxOf(subjects[tag.digest] ?: 0L, at)
                    if (tag.subject == null) {
                        all[tag.domain.name] = maxOf(all[tag.domain.name] ?: 0L, at)
                    } else {
                        anySubject[tag.domain.name] = maxOf(anySubject[tag.domain.name] ?: 0L, at)
                    }
                }
                val next =
                    Marks(
                        subjects = bounded(subjects),
                        domainAll = all,
                        domainAnySubject = anySubject,
                    )
                // encode 失败时保留旧标记，下次仍按旧（更严格）屏障判断过期。
                check(storage.encode(key, json.encodeToString(Marks.serializer(), next))) {
                    "Unable to persist cache invalidation"
                }
                marks[key] = next
            }
        }
    }

    /** 只持有磁盘标签摘要的恢复路径：按原摘要推进同一屏障。 */
    suspend fun markDigests(account: String, diskTags: List<String>, at: Long) {
        if (diskTags.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = marks[key] ?: load(key)
                val subjects = current.subjects.toMutableMap()
                val all = current.domainAll.toMutableMap()
                val anySubject = current.domainAnySubject.toMutableMap()
                diskTags.forEach { value ->
                    val domain = diskDomain(value)
                    val digest = diskDigest(value)
                    subjects[digest] = maxOf(subjects[digest] ?: 0L, at)
                    if (diskIsGlobal(value)) {
                        all[domain] = maxOf(all[domain] ?: 0L, at)
                    } else {
                        anySubject[domain] = maxOf(anySubject[domain] ?: 0L, at)
                    }
                }
                val next =
                    Marks(
                        subjects = bounded(subjects),
                        domainAll = all,
                        domainAnySubject = anySubject,
                    )
                check(storage.encode(key, json.encodeToString(Marks.serializer(), next))) {
                    "Unable to persist cache invalidation"
                }
                marks[key] = next
            }
        }
    }

    /** 任一相关标记晚于快照的 checkedAt 即视为过期。 */
    suspend fun stale(account: String, diskTags: List<String>, checkedAt: Long): Boolean {
        if (diskTags.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            val current =
                mutex.withLock {
                    val key = accountKey(account)
                    marks[key] ?: load(key).also { marks[key] = it }
                }
            diskTags.any { value ->
                val domain = diskDomain(value)
                val digest = diskDigest(value)
                val subjectMark = current.subjects[digest] ?: 0L
                val globalMark = current.domainAll[domain] ?: 0L
                val anySubject = current.domainAnySubject[domain] ?: 0L
                subjectMark > checkedAt ||
                    globalMark > checkedAt ||
                    (diskIsGlobal(value) && anySubject > checkedAt)
            }
        }
    }

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
            storage.decodeString(key)?.let { json.decodeFromString(Marks.serializer(), it) } ?: Marks()
        } catch (_: IllegalArgumentException) {
            Marks()
        }

    private fun bounded(subjects: Map<String, Long>): Map<String, Long> {
        if (subjects.size <= MaxSubjects) return subjects
        return subjects.entries.sortedByDescending { it.value }.take(MaxSubjects).associate { it.toPair() }
    }

    private fun accountKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(account.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return "marks:${digest.digest().toHexString()}"
    }

    private companion object {
        const val MaxSubjects = 1024
    }
}
