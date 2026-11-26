package moe.yumeyuka.yumehub.data.cache

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.repository.DashboardFeedException
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.WorkDetailCommentChangedException
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.data.storage.mmkv.MarkerPhase
import moe.yumeyuka.yumehub.data.storage.mmkv.MutationLimitException
import moe.yumeyuka.yumehub.data.storage.mmkv.MutationMarker
import moe.yumeyuka.yumehub.data.storage.mmkv.PendingMutationStore
import retrofit2.HttpException

/** 稳定序列化枚举：只能追加，不能重排或重命名。 */
enum class MutationKind {
    STAR,
    FOLLOW,
    REACTION,
    VIEWER_STATUS,
    DASHBOARD_PINS,
    REPO_SUBSCRIPTION,
    REPO_DESCRIPTION,
    REPO_LISTS,
    WORK_SUBSCRIPTION,
    NOTIFICATION,
    DISCUSSION_UPVOTE,
    DISCUSSION_ANSWER,
    DISCUSSION_VOTE,
    WORK_FIELD,
    ISSUE_FIELD,
    WORK_PROPERTY,
    COMMENT,
    REVIEW,
    MERGE,
    WORK_CREATE,
    DISCUSSION_CREATE,
    LIST_CREATE,
    FILE_COMMIT,
    FORK,
    WORKFLOW_DISPATCH,
    CHECK_RERUN,
    FEED_FILTERS,
    TWO_FACTOR_DECISION,
}

enum class WritePhase { IDLE, PENDING, UNCERTAIN, REJECTED }

/** 相同 kind/target 的 serializer 只用于校验数据类型，不构成第二个锁。 */
data class MutationKey<T : Any>(
    val kind: MutationKind,
    val target: String,
    val serializer: KSerializer<T>,
) {
    init {
        require(target.isNotBlank()) { "A mutation target is required" }
    }
}

data class WriteState<T : Any>(
    val confirmed: T? = null,
    val desired: T? = null,
    val phase: WritePhase = WritePhase.IDLE,
    val message: String? = null,
) {
    /** An uncertain intent is a hint, not an observed remote value. */
    val visible: T? get() = if (phase == WritePhase.PENDING) desired ?: confirmed else confirmed
}

data class CommandState<R : Any>(
    val result: R? = null,
    val phase: WritePhase = WritePhase.IDLE,
    val message: String? = null,
    val submissionId: String? = null,
)

data class PendingMutationSummary(
    val operationId: String,
    val kind: MutationKind,
    val target: String,
    val startedAtMillis: Long,
)

/** 区分“期望清空”与“无期望”，供可清空字段的投影使用。 */
@Serializable data class NullableValue<T : Any>(val value: T?)

/** 无返回实体的确认型命令，不把 Unit 或 null 当作服务端实体。 */
@Serializable data object CommandAccepted

data class MutationReadStamp
internal constructor(
    val sessionGeneration: Long,
    val kind: MutationKind,
    val target: String,
    val epoch: Long,
    val sequence: Long,
)

/** 服务端明确未执行（本地校验失败或响应证明没有写入）；超时、断线、5xx、解析失败都不是拒绝。 */
open class MutationRejectedException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

internal fun Throwable.isDefiniteMutationRejection(): Boolean =
    when (this) {
        is MutationRejectedException,
        is WorkDetailReviewChangedException,
        is WorkDetailCommentChangedException -> true
        is GithubPageException -> definiteRejection
        is moe.yumeyuka.yumehub.data.twofactor.TwoFactorException -> definiteRejection
        is HttpException -> code() in setOf(400, 401, 403, 404, 409, 422)
        else -> false
    }

/** Only cache entries actually updated by this server call participate in its durability fence. */
internal class ConfirmedWrites(val operationId: String) {
    var checkpointed = false
    private val entries = mutableSetOf<CachedResource<*>>()
    fun record(resource: CachedResource<*>) { synchronized(entries) { entries.add(resource) } }
    private var cleanups: MutableList<() -> Unit>? = null
    fun onCommit(cleanup: () -> Unit) {
        synchronized(this) { (cleanups ?: mutableListOf<() -> Unit>().also { cleanups = it }).add(cleanup) }
    }
    fun committed() { synchronized(this) { cleanups }?.forEach { it() } }
    suspend fun persist() {
        for (entry in synchronized(entries) { entries.toList() }) entry.persistConfirmed()
    }
    companion object { val current = ThreadLocal<ConfirmedWrites?>() }
}

