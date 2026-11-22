package moe.yumeyuka.yumehub.data.cache

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.storage.mmkv.MMKVProvider
import moe.yumeyuka.yumehub.data.storage.mmkv.ResponseCacheStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/** 跨页面共享响应。key 必须包含查询参数和数据版本，不能包含凭据；value 只放业务响应， 不得放令牌、认证响应、页面上下文或 UI 对象。同一 key 必须始终使用同一数据类型。 */
class CachedDataRepository(
    tokenStore: TokenStore,
    provider: MMKVProvider,
    internal val json: Json,
) : AutoCloseable {
    private val session = tokenStore.responseCacheSession
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val store = ResponseCacheStore(provider, json)
    private val collected = ReferenceQueue<CachedResource<*>>()
    private val resources = mutableMapOf<ResourceKey, ResourceReference>()
    private val recent = LinkedHashMap<ResourceKey, CachedResource<*>>(16, 0.75f, true)
    private val runtimes = mutableMapOf<ResourceKey, Runtime>()
    private var closed = false
    private val sessionListener: () -> Unit = ::onSessionChanged
    private val initialized: Deferred<Unit>

    init {
        locked { session.listeners.add(sessionListener) }
        initialized = scope.async { tokenStore.prepareResponseCacheSession() }
        scope.launch {
            initialized.await()
            clearOldSnapshots()
        }
    }

    fun <T : Any> resource(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        maxAgeMillis: Long = 60_000L,
    ): CachedResource<T> = locked {
        check(!closed) { "Response cache is closed" }
        require(maxAgeMillis >= 0L) { "Cache age must not be negative" }
        val identity = identity(account, key)
        reapReferences()
        val existing = resources[identity]?.get()
        val resource =
            if (existing != null) {
                require(existing.serializer.descriptor == serializer.descriptor) {
                    "The same cache key must use the same serializer"
                }
                // 同一资源收到不同 TTL 时取更严格值，不生成另一个状态源。
                existing.maxAgeMillis = minOf(existing.maxAgeMillis, maxAgeMillis)
                @Suppress("UNCHECKED_CAST") (existing as CachedResource<T>)
            } else {
                CachedResource(
                        this,
                        identity.account,
                        key,
                        serializer,
                        maxAgeMillis,
                        session.generation,
                    )
                    .also { resources[identity] = ResourceReference(identity, it, collected) }
            }
        recent[identity] = resource
        while (recent.size > MaxRecentResources) {
            // 只释放仓库的强引用。页面、flow 或请求仍持有资源时，弱索引保持其唯一性。
            val oldest = recent.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        resource
    }

    /**
     * 会话内同步 getOrPut。create 应是无 IO、非阻塞的 holder 工厂，同一 key 的类型须一致。 专属 scope 使用 SupervisorJob +
     * IO；退出或换号时取消，不参与普通响应 LRU。
     */
    fun <T : Any> sessionResource(
        account: String,
        key: String,
        create: (CoroutineScope) -> T,
    ): T = locked {
        check(!closed) { "Response cache is closed" }
        val identity = identity(account, key)
        val generation = session.generation
        if (!isCurrent(identity.account, generation)) throw expiredSession()
        val existing = runtimes[identity]
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return@locked existing.value as T
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runtimeScope = CoroutineScope(scope.coroutineContext + job)
        try {
            val value = create(runtimeScope)
            if (!isCurrent(identity.account, generation)) throw expiredSession()
            runtimes[identity] = Runtime(generation, job, value)
            value
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }
    }

    internal fun <T> locked(block: () -> T): T = synchronized(session.lock, block)

    internal fun isCurrent(account: String, generation: Long): Boolean =
        !closed && session.accepts(account, generation)

    internal suspend fun sessionId(account: String, generation: Long): String {
        initialized.await()
        val binding = locked {
            if (!isCurrent(account, generation)) throw expiredSession()
            session.accountReady
        }
        // 启动时尚未绑定账号只等待，不丢合法结果，也不提前发出匿名请求。
        val boundAccount = binding.await()
        return locked {
            if (
                !isCurrent(account, generation) ||
                    boundAccount != account ||
                    session.account != account
            ) {
                throw expiredSession()
            }
            checkNotNull(session.id)
        }
    }

    /** TokenStore 在修改账号或退出的同一个临界区内调用，立即清空已暴露的 StateFlow。 */
    private fun onSessionChanged() {
        reapReferences()
        val iterator = resources.entries.iterator()
        while (iterator.hasNext()) {
            val (key, reference) = iterator.next()
            val resource = reference.get()
            if (resource == null || !isCurrent(key.account, resource.generation)) {
                resource?.clearSession()
                recent.remove(key)
                iterator.remove()
            }
        }
        val runtimeIterator = runtimes.entries.iterator()
        while (runtimeIterator.hasNext()) {
            val (key, runtime) = runtimeIterator.next()
            if (!isCurrent(key.account, runtime.generation)) {
                runtime.job.cancel(expiredSession())
                runtimeIterator.remove()
            }
        }
        if (!closed) scope.launch { clearOldSnapshots() }
    }

    private suspend fun clearOldSnapshots() {
        val stamp = locked { session.id?.let { it to session.generation } } ?: return
        try {
            store.clearOtherSessions(stamp.first) {
                locked {
                    !closed && session.id == stamp.first && session.generation == stamp.second
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 缓存不可用不能阻止会话恢复；下一次读写仍会清理不同会话的快照。
        }
    }

    fun changes(account: String): DataChanges =
        sessionResource(account, "data-changes-v1") { DataChanges() }

    /** 保留已显示的数据；使同会话内已创建的相关响应在下一次读取时重新校验。 */
    fun invalidateMatching(account: String, matches: (String) -> Boolean) {
        locked {
            val normalized = normalizeAccount(account)
            resources.entries.toList().forEach { (key, reference) ->
                if (key.account == normalized && matches(key.key)) reference.get()?.invalidate()
            }
        }
    }

    private fun reapReferences() {
        while (true) {
            val reference = collected.poll() as? ResourceReference ?: return
            if (resources[reference.key] === reference) resources.remove(reference.key)
        }
    }

    override fun close() {
        locked {
            if (closed) return@locked
            closed = true
            session.listeners.remove(sessionListener)
            resources.values.forEach { it.get()?.clearSession() }
            resources.clear()
            recent.clear()
            runtimes.values.forEach { it.job.cancel() }
            runtimes.clear()
            scope.cancel()
        }
    }

    private fun identity(account: String, key: String): ResourceKey {
        val normalized = normalizeAccount(account)
        require(normalized.isNotBlank()) { "An explicit cache account is required" }
        require(key.isNotBlank()) { "A cache key is required" }
        return ResourceKey(normalized, key)
    }

    private data class ResourceKey(val account: String, val key: String)

    private class ResourceReference(
        val key: ResourceKey,
        resource: CachedResource<*>,
        queue: ReferenceQueue<CachedResource<*>>,
    ) : WeakReference<CachedResource<*>>(resource, queue)

    private class Runtime(val generation: Long, val job: Job, val value: Any)

    private companion object {
        const val MaxRecentResources = 64
    }
}

internal fun expiredSession(): CancellationException =
    CancellationException("Response cache session has ended")
