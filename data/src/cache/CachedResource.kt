package moe.yumeyuka.yumehub.data.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.data.storage.mmkv.ResponseCacheStore

/** 远端查询的一次结果。GraphQL 返回正文；REST 携带 ETag，未变时返回 [NotModified]。 */
sealed interface RemoteFetch<out T> {
    /** 服务端返回完整正文；[validator] 为其 ETag，没有时为 null。 */
    data class Fresh<out T>(val value: T, val validator: String? = null) : RemoteFetch<T>

    /** 服务端以 304 确认 ETag 未变，本地正文即当前内容。 */
    data object NotModified : RemoteFetch<Nothing>
}

/**
 * 不持有页面 scope；请求归仓库所有，调用方取消 await 不会取消共享请求。
 * 例外：经 [observe] 加入的请求会随最后一个观察者离开而取消，前提是没有其他调用方加入。
 */
class CachedResource<T : Any>
internal constructor(
    private val repository: CachedDataRepository,
    private val account: String,
    private val key: String,
    internal val serializer: KSerializer<T>,
    internal var maxAgeMillis: Long,
    internal val generation: Long,
    tags: Set<CacheTag>,
) {
    internal var tags: Set<CacheTag> = tags
    private val mutableState = MutableStateFlow(CachedDataState<T>())
    // 单独持有 flow 的 UI 也必须保活同一资源，不能在 LRU 淘汰后出现第二个状态源。
    val state: StateFlow<CachedDataState<T>> = ResourceStateFlow(this, mutableState)
    val cached: T?
        get() = state.value.data

    private var request: Deferred<T>? = null
    // 在途请求是否只由观察者发起；只有这种请求会随最后一个观察者离开而取消。
    private var requestObserved = false
    private var observers = 0
    private var forceRequested = false
    private var revision = 0L
    private var writeSequence = 0L
    private var hydrated = false
    private var invalidated = false
    // 内存中的新鲜度锚点：任一次成功校验（含 304 与正文未变）都会推进它，不改变已发布的 updatedAt。
    private var checkedAt = 0L
    // 与当前正文对应的服务端 ETag。本地 update 写入的正文不是服务端表示，因此清空。
    private var validator: String? = null

    fun refresh(force: Boolean = false, loader: suspend () -> T): Job =
        start(force, observed = false) { RemoteFetch.Fresh(loader()) }

    suspend fun await(force: Boolean = false, loader: suspend () -> T): T =
        start(force, observed = false) { RemoteFetch.Fresh(loader()) }.await()

    /**
     * 绑定收集者生命周期的查询：收集开始即走缓存优先，只有数据变化才发出新状态。
     * 最后一个收集者离开时，若在途请求只由观察者发起则取消它，关闭页面后不再等待网络；
     * 已被仓库或其他调用方加入的请求继续完成并写入缓存。
     */
    fun observe(
        fetch: suspend (validator: String?) -> RemoteFetch<T>,
    ): Flow<CachedDataState<T>> = flow {
        repository.locked { observers++ }
        try {
            // 新鲜缓存重复订阅不发网络；过期、无缓存或被失效的资源才校验。
            start(force = false, observed = true, fetch = fetch)
            emitAll(state)
        } finally {
            repository.locked {
                observers--
                if (observers == 0 && requestObserved) cancelRequest()
            }
        }
    }

    /** 只恢复当前会话的本地快照，不发请求，也不发布加载占位状态。 */
    suspend fun restore(): T? =
        withContext(Dispatchers.IO) {
            val version = repository.locked {
                if (!isCurrent()) throw expiredSession()
                revision
            }
            val session = repository.sessionId(account, generation)
            hydrate(session, version)
            repository.locked {
                checkCurrent(version)
                mutableState.value.data
            }
        }

    fun update(value: T) {
        repository.locked {
            if (!isCurrent()) return@locked
            revision++
            cancelRequest()
            hydrated = true
            invalidated = false
            val timestamp = System.currentTimeMillis().coerceAtLeast(1L)
            checkedAt = timestamp
            // 本地写入的正文不是服务端返回的表示，旧 ETag 不能再用于条件请求。
            validator = null
            mutableState.value = CachedDataState(data = value, updatedAt = timestamp)
            val version = revision
            persistInBackground(value, timestamp, version, etag = null)
        }
    }

    /** 保留当前内容，同时使正在执行的旧请求失效，下一次 refresh/await/observe 必须重新校验。 */
    fun invalidate() {
        repository.locked {
            if (!isCurrent()) return@locked
            revision++
            cancelRequest()
            invalidated = true
            mutableState.value =
                mutableState.value.copy(
                    loading = false,
                    error = null,
                    updatedAt = 0L,
                )
            val version = revision
            val value = mutableState.value.data
            if (value != null) {
                // ETag 保留：下次以 If-None-Match 校验，服务端未变时只需一次 304。
                persistInBackground(value, 0L, version, validator)
            } else {
                val sequence = ++writeSequence
                repository.writeQueue.submit("response:${account}:$key") {
                    ignoreStorageFailure {
                        val session = repository.sessionId(account, generation)
                        val diskKey = diskKey(session)
                        repository.store.invalidate(session, diskKey) {
                            mayPersist(version, sequence)
                        }
                    }
                }
            }
        }
    }

    private fun start(
        force: Boolean,
        observed: Boolean,
        fetch: suspend (String?) -> RemoteFetch<T>,
    ): Deferred<T> = repository.locked {
        if (!isCurrent()) {
            return@locked CompletableDeferred<T>().also { it.cancel(expiredSession()) }
        }
        request
            ?.takeIf { !it.isCompleted }
            ?.let {
                // hydration 尚未完成时也能升级为强刷；非观察者加入后，离开的观察者不再取消它。
                if (force) forceRequested = true
                if (!observed) requestObserved = false
                return@locked it
            }
        val needsReload = force || mutableState.value.error != null
        freshValue(needsReload)?.let {
            return@locked CompletableDeferred(it)
        }
        val version = revision
        forceRequested = needsReload
        requestObserved = observed
        val next = repository.scope.async(start = CoroutineStart.LAZY) { load(version, fetch) }
        setRequest(next)
        mutableState.value = mutableState.value.copy(error = null)
        next.invokeOnCompletion {
            repository.locked {
                if (request === next) {
                    setRequest(null)
                    requestObserved = false
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
        next.start()
        next
    }

    private suspend fun load(version: Long, fetch: suspend (String?) -> RemoteFetch<T>): T {
        try {
            val session = repository.sessionId(account, generation)
            hydrate(session, version)
            val fresh = repository.locked {
                checkCurrent(version)
                freshValue(forceRequested)?.also {
                    // hydration 命中缓存后这次读取已结束，稍后到达的强刷应创建真正的网络请求。
                    setRequest(null)
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
            if (fresh != null) return fresh
            val etag = repository.locked {
                checkCurrent(version)
                // 只有无数据时才显示首屏 loading；已有数据继续作为内容显示。
                mutableState.value =
                    mutableState.value.copy(
                        loading = mutableState.value.data == null,
                        error = null,
                    )
                // ETag 只对应已持久化的同一份正文；没有正文时不发条件请求。
                validator.takeIf { mutableState.value.data != null }
            }
            return when (val fetched = fetch(etag)) {
                is RemoteFetch.Fresh -> {
                    currentCoroutineContext().ensureActive()
                    accept(version, fetched.value, fetched.validator)
                }
                RemoteFetch.NotModified -> {
                    currentCoroutineContext().ensureActive()
                    confirm(version)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            repository.locked {
                if (isCurrent(version)) {
                    mutableState.value = mutableState.value.copy(loading = false, error = error)
                }
            }
            throw error
        }
    }

    // 调用方不持有锁；正文未变时只推进新鲜度与 ETag，不发布状态，页面不因相同数据重组。
    private fun accept(version: Long, value: T, nextValidator: String?): T =
        repository.locked {
            checkCurrent(version)
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            val current = mutableState.value
            val unchanged = !invalidated && current.data != null && current.data == value
            invalidated = false
            checkedAt = now
            if (!unchanged) {
                validator = nextValidator
                mutableState.value = current.copy(data = value, updatedAt = now, error = null)
                persistInBackground(value, now, version, nextValidator)
            } else {
                // 正文未变也要把本次校验时间写回磁盘，冷启动才能复用同一 ETag。
                validator = nextValidator
                persistInBackground(value, current.updatedAt, version, nextValidator)
            }
            value
        }

    // 304 只表示服务端表示未变；正文沿用当前内容，失效标记随之清除。
    private fun confirm(version: Long): T =
        repository.locked {
            checkCurrent(version)
            val current = mutableState.value
            val value = checkNotNull(current.data) { "A validator is only sent with cached data" }
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            val wasInvalidated = invalidated
            invalidated = false
            checkedAt = now
            if (wasInvalidated) {
                // 失效后被服务端确认仍有效：恢复 updatedAt 并把确认结果写回磁盘。
                mutableState.value = current.copy(updatedAt = now, error = null)
                persistInBackground(value, now, version, validator)
            } else {
                // 304 确认正文未变：持久推进 checkedAt，保留原 updatedAt 且不重发射 UI 状态。
                persistInBackground(value, current.updatedAt, version, validator)
            }
            value
        }

    private suspend fun hydrate(session: String, version: Long) {
        if (repository.locked { hydrated }) return
        if (!repository.persistenceEnabled(account, generation)) {
            repository.locked {
                checkCurrent(version)
                hydrated = true
            }
            return
        }
        val response = ignoreStorageFailure {
            repository.store.load(session, diskKey(session)) { isCurrent(version) }
        }
        // 旧无 tags 的快照不能当作新鲜内容。
        val diskTags = response?.tags
        val value =
            if (response != null && diskTags != null) {
                ignoreStorageFailure { repository.json.decodeFromString(serializer, response.payload) }
            } else null
        val stale =
            response != null &&
                diskTags != null &&
                value != null &&
                (ignoreStorageFailure {
                    repository.stale(
                        account,
                        diskTags,
                        response.checkedAt.takeIf { it > 0L } ?: response.updatedAt,
                    )
                } == true)
        repository.locked {
            checkCurrent(version)
            if (hydrated) return@locked
            hydrated = true
            if (response != null && diskTags != null && value != null) {
                if (stale) {
                    // 被失效标记覆盖的正文只作为过期内容显示，下一次读取必须强制校验。
                    mutableState.value = mutableState.value.copy(data = value, updatedAt = 0L)
                    checkedAt = 0L
                    invalidated = true
                    validator = null
                } else {
                    mutableState.value =
                        mutableState.value.copy(
                            data = value,
                            updatedAt = if (invalidated) 0L else response.updatedAt,
                        )
                    checkedAt = response.checkedAt.takeIf { it > 0L } ?: response.updatedAt
                    validator = response.etag
                }
            }
        }
    }

    // 调用方持有仓库锁；同一资源后发布的值不能被更早的后台写入覆盖。
    private fun persistInBackground(value: T, timestamp: Long, version: Long, etag: String?) {
        val sequence = ++writeSequence
        val tags = tags
        val checkedAt = checkedAt
        val queueKey = "response:${account}:$key"
        repository.writeQueue.submit(queueKey) {
            ignoreStorageFailure {
                val valid = { mayPersist(version, sequence) }
                if (!valid()) return@ignoreStorageFailure
                val session = repository.sessionId(account, generation)
                val payload = repository.json.encodeToString(serializer, value)
                repository.recordRecentSize(account, key, payload.length.toLong())
                repository.store.save(
                    session,
                    diskKey(session),
                    payload,
                    timestamp,
                    checkedAt,
                    etag,
                    tags.map { it.diskValue() },
                    valid,
                )
            }
        }
    }

    private fun mayPersist(version: Long, sequence: Long): Boolean = repository.locked {
        isCurrent() && revision == version && writeSequence == sequence
    }

    internal fun matches(invalidations: Set<CacheTag>): Boolean =
        tags.any { tag -> invalidations.any { it.covers(tag) } }

    internal fun mergeTags(next: Set<CacheTag>) {
        if (next.isNotEmpty()) tags = tags + next
    }

    private fun diskKey(session: String): String =
        ResponseCacheStore.key(session, account, key, serializer.descriptor.toString())

    // 调用方持有仓库锁；fresh 命中不改变 loading/error，也不产生状态通知。
    private fun freshValue(force: Boolean): T? {
        val age = System.currentTimeMillis() - checkedAt
        return mutableState.value.data?.takeIf {
            hydrated && !force && !invalidated && checkedAt > 0L && age >= 0L && age < maxAgeMillis
        }
    }

    // 调用方持有仓库锁；刷新指示器与请求引用同步变化，取消或完成后不会残留。
    private fun setRequest(next: Deferred<T>?) {
        request = next
        mutableState.value = mutableState.value.copy(refreshing = next != null)
    }

    private fun isCurrent(): Boolean = repository.isCurrent(account, generation)

    private fun isCurrent(version: Long): Boolean = repository.locked {
        isCurrent() && revision == version
    }

    private fun checkCurrent(version: Long) {
        if (!isCurrent(version)) throw expiredSession()
    }

    private fun cancelRequest() {
        val previous = request ?: return
        setRequest(null)
        requestObserved = false
        // 完成回调看到 request 已清空会跳过，这里同步清除 loading，避免占位残留。
        mutableState.value = mutableState.value.copy(loading = false)
        previous.cancel()
    }

    internal fun clearSession() {
        revision++
        cancelRequest()
        hydrated = true
        checkedAt = 0L
        validator = null
        mutableState.value = CachedDataState()
    }

    private suspend fun <R> ignoreStorageFailure(block: suspend () -> R): R? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ResourceStateFlow<T : Any>(
        @Suppress("unused") private val owner: CachedResource<T>,
        delegate: StateFlow<CachedDataState<T>>,
    ) : StateFlow<CachedDataState<T>> by delegate
}
