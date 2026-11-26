package moe.yumeyuka.yumehub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import java.util.LinkedHashMap
import java.util.Locale
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.covers
import moe.yumeyuka.yumehub.data.storage.mmkv.PagingSnapshotPage
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
        cache.addInvalidationListener { account, tags -> registry(account).invalidate(tags) }
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

    /** Invalidate the stable query even when no Pager currently holds it. */
    fun invalidate(account: String, key: String) {
        cache.invalidateAndPersist(account, setOf(CacheTag(DataDomain.Paging, key)))
    }

    fun revalidation(account: String, key: String): StateFlow<RevalidationState> =
        registry(account).state(key).revalidation

    fun retryRevalidation(account: String, key: String) {
        val entry = registry(account).currentEntry(key) ?: return
        entry.retryRevalidation()
    }

    internal fun revisions(account: String, key: String): StateFlow<Long> =
        registry(account).state(key).version


    private fun registry(account: String): PagingRegistry =
        cache.sessionResource(account, "paging-registry-v1") { scope ->
            PagingRegistry(scope)
        }
}

internal class PagingKeyState {
    private var first: WeakReference<PagingSnapshotPage<*>>? = null
    private var diskRevision = -1L
    private var frameGeneration = -1L
    @Volatile var sequence = 0L
        private set
    private val mutableVersion = MutableStateFlow(0L)
    val version: StateFlow<Long> = mutableVersion.asStateFlow()
    private val mutableRevalidation = MutableStateFlow<RevalidationState>(RevalidationState.Idle)
    val revalidation: StateFlow<RevalidationState> = mutableRevalidation.asStateFlow()

    @Synchronized fun rotate() {
        sequence++
        mutableVersion.value++
        mutableRevalidation.value = RevalidationState.Idle
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T : Any> frame(revision: Long): PagingSnapshotPage<T>? {
        val page = first?.get() as? PagingSnapshotPage<T> ?: return null
        return if (revision == diskRevision && frameGeneration == version.value) page
        else page.copy(boundaryInvalid = true)
    }

    @Synchronized fun remember(page: PagingSnapshotPage<*>, revision: Long): Long {
        first = WeakReference(page)
        diskRevision = revision
        frameGeneration = version.value
        return ++sequence
    }

    @Synchronized fun forget(): Long {
        first = null
        return ++sequence
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

    fun invalidate(tags: Set<CacheTag>) {
        val affected = synchronized(this) {
            tags.filter { it.domain == DataDomain.Paging }.forEach { tag ->
                tag.subject?.takeIf { it !in entries }?.let { states[it]?.rotate() }
            }
            entries.values.filter { it.matches(tags) }.onEach { it.state.rotate() }
        }
        affected.forEach { it.retire() }
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
    tags: Set<CacheTag>,
    source: (PagingContext) -> PagingSource<Int, T>,
) {
    val tags = tags + CacheTag(DataDomain.Paging, key)
    val sessionGeneration = cache.sessionGeneration()
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
                        if (!initial) retireGeneration() else null
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
            if (version != expected || !cache.isCurrent(account, sessionGeneration)) throw CancellationException("分页请求已失效")
        }

    fun matches(invalidations: Set<CacheTag>): Boolean =
        tags.any { tag -> invalidations.any { it.covers(tag) } }

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
                val diff = pageRead { changed() }
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
    internal val state get() = entry.state
    internal val cache get() = entry.cache
    internal val snapshotTags get() = entry.tags
    internal suspend fun sessionId() = cache.sessionId(entry.account, entry.sessionGeneration)
    internal fun active() = entry.isReusable && cache.isCurrent(entry.account, entry.sessionGeneration)

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
        val namespace = entry.key
        val resource =
            entry.cache.resource(
                entry.account,
                "paging:$namespace:$key",
                serializer,
                maxAgeMillis,
                entry.tags + tags,
            )
        ensureCurrent()
        val value = resource.await(force = force, loader = loader)
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return value
    }

    internal fun validFrame(sequence: Long): Boolean = active() && state.sequence == sequence

    internal fun persistenceEnabled(): Boolean =
        entry.cache.persistenceEnabled(entry.account, entry.sessionGeneration)

    fun revalidate(source: PagingSource<*, *>, changed: suspend () -> Boolean) {
        entry.startRevalidate(version, generation, source, changed)
    }
}

/** One deadline for the full cursor walk and mapping, not one timeout per HTTP call. */
suspend fun <T : Any> pageRead(load: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(30_000L) { load() } ?: throw SocketTimeoutException("Page read timed out")
    }

fun defaultPagingConfig(pageSize: Int = 30): PagingConfig =
    PagingConfig(
        pageSize = pageSize,
        initialLoadSize = pageSize,
        enablePlaceholders = false,
        maxSize = pageSize * 10,
    )
