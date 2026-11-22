package moe.yumeyuka.yumehub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore

const val StarredRepositoriesPagingKey = "starred:sort=created:direction=desc:size=30"

/** 一个会话只持有一个 registry；VM 只订阅，不拥有 Pager 的缓存生命周期。 */
class PagingRepository(
    private val cache: CachedDataRepository,
    private val snapshotStore: PagingSnapshotStore,
) {
    fun <T : Any> snapshot(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        context: PagingContext? = null,
    ): PagingSnapshot<T> = PagingSnapshot(snapshotStore, account, key, serializer, context)

    fun <T : Any> pages(
        account: String,
        key: String,
        config: PagingConfig = defaultPagingConfig(),
        source: (PagingContext) -> PagingSource<Int, T>,
    ): Flow<PagingData<T>> = channelFlow {
        val registry = registry(account)
        val revision = revision(account, key)
        val entry =
            registry.acquire(key) {
                PagingEntry(
                    registry.scope,
                    cache,
                    account.lowercase(Locale.ROOT),
                    key,
                    revision,
                    config,
                    source,
                )
            }
        val cancellation =
            entry.scope.coroutineContext[Job]!!.invokeOnCompletion {
                cancel(CancellationException("分页会话已结束"))
            }
        try {
            entry.flow.collect { send(it) }
        } finally {
            cancellation.dispose()
            registry.release(key, entry)
        }
    }

    /** 原列表由 Paging 保留，替换 generation 的首屏必须重新请求网络。 */
    fun invalidate(account: String, key: String) {
        // 即便 Pager 尚未创建或已被闲置 LRU 淘汰，也持久化新的页命名空间。
        val revision = revision(account, key)
        cache.locked {
            revision.update(maxOf(System.currentTimeMillis(), (revision.cached ?: 0L) + 1L))
        }
        registry(account).invalidate(key)
    }

    private fun revision(account: String, key: String): CachedResource<Long> =
        cache.resource(account, "paging-revision:$key", Long.serializer(), Long.MAX_VALUE)

    internal fun revisions(account: String, key: String) = revision(account, key).state

    private fun registry(account: String): PagingRegistry =
        cache.sessionResource(account, "paging-registry-v1") { scope ->
            scope.coroutineContext[Job]!!.invokeOnCompletion { snapshotStore.clearMemory(account) }
            PagingRegistry(scope)
        }
}

private class PagingRegistry(val scope: CoroutineScope) {
    private val entries = LinkedHashMap<String, PagingEntry<*>>(16, 0.75f, true)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> acquire(key: String, create: () -> PagingEntry<T>): PagingEntry<T> {
        val (entry, evicted) =
            synchronized(this) {
                scope.coroutineContext.ensureActive()
                val entry =
                    entries[key]?.takeIf { it.isReusable } as? PagingEntry<T>
                        ?: create().also { entries[key] = it }
                entry.users++
                entry to trimIdle()
            }
        evicted.forEach { it.close() }
        return entry
    }

    fun release(key: String, entry: PagingEntry<*>) {
        val evicted =
            synchronized(this) {
                if (entries[key] !== entry) return
                entry.users = (entry.users - 1).coerceAtLeast(0)
                trimIdle()
            }
        evicted.forEach { it.close() }
    }

    fun invalidate(key: String) {
        synchronized(this) { entries[key] }?.invalidate()
    }

    private fun trimIdle(): List<PagingEntry<*>> {
        val idle = entries.filterValues { it.users == 0 }.keys.toList()
        return idle.take((idle.size - MaxIdleEntries).coerceAtLeast(0)).mapNotNull(entries::remove)
    }

    private companion object {
        const val MaxIdleEntries = 16
    }
}

