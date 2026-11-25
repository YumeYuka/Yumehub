package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.data.api.dto.CollaborationFilterOptions

/** 仓库筛选选项按账号会话缓存；并发读取共享请求，容量不足只淘汰最久未使用项。 */
internal class FilterOptionsCache(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val maxEntries: Int = 32,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(
        val account: String,
        val owner: String,
        val name: String,
        val generation: Long,
    )

    private class Entry(val fetchedAt: Long, val value: CollaborationFilterOptions)

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val requests = mutableMapOf<Key, CompletableDeferred<CollaborationFilterOptions>>()
    private val lock = Mutex()

    init {
        require(ttlMillis >= 0L)
        require(maxEntries > 0)
    }

    suspend fun get(
        account: String,
        owner: String,
        name: String,
        generation: Long,
        load: suspend () -> CollaborationFilterOptions,
    ): CollaborationFilterOptions {
        val key =
            Key(
                account.trim().lowercase(Locale.ROOT),
                owner.lowercase(Locale.ROOT),
                name.lowercase(Locale.ROOT),
                generation,
            )
        var ownsRequest = false
        val request = lock.withLock {
            entries[key]?.let { entry ->
                val age = clock() - entry.fetchedAt
                if (age >= 0L && age < ttlMillis) return entry.value
                entries.remove(key)
            }
            requests[key]
                ?: CompletableDeferred<CollaborationFilterOptions>().also {
                    requests[key] = it
                    ownsRequest = true
                }
        }
        if (!ownsRequest) return request.await()
        try {
            val value = load()
            currentCoroutineContext().ensureActive()
            lock.withLock {
                entries[key] = Entry(clock(), value)
                while (entries.size > maxEntries) {
                    val oldest = entries.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
            request.complete(value)
            return value
        } catch (error: Throwable) {
            request.completeExceptionally(error)
            throw error
        } finally {
            // 请求拥有者取消时也释放占位；等待者取消不会中止共享请求。
            withContext(NonCancellable) {
                lock.withLock { if (requests[key] === request) requests.remove(key) }
            }
        }
    }

    suspend fun invalidate(owner: String, name: String) {
        val normalizedOwner = owner.lowercase(Locale.ROOT)
        val normalizedName = name.lowercase(Locale.ROOT)
        lock.withLock {
            val iterator = entries.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.owner == normalizedOwner && key.name == normalizedName) {
                    iterator.remove()
                }
            }
        }
    }
}
