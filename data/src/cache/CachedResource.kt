package moe.yumeyuka.yumehub.data.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.data.storage.mmkv.ResponseCacheStore

/** 不持有 loader 或页面 scope；请求归仓库所有，调用方取消 await 不会取消共享请求。 */
class CachedResource<T : Any>
internal constructor(
    private val repository: CachedDataRepository,
    private val account: String,
    private val key: String,
    internal val serializer: KSerializer<T>,
    internal var maxAgeMillis: Long,
    internal val generation: Long,
) {
    private val mutableState = MutableStateFlow(CachedDataState<T>())
    // 单独持有 flow 的 UI 也必须保活同一资源，不能在 LRU 淘汰后出现第二个状态源。
    val state: StateFlow<CachedDataState<T>> = ResourceStateFlow(this, mutableState)
    val cached: T?
        get() = state.value.data

    private var request: Deferred<T>? = null
    private var forceRequested = false
    private var revision = 0L
    private var writeSequence = 0L
    private var hydrated = false
    private var invalidated = false

    fun refresh(force: Boolean = false, loader: suspend () -> T): Job = start(force, loader)

    suspend fun await(force: Boolean = false, loader: suspend () -> T): T =
        start(force, loader).await()

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
            mutableState.value = CachedDataState(data = value, updatedAt = timestamp)
            val version = revision
            persistInBackground(value, timestamp, version)
        }
    }

    /** 保留当前内容，同时使正在执行的旧请求失效，下一次 refresh/await 必须重新请求。 */
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
                persistInBackground(value, 0L, version)
            } else {
                val sequence = ++writeSequence
                repository.scope.launch {
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

    private fun start(force: Boolean, loader: suspend () -> T): Deferred<T> = repository.locked {
        if (!isCurrent()) {
            return@locked CompletableDeferred<T>().also { it.cancel(expiredSession()) }
        }
        request
            ?.takeIf { !it.isCompleted }
            ?.let {
                // hydration 尚未完成时也能升级为强刷；已有 loader 则仍共享同一次调用。
                if (force) forceRequested = true
                return@locked it
            }
        val needsReload = force || mutableState.value.error != null
        freshValue(needsReload)?.let {
            return@locked CompletableDeferred(it)
        }
        val version = revision
        forceRequested = needsReload
        val next = repository.scope.async(start = CoroutineStart.LAZY) { load(version, loader) }
        request = next
        mutableState.value = mutableState.value.copy(error = null)
        next.invokeOnCompletion {
            repository.locked {
                if (request === next) {
                    request = null
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
        next.start()
        next
    }

    private suspend fun load(version: Long, loader: suspend () -> T): T {
        try {
            val session = repository.sessionId(account, generation)
            hydrate(session, version)
            val fresh = repository.locked {
                checkCurrent(version)
                freshValue(forceRequested)?.also {
                    // hydration 命中缓存后这次读取已结束，稍后到达的强刷应创建真正的网络请求。
                    request = null
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
            if (fresh != null) return fresh
            repository.locked {
                checkCurrent(version)
                // 只有无数据时才显示首屏 loading；已有数据继续作为内容显示。
                mutableState.value =
                    mutableState.value.copy(
                        loading = mutableState.value.data == null,
                        error = null,
                    )
            }
            val value = loader()
            currentCoroutineContext().ensureActive()
            repository.locked {
                checkCurrent(version)
                val now = System.currentTimeMillis().coerceAtLeast(1L)
                invalidated = false
                mutableState.value = CachedDataState(data = value, updatedAt = now)
                persistInBackground(value, now, version)
            }
            return value
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

    private suspend fun hydrate(session: String, version: Long) {
        if (repository.locked { hydrated }) return
        val response = ignoreStorageFailure {
            repository.store.load(session, diskKey(session)) { isCurrent(version) }
        }
        val value = response?.let {
            ignoreStorageFailure { repository.json.decodeFromString(serializer, it.payload) }
        }
        repository.locked {
            checkCurrent(version)
            if (hydrated) return@locked
            hydrated = true
            if (value != null && response != null) {
                mutableState.value =
                    mutableState.value.copy(
                        data = value,
                        updatedAt = if (invalidated) 0L else response.updatedAt,
                    )
            }
        }
    }

    // 调用方持有仓库锁；同一资源后发布的值不能被更早的后台写入覆盖。
    private fun persistInBackground(value: T, timestamp: Long, version: Long) {
        val sequence = ++writeSequence
        repository.scope.launch {
            ignoreStorageFailure {
                val valid = { mayPersist(version, sequence) }
                if (!valid()) return@ignoreStorageFailure
                val session = repository.sessionId(account, generation)
                val payload = repository.json.encodeToString(serializer, value)
                repository.store.save(session, diskKey(session), payload, timestamp, valid)
            }
        }
    }

    private fun mayPersist(version: Long, sequence: Long): Boolean = repository.locked {
        isCurrent() && revision == version && writeSequence == sequence
    }

    private fun diskKey(session: String): String =
        ResponseCacheStore.key(session, account, key, serializer.descriptor.toString())

    // 调用方持有仓库锁；fresh 命中不改变 loading/error，也不产生状态通知。
    private fun freshValue(force: Boolean): T? {
        val snapshot = mutableState.value
        val age = System.currentTimeMillis() - snapshot.updatedAt
        return snapshot.data?.takeIf {
            hydrated &&
                !force &&
                !invalidated &&
                snapshot.updatedAt > 0L &&
                age >= 0L &&
                age < maxAgeMillis
        }
    }

    private fun isCurrent(): Boolean = repository.isCurrent(account, generation)

    private fun isCurrent(version: Long): Boolean = repository.locked {
        isCurrent() && revision == version
    }

    private fun checkCurrent(version: Long) {
        if (!isCurrent(version)) throw expiredSession()
    }

    private fun cancelRequest() {
        val previous = request
        request = null
        previous?.cancel()
    }

    internal fun clearSession() {
        revision++
        cancelRequest()
        hydrated = true
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

    private class ResourceStateFlow<T : Any>(
        @Suppress("unused") private val owner: CachedResource<T>,
        delegate: StateFlow<CachedDataState<T>>,
    ) : StateFlow<CachedDataState<T>> by delegate
}