/**
 * 会话级写状态与未确认操作恢复。
 *
 * 乐观投影只存在这里；UI 订阅对应 slot。请求、marker 写入和缓存失效屏障都在会话 scope 内串行依赖；
 * 页面离开不取消，账号换代由 [CachedDataRepository.isCurrent] 校验。
 */
class MutationCoordinator
internal constructor(
    private val account: String,
    private val generation: Long,
    private val cache: CachedDataRepository,
    private val store: PendingMutationStore,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private open class ObservedSlot {
        var retained = false
        var readers = 0
    }

    private class Slot<T : Any>(val key: MutationKey<T>) : ObservedSlot() {
        val state = MutableStateFlow(WriteState<T>())
        var epoch = 0L
        var readSequence = 0L
        var confirmedKnown = false
        var requiresFreshRead = false
        var inFlight = false
        var operationId: String? = null
        var tags: Set<CacheTag> = emptySet()
        var markerTags: List<String> = emptyList()
        var startedAt: Long = 0L
        var pending: Pending<T>? = null
        var reconciling: Job? = null
    }

    private class Pending<T : Any>(
        val desired: T,
        val tags: Set<CacheTag>,
        val send: suspend (T) -> T,
        val readBack: suspend () -> T,
    )

    private class CommandSlot<R : Any>(val key: MutationKey<R>) : ObservedSlot() {
        val state = MutableStateFlow(CommandState<R>())
        var inFlight = false
        var operationId: String? = null
        var tags: Set<CacheTag> = emptySet()
        var markerTags: List<String> = emptyList()
        var startedAt: Long = 0L
        var pending: CommandPending<R>? = null
        var reconciling: Job? = null
    }

    private class CommandPending<R : Any>(
        val id: String,
        val tags: Set<CacheTag>,
        val result: CompletableDeferred<CommandState<R>>,
        val onConfirmed: ((R) -> Unit)?,
        val send: suspend () -> R,
        val readBack: suspend () -> R?,
    )

    private val lock = Any()
    private val syncMarkers: () -> Unit = store::sync
    private val slots = mutableMapOf<String, Slot<*>>()
    private val commands = mutableMapOf<String, CommandSlot<*>>()
    // 恢复出的未知项在页面首次订阅前也必须出现在待核对列表。
    private val recovered = mutableMapOf<String, MutationMarker>()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    private val mutableRecoveryFailed = MutableStateFlow(false)
    val recoveryFailed: StateFlow<Boolean> = mutableRecoveryFailed.asStateFlow()
    private val mutablePending = MutableStateFlow<List<PendingMutationSummary>>(emptyList())
    val pending: StateFlow<List<PendingMutationSummary>> = mutablePending.asStateFlow()
    private var recovering: Job? = null

    init {
        startRecovery()
    }

    fun <T : Any> observe(key: MutationKey<T>): StateFlow<WriteState<T>> = slot(key).state

    fun <T : Any> current(key: MutationKey<T>): WriteState<T> = slot(key).state.value

    fun <R : Any> observeCommand(key: MutationKey<R>): StateFlow<CommandState<R>> =
        commandSlot(key).state

    fun <R : Any> currentCommand(key: MutationKey<R>): CommandState<R> =
        commandSlot(key).state.value

    /** Scrolling past a read-only row must not retain a write slot for the whole session. */
    internal fun <T : Any> watch(key: MutationKey<T>): Flow<WriteState<T>> =
        watchSlot({ slot(key, retain = false) }, { it.state }, { slots.remove(slotKey(key.kind, key.target)) })

    internal fun <T : Any> watchCommand(key: MutationKey<T>): Flow<CommandState<T>> =
        watchSlot({ commandSlot(key, retain = false) }, { it.state }, { commands.remove(slotKey(key.kind, key.target)) })

    private fun <S : ObservedSlot, V> watchSlot(get: () -> S, state: (S) -> StateFlow<V>, remove: () -> Unit): Flow<V> = flow {
        val holder = synchronized(lock) { get().also { it.readers++ } }
        try {
            emitAll(state(holder))
        } finally {
            synchronized(lock) { if (--holder.readers == 0 && !holder.retained) remove() }
        }
    }

    /** Dynamic field definitions are only known after the query. Never stamp a slot after I/O. */
    internal fun beginKnownReads(kind: MutationKind, prefix: String): Map<String, MutationReadStamp> =
        synchronized(lock) {
            slots.values.filter { it.key.kind == kind && it.key.target.startsWith(prefix) }
                .associate { it.key.target to beginRead(it.key) }
        }

    /**
     * 同步登记并立即投影 desired；返回 false 表示因未知结果未发送。
     * 真正的网络发送、marker 与失效屏障都在会话 scope 中串行完成。
     */
    fun <T : Any> submit(
        key: MutationKey<T>,
        confirmed: T?,
        desired: T,
        tags: Set<CacheTag>,
        send: suspend (T) -> T,
        readBack: suspend () -> T,
    ): Boolean {
        if (!isCurrent()) return false
        val slot = slot(key)
        return synchronized(slot) {
            if (slot.state.value.phase == WritePhase.UNCERTAIN) {
                // The previous action may still complete. An unaccepted tap is not a new intent.
                return@synchronized false
            }
            if (!slot.confirmedKnown && !slot.requiresFreshRead && confirmed != null) {
                slot.confirmedKnown = true
                slot.state.value = slot.state.value.copy(confirmed = confirmed)
            }
            slot.epoch++
            slot.tags = tags
            slot.startedAt = System.currentTimeMillis()
            val pending = Pending(desired, tags, send, readBack)
            if (slot.inFlight) {
                // 同一 key 最多一个在途；只保留最新 desired，确认后再发。
                slot.pending = pending
                slot.state.value =
                    slot.state.value.copy(desired = desired, phase = WritePhase.PENDING, message = null)
                return@synchronized true
            }
            if (mutableRecoveryFailed.value) {
                slot.pending = null
                slot.state.value =
                    WriteState(
                        confirmed = slot.state.value.confirmed,
                        desired = null,
                        phase = WritePhase.REJECTED,
                        message = MutationRecoveryFailed,
                    )
                return@synchronized false
            }
            slot.pending = pending
            slot.state.value =
                WriteState(
                    confirmed = slot.state.value.confirmed,
                    desired = desired,
                    phase = WritePhase.PENDING,
                    message = null,
                )
            if (!mutableReady.value) {
                // 恢复完成后再决定是否发送，避免恢复出未知结果时误发。
                return@synchronized true
            }
            start(slot)
            true
        }
    }

    /** The slot describes the target; the returned receipt settles exactly this submission, even after another writer arrives. */
    fun <R : Any> submitCommand(
        key: MutationKey<R>,
        tags: Set<CacheTag>,
        onConfirmed: ((R) -> Unit)? = null,
        send: suspend () -> R,
        readBack: suspend () -> R?,
    ): Deferred<CommandState<R>>? {
        if (!isCurrent()) return null
        val slot = commandSlot(key)
        return synchronized(slot) {
            if (slot.state.value.phase == WritePhase.UNCERTAIN || slot.operationId != null || slot.inFlight || slot.pending != null) return@synchronized null
            val submissionId = UUID.randomUUID().toString()
            if (mutableRecoveryFailed.value) {
                slot.pending = null
                slot.state.value =
                    CommandState(
                        phase = WritePhase.REJECTED,
                        message = MutationRecoveryFailed,
                        submissionId = submissionId,
                    )
                return@synchronized null
            }
            slot.tags = tags
            slot.startedAt = System.currentTimeMillis()
            val result = CompletableDeferred<CommandState<R>>(scope.coroutineContext[Job])
            slot.pending = CommandPending(submissionId, tags, result, onConfirmed, send, readBack)
            slot.state.value = CommandState(phase = WritePhase.PENDING, submissionId = submissionId)
            if (mutableReady.value) startCommand(slot)
            result
        }
    }

    /** 强制只读核对；同一 key 去重。读回值不自动证明写请求已执行。 */
    fun <T : Any> reconcile(key: MutationKey<T>, readBack: suspend () -> T): Job {
        val slot = slot(key)
        synchronized(slot) {
            slot.reconciling?.takeIf { it.isActive }?.let { return it }
            val job =
                scope.launch {
                    if (!isCurrent()) return@launch
                    val stamp = beginRead(key)
                    val value =
                        try {
                            readBack()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        } ?: return@launch
                    publishConfirmed(key, stamp, value)
                }
            slot.reconciling = job
            return job
        }
    }

    fun <R : Any> reconcileCommand(key: MutationKey<R>, readBack: suspend () -> R?): Job {
        val slot = commandSlot(key)
        synchronized(slot) {
            slot.reconciling?.takeIf { it.isActive }?.let { return it }
            val job =
                scope.launch {
                    if (!isCurrent()) return@launch
                    val value =
                        try {
                            readBack()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        } ?: return@launch
                    synchronized(slot) {
                        if (slot.state.value.phase == WritePhase.UNCERTAIN) {
                            slot.state.value = slot.state.value.copy(result = value)
                        }
                    }
                }
            slot.reconciling = job
            return job
        }
    }

    /** 人工确认：先补失效屏障并同步两个缓存索引，再删除 marker 并同步；失败保留提示。 */
    fun acknowledge(operationId: String): Job = scope.launch {
        val slot =
            synchronized(lock) {
                slots.values.firstOrNull { it.operationId == operationId }
            }
        val command =
            synchronized(lock) {
                commands.values.firstOrNull { it.operationId == operationId }
            }
        val marker =
            synchronized(lock) {
                recovered.entries.firstOrNull { it.value.operationId == operationId }?.value
            }
        if (!isCurrent() || (marker == null && slot?.state?.value?.phase != WritePhase.UNCERTAIN &&
                command?.state?.value?.phase != WritePhase.UNCERTAIN)) return@launch
        val tags =
            synchronized(lock) {
                slot?.tags.orEmpty() + command?.tags.orEmpty()
            }
        try {
            if (tags.isNotEmpty()) cache.persistInvalidation(account, tags)
            val markerTags = marker?.tags ?: slot?.markerTags ?: command?.markerTags
            if (!markerTags.isNullOrEmpty()) {
                cache.writeQueue.run {
                    cache.invalidationStore.markDigests(account, markerTags, System.currentTimeMillis())
                }
            }
            cache.writeQueue.run {
                cache.syncCacheStores()
                store.remove(account, operationId)
                store.sync()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            @Suppress("UNCHECKED_CAST")
            val known = slot as? Slot<Any>
            if (known != null) {
                synchronized(known) {
                    known.state.value =
                        known.state.value.copy(
                            message = error.message ?: MutationNotSubmitted,
                        )
                }
            }
            @Suppress("UNCHECKED_CAST")
            val knownCmd = command as? CommandSlot<Any>
            if (knownCmd != null) {
                synchronized(knownCmd) {
                    knownCmd.state.value =
                        knownCmd.state.value.copy(
                            message = error.message ?: MutationNotSubmitted,
                        )
                }
            }
            return@launch
        }
        synchronized(lock) {
            recovered.entries.removeIf { it.value.operationId == operationId }
            slots.values
                .filter { it.operationId == operationId }
                .forEach { known ->
                    synchronized(known) {
                        known.operationId = null
                        known.pending = null
                        known.epoch++
                        known.confirmedKnown = false
                        known.requiresFreshRead = true
                        @Suppress("UNCHECKED_CAST")
                        val typed = known as Slot<Any>
                        typed.state.value = WriteState()
                    }
                }
            commands.values
                .filter { it.operationId == operationId }
                .forEach { known ->
                    synchronized(known) {
                        known.operationId = null
                        known.pending = null
                        @Suppress("UNCHECKED_CAST")
                        (known as CommandSlot<Any>).state.value = CommandState()
                    }
                }
        }
        publishPending()
    }

    fun retryRecovery(): Job {
        synchronized(lock) {
            recovering?.takeIf { it.isActive }?.let { return it }
        }
        return startRecovery()
    }

    fun <T : Any> beginRead(key: MutationKey<T>): MutationReadStamp {
        val slot = slot(key)
        return synchronized(slot) {
            MutationReadStamp(generation, key.kind, key.target, slot.epoch, ++slot.readSequence)
        }
    }

    /** A verified intermediate response owns only the active request's baseline, not a newer intent. */
    fun <T : Any> confirmStep(key: MutationKey<T>, confirmed: T) {
        val writes = checkNotNull(ConfirmedWrites.current.get())
        if (!isCurrent()) throw expiredSession()
        val slot = slot(key)
        synchronized(slot) {
            check(slot.operationId == writes.operationId && slot.inFlight)
            slot.epoch++
            slot.confirmedKnown = true
            slot.state.value = slot.state.value.copy(confirmed = confirmed)
            writes.checkpointed = true
        }
    }

    /** Hydration may seed a new slot, but never supersedes a network read or a user intent. */
    fun <T : Any> seedFromCache(key: MutationKey<T>, value: T) {
        if (!isCurrent()) return
        val slot = slot(key)
        synchronized(slot) {
            if (slot.epoch == 0L && !slot.confirmedKnown && slot.state.value.phase == WritePhase.IDLE) {
                slot.confirmedKnown = true
                slot.state.value = slot.state.value.copy(confirmed = value)
            }
        }
    }

    fun <T : Any> publishConfirmed(
        key: MutationKey<T>,
        stamp: MutationReadStamp,
        value: T,
    ): Boolean {
        if (!isCurrent() || stamp.sessionGeneration != generation) return false
        val slot = slot(key)
        return synchronized(slot) {
            if (
                stamp.kind != key.kind ||
                    stamp.target != key.target ||
                    stamp.epoch != slot.epoch ||
                    stamp.sequence != slot.readSequence
            )
                return@synchronized false
            slot.confirmedKnown = true
            slot.requiresFreshRead = false
            slot.state.value = slot.state.value.copy(confirmed = value)
            true
        }
    }

    private fun startRecovery(): Job =
        synchronized(lock) {
            recovering =
                scope.launch {
                    try {
                        val markers = store.markers(account)
                        markers
                            .filter { it.phase == MarkerPhase.PREPARED }
                            .forEach { cache.writeQueue.run { store.remove(account, it.operationId) } }
                        val unconfirmed =
                            markers
                                .filter { it.phase == MarkerPhase.MAY_HAVE_SENT }
                                .sortedBy { it.startedAt }
                        if (unconfirmed.isNotEmpty()) {
                            val at = System.currentTimeMillis().coerceAtLeast(1L)
                            val digests = unconfirmed.flatMap { it.tags }.distinct()
                            if (digests.isNotEmpty()) {
                                cache.writeQueue.run {
                                    cache.invalidationStore.markDigests(account, digests, at)
                                }
                            }
                            unconfirmed.forEach { marker ->
                                val kind =
                                    MutationKind.entries.firstOrNull { it.name == marker.kind }
                                        ?: return@forEach
                                val id = slotKey(kind, marker.target)
                                synchronized(lock) {
                                    recovered[id] = marker
                                    slots[id]?.let { slot ->
                                        synchronized(slot) { applyRecovered(slot, marker) }
                                    }
                                    commands[id]?.let { cmd ->
                                        synchronized(cmd) { applyRecoveredCommand(cmd, marker) }
                                    }
                                }
                            }
                        }
                        mutableRecoveryFailed.value = false
                        mutableReady.value = true
                        publishPending()
                        flushPending()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        synchronized(lock) {
                            slots.values.forEach { slot ->
                                synchronized(slot) {
                                    slot.pending = null
                                    if (slot.state.value.phase == WritePhase.PENDING) {
                                        @Suppress("UNCHECKED_CAST")
                                        (slot as Slot<Any>).state.value =
                                            (slot as Slot<Any>).state.value.copy(
                                                desired = null,
                                                phase = WritePhase.REJECTED,
                                                message = MutationRecoveryFailed,
                                            )
                                    }
                                }
                            }
                            commands.values.forEach { cmd ->
                                synchronized(cmd) {
                                    if (cmd.state.value.phase == WritePhase.PENDING) {
                                        @Suppress("UNCHECKED_CAST")
                                        val typed = cmd as CommandSlot<Any>
                                        typed.state.value = typed.state.value.copy(
                                            phase = WritePhase.REJECTED,
                                            message = MutationRecoveryFailed,
                                        )
                                        typed.pending?.result?.complete(typed.state.value)
                                    }
                                    cmd.pending = null
                                }
                            }
                        }
                        mutableRecoveryFailed.value = true
                        mutableReady.value = true
                        publishPending()
                    }
                }
            recovering!!
        }

    private fun <T : Any> applyRecovered(slot: Slot<T>, marker: MutationMarker) {
        slot.operationId = marker.operationId
        slot.confirmedKnown = false
        slot.pending = null
        slot.markerTags = marker.tags
        slot.startedAt = marker.startedAt
        slot.state.value =
            WriteState(phase = WritePhase.UNCERTAIN, message = MutationUncertain)
    }

    private fun <R : Any> applyRecoveredCommand(slot: CommandSlot<R>, marker: MutationMarker) {
        slot.operationId = marker.operationId
        slot.markerTags = marker.tags
        slot.startedAt = marker.startedAt
        slot.state.value =
            CommandState(phase = WritePhase.UNCERTAIN, message = MutationUncertain, submissionId = marker.operationId)
        slot.pending?.result?.complete(slot.state.value)
        slot.pending = null
    }

    private fun <T : Any> start(slot: Slot<T>) {
        val pending = synchronized(slot) {
            if (slot.inFlight || slot.operationId != null) return
            val next = slot.pending ?: return
            slot.pending = null
            slot.inFlight = true
            next
        }
        scope.launch {
            var latest: Pending<T>? = pending
            while (latest != null) {
                var stopLoop = false
                try {
                    stopLoop = !perform(slot, latest)
                } catch (error: CancellationException) {
                    synchronized(slot) { slot.inFlight = false; slot.pending = null }
                    if (isCurrent() && slot.operationId == null) reject(slot, error)
                    throw error
                } catch (error: Exception) {
                    if (error.isDefiniteMutationRejection()) {
                        reject(slot, error)
                    } else {
                        markUnknown(slot, latest)
                    }
                    stopLoop = true
                }
                latest =
                    synchronized(slot) {
                        slot.inFlight = false
                        if (stopLoop) {
                            slot.pending = null
                            null
                        } else {
                            val next = slot.pending
                            slot.pending = null
                            if (next != null) {
                                if (slot.confirmedKnown && next.desired == slot.state.value.confirmed) {
                                    slot.state.value =
                                        slot.state.value.copy(
                                            desired = null,
                                            phase = WritePhase.IDLE,
                                            message = null,
                                        )
                                    null
                                } else {
                                    slot.inFlight = true
                                    next
                                }
                            } else {
                                slot.state.value = slot.state.value.copy(desired = null, phase = WritePhase.IDLE)
                                null
                            }
                        }
                    }
            }
            publishPending()
        }
    }

    private suspend fun <T : Any> perform(slot: Slot<T>, pending: Pending<T>): Boolean {
        if (!isCurrent()) return false
        if (synchronized(slot) { !slot.confirmedKnown || slot.requiresFreshRead }) {
            // 首次无 confirmed：立即显示提交中，强读 baseline；读失败不发送。
            val stamp = beginRead(slot.key)
            val baseline =
                try {
                    pending.readBack()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reject(slot, error)
                    return false
                }
            if (!isCurrent()) return false
            if (!publishConfirmed(slot.key, stamp, baseline)) {
                if (synchronized(slot) { slot.pending != null }) return true
                if (synchronized(slot) { !slot.confirmedKnown }) {
                    reject(slot, MutationRejectedException(MutationNotSubmitted))
                    return false
                }
            }
        }
        val key = slot.key
        val operationId = UUID.randomUUID().toString()
        val marker =
            MutationMarker(
                operationId = operationId,
                kind = key.kind.name,
                target = key.target,
                tags = pending.tags.map { it.diskValue() },
                desiredDigest = digest(pending.desired, key.serializer),
                startedAt = System.currentTimeMillis(),
                phase = MarkerPhase.PREPARED,
            )
        val prepared =
            try {
                cache.writeQueue.run { store.put(account, marker) }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reject(slot, error)
                false
            }
        if (!prepared) return false
        synchronized(slot) {
            slot.operationId = operationId
            slot.markerTags = marker.tags
        }
        try {
            // 发送前必须等失效屏障持久完成。
            cache.prepareInvalidation(account, pending.tags)
            cache.writeQueue.run(syncMarkers) {
                store.updatePhase(account, operationId, MarkerPhase.MAY_HAVE_SENT)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 屏障/marker 未完成：请求未发送，回滚并明确失败。
            cache.writeQueue.run { store.remove(account, operationId) }
            synchronized(slot) { slot.operationId = null }
            reject(slot, error)
            return false
        }
        val writes = ConfirmedWrites(operationId)
        val confirmed =
            try {
                withContext(ConfirmedWrites.current.asContextElement(writes)) { pending.send(pending.desired) }
            } catch (error: CancellationException) {
                // 发送后取消：结果未知，保留 marker。
                markUnknown(slot, pending)
                throw error
            } catch (error: Exception) {
                if (writes.checkpointed && isCurrent()) {
                    cache.invalidate(account, pending.tags)
                    publish(pending.tags)
                }
                if (error.isDefiniteMutationRejection()) {
                    if (writes.checkpointed) {
                        writes.persist()
                        finishMarker(operationId, pending.tags)
                    } else cache.writeQueue.run(syncMarkers) { store.remove(account, operationId) }
                    synchronized(slot) { slot.operationId = null }
                    reject(slot, error)
                    return false
                }
                markUnknown(slot, pending)
                return false
            }
        if (!isCurrent()) return false
        synchronized(slot) {
            slot.epoch++
            slot.confirmedKnown = true
            val queued = slot.pending?.desired?.takeIf { it != confirmed }
            slot.state.value = WriteState(confirmed, queued, WritePhase.PENDING)
        }
        cache.invalidate(account, pending.tags)
        publish(pending.tags)
        try {
            writes.persist()
            finishMarker(operationId, pending.tags)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            synchronized(slot) {
                slot.pending = null
                slot.state.value = WriteState(confirmed = confirmed, phase = WritePhase.UNCERTAIN, message = MutationUncertain)
            }
            publishPending()
            return false
        }
        synchronized(slot) { slot.operationId = null }
        writes.committed()
        return true
    }

    private suspend fun finishMarker(operationId: String, tags: Set<CacheTag>) {
        cache.writeQueue.run {
            if (tags.isNotEmpty()) cache.invalidationStore.mark(account, tags, System.currentTimeMillis().coerceAtLeast(1L))
            cache.syncCacheStores()
            store.remove(account, operationId)
            store.sync()
        }
    }

    private fun <T : Any> markUnknown(slot: Slot<T>, pending: Pending<T>) {
        synchronized(slot) {
            slot.state.value =
                WriteState(
                    confirmed = slot.state.value.confirmed,
                    desired = slot.pending?.desired ?: pending.desired,
                    phase = WritePhase.UNCERTAIN,
                    message = MutationUncertain,
                )
        }
        publishPending()
        if (isCurrent()) reconcile(slot.key, pending.readBack)
    }

    private fun <T : Any> reject(slot: Slot<T>, error: Exception) {
        val message =
            when (error) {
                is MutationLimitException -> "$MutationNotSubmitted: $MutationLimit"
                else -> error.message ?: MutationNotSubmitted
            }
        synchronized(slot) {
            val unsent = slot.pending?.desired
            slot.pending = null
            slot.operationId = null
            slot.epoch++
            slot.state.value =
                WriteState(
                    confirmed = slot.state.value.confirmed,
                    desired = unsent,
                    phase = WritePhase.REJECTED,
                    message = message,
                )
        }
        publishPending()
    }

    private fun <R : Any> startCommand(slot: CommandSlot<R>) {
        val pending = synchronized(slot) {
            if (slot.inFlight || slot.operationId != null) return
            val next = slot.pending ?: return
            slot.pending = null
            slot.inFlight = true
            next
        }
        scope.launch {
            val id = pending.id
            var prepared = false
            var mayHaveSent = false
            var committed = false
            var confirmed: R? = null
            fun finish(phase: WritePhase, message: String? = null) {
                synchronized(slot) {
                    if (phase != WritePhase.UNCERTAIN) slot.operationId = null
                    slot.inFlight = false
                    slot.state.value = CommandState(confirmed, phase, message, id)
                    pending.result.complete(slot.state.value)
                }
            }
            try {
                if (!isCurrent()) return@launch
                val marker = MutationMarker(id, slot.key.kind.name, slot.key.target,
                    pending.tags.map { it.diskValue() }, null, System.currentTimeMillis(), MarkerPhase.PREPARED)
                cache.writeQueue.run { store.put(account, marker) }
                prepared = true
                synchronized(slot) {
                    slot.operationId = id
                    slot.markerTags = marker.tags
                    slot.startedAt = marker.startedAt
                }
                cache.prepareInvalidation(account, pending.tags)
                cache.writeQueue.run(syncMarkers) { store.updatePhase(account, id, MarkerPhase.MAY_HAVE_SENT) }
                val writes = ConfirmedWrites(id)
                mayHaveSent = true
                confirmed = withContext(ConfirmedWrites.current.asContextElement(writes)) { pending.send() }
                if (!isCurrent()) return@launch
                cache.invalidate(account, pending.tags)
                publish(pending.tags)
                writes.persist()
                finishMarker(id, pending.tags)
                writes.committed()
                committed = true
            } catch (error: CancellationException) {
                if (isCurrent()) finish(if (prepared) WritePhase.UNCERTAIN else WritePhase.REJECTED,
                    if (prepared) MutationUncertain else MutationNotSubmitted)
                throw error
            } catch (error: Exception) {
                val rejected = !mayHaveSent || confirmed == null && error.isDefiniteMutationRejection()
                val cleared = if (rejected && prepared) {
                    try {
                        cache.writeQueue.run(syncMarkers) { store.remove(account, id) }
                        true
                    } catch (cancel: CancellationException) {
                        if (isCurrent()) finish(WritePhase.UNCERTAIN, MutationNotSubmitted)
                        throw cancel
                    } catch (_: Exception) { false }
                } else !prepared
                if (rejected && cleared) {
                    finish(WritePhase.REJECTED, if (error is MutationLimitException) "$MutationNotSubmitted: $MutationLimit"
                        else error.message ?: MutationNotSubmitted)
                } else {
                    finish(WritePhase.UNCERTAIN, if (rejected) MutationNotSubmitted else MutationUncertain)
                    if (!rejected && confirmed == null && isCurrent()) reconcileCommand(slot.key, pending.readBack)
                }
            } finally {
                if (committed) {
                    try { pending.onConfirmed?.invoke(checkNotNull(confirmed)) }
                    finally { finish(WritePhase.IDLE); publishPending() }
                } else publishPending()
            }
        }
    }

    private fun flushPending() {
        val (targets, cmdTargets) =
            synchronized(lock) { slots.values.toList() to commands.values.toList() }
        targets.forEach { slot ->
            val shouldStart =
                synchronized(slot) {
                    !slot.inFlight && slot.operationId == null && slot.pending != null
                }
            if (shouldStart) {
                @Suppress("UNCHECKED_CAST") start(slot as Slot<Any>)
            }
        }
        cmdTargets.forEach { slot ->
            val shouldStart =
                synchronized(slot) {
                    !slot.inFlight && slot.operationId == null && slot.pending != null
                }
            if (shouldStart) {
                @Suppress("UNCHECKED_CAST") startCommand(slot as CommandSlot<Any>)
            }
        }
    }

    private fun publish(tags: Set<CacheTag>) {
        val domains = tags.map { it.domain }.distinct()
        if (domains.isNotEmpty()) cache.changes(account).publish(*domains.toTypedArray())
    }

    private fun publishPending() {
        synchronized(lock) {
            val live =
                slots.values.mapNotNull { slot ->
                    if (slot.state.value.phase != WritePhase.UNCERTAIN) return@mapNotNull null
                    val operationId = slot.operationId ?: return@mapNotNull null
                    PendingMutationSummary(
                        operationId,
                        slot.key.kind,
                        slot.key.target,
                        slot.startedAt,
                    )
                }
            val liveCommands =
                commands.values.mapNotNull { cmd ->
                    if (cmd.state.value.phase != WritePhase.UNCERTAIN) return@mapNotNull null
                    val operationId = cmd.operationId ?: return@mapNotNull null
                    PendingMutationSummary(
                        operationId,
                        cmd.key.kind,
                        cmd.key.target,
                        cmd.startedAt,
                    )
                }
            val recoveredOnly =
                recovered
                    .filterKeys { it !in slots.keys && it !in commands.keys }
                    .values
                    .map { marker ->
                        val kind =
                            MutationKind.entries.firstOrNull { entry -> entry.name == marker.kind }
                                ?: MutationKind.COMMENT
                        PendingMutationSummary(
                            marker.operationId,
                            kind,
                            marker.target,
                            marker.startedAt,
                        )
                    }
            mutablePending.value =
                (live + liveCommands + recoveredOnly).sortedWith(
                    compareBy({ it.startedAtMillis }, { it.operationId }),
                )
        }
    }

    private fun <T : Any> slot(key: MutationKey<T>, retain: Boolean = true): Slot<T> =
        synchronized(lock) {
            val id = slotKey(key.kind, key.target)
            val existing = slots[id]
            val slot =
                if (existing != null) {
                    require(existing.key.serializer.descriptor == key.serializer.descriptor) {
                        "The same mutation target must use the same serializer"
                    }
                    @Suppress("UNCHECKED_CAST") (existing as Slot<T>)
                } else {
                    Slot(key).also { slots[id] = it }
                }
            if (retain) slot.retained = true
            recovered[id]?.let { marker ->
                if (slot.state.value.phase != WritePhase.UNCERTAIN) {
                    synchronized(slot) { applyRecovered(slot, marker) }
                }
            }
            slot
        }

    private fun <R : Any> commandSlot(key: MutationKey<R>, retain: Boolean = true): CommandSlot<R> =
        synchronized(lock) {
            val id = slotKey(key.kind, key.target)
            val existing = commands[id]
            val slot =
                if (existing != null) {
                    require(existing.key.serializer.descriptor == key.serializer.descriptor) {
                        "The same mutation target must use the same serializer"
                    }
                    @Suppress("UNCHECKED_CAST") (existing as CommandSlot<R>)
                } else {
                    CommandSlot(key).also { commands[id] = it }
                }
            if (retain) slot.retained = true
            recovered[id]?.let { marker ->
                if (slot.state.value.phase != WritePhase.UNCERTAIN) {
                    synchronized(slot) { applyRecoveredCommand(slot, marker) }
                }
            }
            slot
        }

    private fun slotKey(kind: MutationKind, target: String): String = "${kind.name}\u0000$target"

    private fun isCurrent(): Boolean = cache.isCurrent(account, generation)

    private fun <T : Any> digest(value: T, serializer: KSerializer<T>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            .toHexString()

    internal companion object {
        const val MutationUncertain = "Result unconfirmed"
        const val MutationNotSubmitted = "Not submitted"
        const val MutationLimit = "Review unconfirmed actions first"
        const val MutationRecoveryFailed = "Not submitted: unable to recover unconfirmed actions"
    }
}
