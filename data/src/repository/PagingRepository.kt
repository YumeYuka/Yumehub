package moe.yumeyuka.yumehub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotStore

const val StarredRepositoriesPagingKey = "starred:sort=created:direction=desc:size=30"

sealed interface RevalidationState {
    data object Idle : RevalidationState
    data object Checking : RevalidationState
    data class Failed(val error: Throwable) : RevalidationState
}

/** 一个会话只持有一个 registry；VM 只订阅，不拥有 Pager 的缓存生命周期。 */
class PagingRepository(
    private val cache: CachedDataRepository,
    private val snapshotStore: PagingSnapshotStore,
) {
    init {
        // domain 级写入（star、follow、work 状态变化）让正在显示的分页首屏自行重读；
        // 具体 subject 失效只依赖快照标记，避免评论正文刷新整页列表。
        cache.addInvalidationListener { account, tags ->
            if (tags.any { it.subject == null }) registry(account).revalidate(tags)
        }
    }

    fun <T : Any> snapshot(
        account: String,
        key: String,
        serializer: KSerializer<T>,
        context: PagingContext? = null,
        tags: Set<CacheTag> = emptySet(),
    ): PagingSnapshot<T> = PagingSnapshot(snapshotStore, account, key, serializer, context, tags)

    fun <T : Any> pages(
        account: String,
        key: String,
        config: PagingConfig = defaultPagingConfig(),
        tags: Set<CacheTag> = emptySet(),
        source: (PagingContext) -> PagingSource<Int, T>,
    ): Flow<PagingData<T>> = channelFlow {
        val registry = registry(account)
        val state = registry.state(key)
        val entry =
            registry.acquire(key) {
                PagingEntry(
                    registry.scope,
                    cache,
                    account.lowercase(Locale.ROOT),
                    key,
                    state,
                    config,
                    tags,
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
        // 即便 Pager 尚未创建或已被闲置 LRU 淘汰，也更换本会话的页命名空间。
        val registry = registry(account)
        val entry = cache.locked { registry.rotate(key) }
        entry?.retire()
    }

    fun revalidation(account: String, key: String): StateFlow<RevalidationState> =
        registry(account).state(key).revalidation

    fun retryRevalidation(account: String, key: String) {
        val entry = registry(account).currentEntry(key) ?: return
        entry.retryRevalidation()
    }

    internal fun revisions(account: String, key: String): StateFlow<Long> =
        registry(account).state(key).version

    internal fun currentNamespace(account: String, key: String): String =
        registry(account).state(key).current()

    private fun registry(account: String): PagingRegistry =
        cache.sessionResource(account, "paging-registry-v1") { scope ->
            scope.coroutineContext[Job]!!.invokeOnCompletion { snapshotStore.clearMemory(account) }
            PagingRegistry(scope)
        }
}

/** 同一分页 key 的命名空间与版本只属于当前会话，不依赖持久化 revision。 */
internal class PagingKeyState {
    private val lock = Any()
    private var namespace = UUID.randomUUID().toString()
    private val mutableVersion = MutableStateFlow(0L)
    val version: StateFlow<Long> = mutableVersion.asStateFlow()
    private val mutableRevalidation = MutableStateFlow<RevalidationState>(RevalidationState.Idle)
    val revalidation: StateFlow<RevalidationState> = mutableRevalidation.asStateFlow()

    fun current(): String = synchronized(lock) { namespace }

    fun rotate() =
        synchronized(lock) {
            namespace = UUID.randomUUID().toString()
            mutableVersion.value = mutableVersion.value + 1L
            mutableRevalidation.value = RevalidationState.Idle
        }

    fun updateRevalidation(state: RevalidationState) {
        mutableRevalidation.value = state
    }
}

private class PagingRegistry(val scope: CoroutineScope) {
    private val entries = LinkedHashMap<String, PagingEntry<*>>(16, 0.75f, true)
    private val states = mutableMapOf<String, PagingKeyState>()

    fun state(key: String): PagingKeyState = synchronized(this) {
        states.getOrPut(key) { PagingKeyState() }
    }

    fun currentEntry(key: String): PagingEntry<*>? = synchronized(this) {
        entries[key]?.takeIf { it.isReusable }
    }

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

    /** 调用方持有会话锁：先更换命名空间，返回需要退休的 entry。 */
    fun rotate(key: String): PagingEntry<*>? = synchronized(this) {
        state(key).rotate()
        entries[key]
    }

    /** domain 级失效：让所有正在收集且属于该 domain 的页自行重读首屏。 */
    fun revalidate(invalidations: Set<CacheTag>) {
        val affected =
            synchronized(this) {
                entries.values.filter { entry -> entry.matches(invalidations) }
            }
        affected.forEach { it.revalidate() }
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
    val state: PagingKeyState,
    config: PagingConfig,
    private val tags: Set<CacheTag>,
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
    private val checkingSources = mutableSetOf<PagingSource<*, *>>()
    private var lastRevalidator: (Triple<Long, PagingSource<*, *>, suspend () -> Boolean>)? = null
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
                            synchronized(lock) {
                                sources.remove(pagingSource)
                                checkingSources.remove(pagingSource)
                                if (lastRevalidator?.second === pagingSource) lastRevalidator = null
                            }
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

    fun matches(invalidations: Set<CacheTag>): Boolean =
        tags.any { tag -> invalidations.any { it.domain == tag.domain } }

    /** 标记下一份 source 必须强刷，并让当前 source 失效以触发 Paging 重读。 */
    fun revalidate() {
        val current =
            synchronized(lock) {
                revalidated = true
                sources.toList()
            }
        current.forEach { it.invalidate() }
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

    fun startRevalidate(
        expected: Long,
        expectedGeneration: Job,
        source: PagingSource<*, *>,
        changed: suspend () -> Boolean,
    ) {
        val launched =
            synchronized(lock) {
                if (version != expected || source.invalid || !checkingSources.add(source)) return
                lastRevalidator = Triple(expected, source, changed)
                state.updateRevalidation(RevalidationState.Checking)
                true
            }
        if (!launched) return
        CoroutineScope(scope.coroutineContext + expectedGeneration).launch {
            try {
                ensureCurrent(expected)
                val diff = changed()
                ensureCurrent(expected)
                if (diff) revalidated(expected, source)
                synchronized(lock) {
                    checkingSources.remove(source)
                    if (version == expected) state.updateRevalidation(RevalidationState.Idle)
                }
            } catch (error: CancellationException) {
                synchronized(lock) {
                    checkingSources.remove(source)
                    if (version == expected) state.updateRevalidation(RevalidationState.Idle)
                }
                throw error
            } catch (error: Exception) {
                synchronized(lock) {
                    checkingSources.remove(source)
                    if (version == expected) {
                        state.updateRevalidation(RevalidationState.Failed(error))
                    }
                }
            }
        }
    }

    fun retryRevalidation() {
        val pending =
            synchronized(lock) {
                val item = lastRevalidator ?: return
                val (expected, source, changed) = item
                if (version != expected || source !in sources || source.invalid) return
                Triple(expected, source, changed) to generation
            }
        val (triple, gen) = pending
        startRevalidate(triple.first, gen, triple.second, triple.third)
    }

    /** 调用方已更换 [state] 的命名空间；这里取消旧 generation 与 source。 */
    fun retire() {
        val old =
            synchronized(lock) {
                initial = false
                revalidated = false
                checkingSources.clear()
                lastRevalidator = null
                retireGeneration() to sources.toList()
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
        tags: Set<CacheTag> = emptySet(),
        loader: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val namespace = snapshotKey(entry.key)
        val resource =
            entry.cache.resource(
                entry.account,
                "paging:$namespace:$key",
                serializer,
                maxAgeMillis,
                tags,
            )
        ensureCurrent()
        val value = resource.await(force = force, loader = loader)
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return value
    }

    suspend fun snapshotKey(key: String): String {
        ensureCurrent()
        val session = entry.cache.sessionId(entry.account)
        ensureCurrent()
        return "session:$session:revision:${entry.state.current()}:$key"
    }

    internal fun persistenceEnabled(): Boolean =
        entry.cache.persistenceEnabled(entry.account, entry.cache.sessionGeneration())

    internal suspend fun tagsStale(diskTags: List<String>, checkedAt: Long): Boolean =
        entry.cache.stale(entry.account, diskTags, checkedAt)

    internal fun persist(block: suspend () -> Unit) {
        CoroutineScope(entry.scope.coroutineContext + generation).launch {
            ensureCurrent()
            block()
        }
    }

    fun revalidate(source: PagingSource<*, *>, changed: suspend () -> Boolean) {
        entry.startRevalidate(version, generation, source, changed)
    }
}

fun defaultPagingConfig(pageSize: Int = 30): PagingConfig =
    PagingConfig(
        pageSize = pageSize,
        initialLoadSize = pageSize,
        enablePlaceholders = false,
        maxSize = pageSize * 10,
    )
