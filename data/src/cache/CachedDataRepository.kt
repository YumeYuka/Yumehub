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
import moe.yumeyuka.yumehub.data.storage.mmkv.CacheInvalidationStore
import moe.yumeyuka.yumehub.data.storage.mmkv.MMKVProvider
import moe.yumeyuka.yumehub.data.storage.mmkv.PendingMutationStore
import moe.yumeyuka.yumehub.data.storage.mmkv.ResponseCacheStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/** 跨页面共享响应。key 必须包含查询参数和数据版本，不能包含凭据；value 只放业务响应， 不得放令牌、认证响应、页面上下文或 UI 对象。同一 key 必须始终使用同一数据类型。 */
class CachedDataRepository(
    tokenStore: TokenStore,
    provider: MMKVProvider,
    internal val json: Json,
    private val pagingStore: moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore,
) : AutoCloseable {
    private val session = tokenStore.responseCacheSession
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val store = ResponseCacheStore(provider, json)
    internal val invalidationStore = CacheInvalidationStore(provider, json)
    internal val pendingMutations = PendingMutationStore(provider, json)
    internal val twoFactorRegistrations =
        moe.yumeyuka.yumehub.data.storage.mmkv.TwoFactorRegistrationStore(provider, json)
    internal val writeQueue = CacheWriteQueue(scope)
    private val collected = ReferenceQueue<CachedResource<*>>()
    private val resources = mutableMapOf<ResourceKey, ResourceReference>()
    private val recent = LinkedHashMap<ResourceKey, Pair<CachedResource<*>, Long>>(16, 0.75f, true)
    private var recentBytes = 0L
    private val runtimes = mutableMapOf<ResourceKey, Runtime>()
    private val invalidationListeners = mutableListOf<(String, Set<CacheTag>) -> Unit>()
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
        tags: Set<CacheTag> = emptySet(),
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
                existing.mergeTags(tags)
                @Suppress("UNCHECKED_CAST") (existing as CachedResource<T>)
            } else {
                CachedResource(
                        this,
                        identity.account,
                        key,
                        serializer,
                        maxAgeMillis,
                        session.generation,
                        tags,
                    )
                    .also { resources[identity] = ResourceReference(identity, it, collected) }
            }
        recent[identity] // Touch the LRU without erasing its encoded size.
        resource
    }

    private fun rememberRecent(
        identity: ResourceKey,
        resource: CachedResource<*>,
        bytes: Long = 0L,
    ) {
        if (bytes > MaxRecentBytes) {
            recent.remove(identity)?.let { recentBytes -= it.second }
            return
        }
        val previous = recent.put(identity, resource to bytes)
        if (previous != null) recentBytes -= previous.second
        recentBytes += bytes
        while (recent.size > MaxRecentResources || recentBytes > MaxRecentBytes) {
            // 只释放仓库的强引用。页面、flow 或请求仍持有资源时，弱索引保持其唯一性。
            val oldest = recent.entries.iterator()
            if (!oldest.hasNext()) break
            val entry = oldest.next()
            recentBytes -= entry.value.second
            oldest.remove()
        }
    }

    internal fun recordRecentSize(
        account: String,
        key: String,
        resource: CachedResource<*>,
        bytes: Long,
    ) {
        locked {
            if (closed) return@locked
            val identity = ResourceKey(normalizeAccount(account), key)
            if (resources[identity]?.get() === resource) rememberRecent(identity, resource, bytes)
        }
    }

    internal fun syncCacheStores() {
        store.sync()
        pagingStore.sync()
        invalidationStore.sync()
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
                recent.remove(key)?.let { recentBytes -= it.second }
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
            val valid = {
                locked {
                    !closed && session.id == stamp.first && session.generation == stamp.second
                }
            }
            writeQueue.run {
                store.clearOtherSessions(stamp.first, valid)
                pagingStore.disk.clearOtherSessions(stamp.first, valid)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 缓存不可用不能阻止会话恢复；下一次读写仍会清理不同会话的快照。
        }
    }

    fun changes(account: String): DataChanges =
        sessionResource(account, "data-changes-v1") { DataChanges() }

    /** 每账号一个会话级写协调器；页面只订阅其状态，不创建第二套队列。 */
    fun mutations(account: String): MutationCoordinator =
        sessionResource(account, "mutation-coordinator-v1") { scope ->
            MutationCoordinator(
                normalizeAccount(account),
                session.generation,
                this,
                pendingMutations,
                json,
                scope,
            )
        }

    /**
     * 立即推进相关资源的会话内版本并取消旧读；磁盘正文保留，供过期显示和下次强制校验。 具体 subject 失效同时影响该 domain 的全域视图；null 失效影响整个 domain。
     */
    fun invalidate(account: String, tags: Set<CacheTag>) {
        invalidate(account, tags, notifyListeners = true)
    }

    private fun invalidate(account: String, tags: Set<CacheTag>, notifyListeners: Boolean) {
        if (tags.isEmpty()) return
        val listeners = locked {
            val normalized = normalizeAccount(account)
            resources.entries.toList().forEach { (key, reference) ->
                if (key.account != normalized) return@forEach
                val resource = reference.get() ?: return@forEach
                if (resource.matches(tags)) resource.invalidate()
            }
            if (notifyListeners) invalidationListeners.toList() else emptyList()
        }
        listeners.forEach { listener ->
            runCatching { listener(account, tags) }
        }
    }

    /** 供分页等会话级视图在 domain 级失效后自行重读；异常不影响缓存失效。 */
    internal fun addInvalidationListener(listener: (String, Set<CacheTag>) -> Unit) {
        locked { invalidationListeners.add(listener) }
    }

    private val syncInvalidations: () -> Unit = invalidationStore::sync

    /** 等待失效屏障持久完成后再返回；网络写入必须在发送前等待它。 */
    suspend fun persistInvalidation(account: String, tags: Set<CacheTag>) {
        if (tags.isEmpty()) return
        val at = System.currentTimeMillis().coerceAtLeast(1L)
        invalidate(account, tags)
        writeQueue.run(syncInvalidations) { invalidationStore.mark(account, tags, at) }
    }

    /** 发送前屏障：失效内存读，但列表重读等确认后的失效再触发。 */
    internal suspend fun prepareInvalidation(account: String, tags: Set<CacheTag>) {
        if (tags.isEmpty()) return
        val at = System.currentTimeMillis().coerceAtLeast(1L)
        invalidate(account, tags, notifyListeners = false)
        writeQueue.run(syncInvalidations) { invalidationStore.mark(account, tags, at) }
    }

    /** 已确认操作后的失效：内存立即生效，磁盘屏障由单写队列异步落盘。 */
    internal fun invalidateAndPersist(account: String, tags: Set<CacheTag>) {
        if (tags.isEmpty()) return
        val at = System.currentTimeMillis().coerceAtLeast(1L)
        invalidate(account, tags)
        scope.launch {
            runCatching { writeQueue.run { invalidationStore.mark(account, tags, at) } }
        }
    }

    internal suspend fun stale(account: String, diskTags: List<String>, checkedAt: Long): Boolean =
        invalidationStore.stale(account, diskTags, checkedAt)

    internal fun persistenceEnabled(account: String, generation: Long): Boolean =
        isCurrent(account, generation) && locked { session.persistenceEnabled }

    internal fun sessionGeneration(): Long = locked { session.generation }

    /** 当前会话的分页/快照读取也使用同一账号校验，不依赖旧的持久 revision。 */
    internal suspend fun sessionId(account: String): String {
        val normalized = normalizeAccount(account)
        initialized.await()
        val binding = locked {
            if (!isCurrent(normalized, session.generation)) throw expiredSession()
            session.accountReady
        }
        val boundAccount = binding.await()
        return locked {
            if (!isCurrent(normalized, session.generation) || boundAccount != normalized) {
                throw expiredSession()
            }
            checkNotNull(session.id)
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
            recentBytes = 0L
            runtimes.values.forEach { it.job.cancel() }
            runtimes.clear()
            invalidationListeners.clear()
            writeQueue.close()
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
        const val MaxRecentBytes = 16L * 1024 * 1024
    }
}

internal fun expiredSession(): CancellationException =
    CancellationException("Response cache session has ended")