internal class PagingEntry<T : Any>(
    parent: CoroutineScope,
    val cache: CachedDataRepository,
    val account: String,
    val key: String,
    val revision: CachedResource<Long>,
    config: PagingConfig,
    source: (PagingContext) -> PagingSource<Int, T>,
) {
    private val lock = Any()
    val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var generation = SupervisorJob(scope.coroutineContext[Job])
    private var version = 0L
    private var initial = true
    private var revalidated = false
    private val sources = mutableSetOf<PagingSource<Int, T>>()
    var users: Int = 0
    val isReusable: Boolean
        get() = scope.coroutineContext[Job]?.isActive == true

    val flow: Flow<PagingData<T>> =
        Pager(config) {
                val retired =
                    synchronized(lock) {
                        if (!initial && !revalidated) retireGeneration() else null
                    }
                retired?.cancel()
                synchronized(lock) {
                    val context = PagingContext(this, version, generation, initial, !revalidated)
                    initial = false
                    revalidated = false
                    source(context).also { pagingSource ->
                        sources.add(pagingSource)
                        pagingSource.registerInvalidatedCallback {
                            synchronized(lock) { sources.remove(pagingSource) }
                        }
                    }
                }
            }
            .flow
            .cachedIn(scope)

    fun ensureCurrent(expected: Long) =
        synchronized(lock) {
            scope.coroutineContext.ensureActive()
            if (version != expected) throw CancellationException("分页请求已失效")
        }

    fun <R> commit(expected: Long, block: () -> R): R = cache.locked {
        synchronized(lock) {
            ensureCurrent(expected)
            block()
        }
    }

    fun revalidated(expected: Long, source: PagingSource<*, *>) =
        synchronized(lock) {
            ensureCurrent(expected)
            if (!source.invalid) {
                revalidated = true
                source.invalidate()
            }
        }

    fun invalidate() {
        val old =
            synchronized(lock) {
                val retired = retireGeneration()
                initial = false
                revalidated = false
                retired to sources.toList()
            }
        old.first.cancel()
        old.second.forEach { it.invalidate() }
    }

    private fun retireGeneration(): Job {
        version++
        val oldGeneration = generation
        generation = SupervisorJob(scope.coroutineContext[Job])
        // 页面换代只取消本页加载；共享响应由业务失效处理，不能取消另一页面的读取。
        return oldGeneration
    }

    fun close() {
        // 释放页面分页不能使其他页面共享的业务响应失效。
        scope.cancel()
    }
}

/** 单个 PagingSource 的请求令牌；失效后旧结果不能更新内存、快照或 UI。 */
class PagingContext
internal constructor(
    private val entry: PagingEntry<*>,
    private val version: Long,
    private val generation: Job,
    val initialSnapshot: Boolean,
    val forceRefresh: Boolean,
) {
    fun ensureCurrent() = entry.ensureCurrent(version)

    internal fun <R> commit(block: () -> R): R = entry.commit(version, block)

    suspend fun <T : Any> await(
        key: String,
        serializer: KSerializer<T>,
        force: Boolean = false,
        maxAgeMillis: Long = 60_000L,
        loader: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val namespace = snapshotKey(entry.key)
        val resource =
            entry.cache.resource(entry.account, "paging:$namespace:$key", serializer, maxAgeMillis)
        ensureCurrent()
        val value = resource.await(force = force, loader = loader)
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return value
    }

    suspend fun snapshotKey(key: String): String {
        ensureCurrent()
        val session = entry.cache.sessionId(entry.account, entry.revision.generation)
        val revision = entry.revision.await { 0L }
        ensureCurrent()
        return "session:$session:revision:$revision:$key"
    }

    internal fun persist(block: suspend () -> Unit) {
        CoroutineScope(entry.scope.coroutineContext + generation).launch {
            ensureCurrent()
            block()
        }
    }

    fun revalidate(source: PagingSource<*, *>, changed: suspend () -> Boolean) {
        CoroutineScope(entry.scope.coroutineContext + generation).launch {
            try {
                if (changed()) entry.revalidated(version, source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 离线时保留已加载的首屏，显式刷新仍可重试。
            }
        }
    }
}

fun defaultPagingConfig(pageSize: Int = 30): PagingConfig =
    PagingConfig(
        pageSize = pageSize,
        initialLoadSize = pageSize,
        enablePlaceholders = false,
        maxSize = pageSize * 10,
    )
