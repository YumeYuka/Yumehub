package moe.yumeyuka.yumehub.data.cache

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缓存磁盘写入的单写队列。
 *
 * - 关键消息（失效屏障、marker）使用容量 128 的有界 Channel 挂起提交，绝不阻塞 UI 线程或丢弃。
 * - 可重建快照使用最多 128 个不同 key 的有界合并表保存最新尚未开始项，超额优先淘汰最早的可重建项。
 * - 每轮优先处理所有已就绪的关键消息，再处理一个快照。
 */
internal class CacheWriteQueue(private val scope: CoroutineScope) {
    private class CriticalWork(
        val block: suspend () -> Unit,
        val done: CompletableDeferred<Unit>,
    )

    private class SnapshotWork(val block: suspend () -> Unit)

    private val lock = Any()
    private val criticalChannel = Channel<CriticalWork>(MaxCritical)
    private val snapshots = LinkedHashMap<String, SnapshotWork>(16, 0.75f, false)
    private var unkeyedCounter = 0L
    private var draining = false
    private var closed = false

    internal val criticalExecuted = AtomicInteger(0)
    internal val snapshotSubmitted = AtomicInteger(0)
    internal val snapshotCoalesced = AtomicInteger(0)
    internal val snapshotExecuted = AtomicInteger(0)
    internal val snapshotEvicted = AtomicInteger(0)
    internal val lastCriticalQueueNanos = AtomicLong(0L)

    /** 可合并的后台快照写入；队列满时淘汰最早的可重建项。 */
    fun submit(coalesceKey: String?, block: suspend () -> Unit) {
        val start =
            synchronized(lock) {
                if (closed) return
                snapshotSubmitted.incrementAndGet()
                val key = coalesceKey ?: "\u0000anon:${++unkeyedCounter}"
                if (snapshots.remove(key) != null) {
                    snapshotCoalesced.incrementAndGet()
                }
                while (snapshots.size >= MaxSnapshots) {
                    val oldest = snapshots.keys.firstOrNull() ?: break
                    snapshots.remove(oldest)
                    snapshotEvicted.incrementAndGet()
                }
                snapshots[key] = SnapshotWork(block)
                if (!draining) {
                    draining = true
                    true
                } else false
            }
        if (start) launchDrain()
    }

    /** 关键写入：有界挂起入队；调用方等待其真正提交完成，失败向调用方抛出。 */
    suspend fun run(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        val enqueuedAt = System.nanoTime()
        criticalChannel.send(CriticalWork({
            lastCriticalQueueNanos.set(System.nanoTime() - enqueuedAt)
            block()
        }, done))
        val start =
            synchronized(lock) {
                if (closed) {
                    done.completeExceptionally(expiredSession())
                    false
                } else if (!draining) {
                    draining = true
                    true
                } else false
            }
        if (start) launchDrain()
        done.await()
    }

    fun close() {
        val pendingCritical = mutableListOf<CriticalWork>()
        synchronized(lock) {
            if (closed) return
            closed = true
            snapshots.clear()
        }
        criticalChannel.close(expiredSession())
        while (true) {
            val item = criticalChannel.tryReceive().getOrNull() ?: break
            pendingCritical.add(item)
        }
        pendingCritical.forEach { it.done.completeExceptionally(expiredSession()) }
    }

    private fun launchDrain() {
        scope.launch { withContext(dispatcher) { drain() } }
    }

    private suspend fun drain() {
        while (true) {
            val critical = criticalChannel.tryReceive().getOrNull()
            if (critical != null) {
                executeCritical(critical)
                continue
            }
            val snapshot =
                synchronized(lock) {
                    val nextKey = snapshots.keys.firstOrNull()
                    if (nextKey != null) {
                        snapshots.remove(nextKey)
                    } else {
                        val queuedCritical = criticalChannel.tryReceive().getOrNull()
                        if (queuedCritical != null) {
                            return@synchronized queuedCritical
                        }
                        draining = false
                        return
                    }
                }
            if (snapshot is CriticalWork) {
                executeCritical(snapshot)
            } else if (snapshot is SnapshotWork) {
                try {
                    snapshot.block()
                    snapshotExecuted.incrementAndGet()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun executeCritical(work: CriticalWork) {
        try {
            work.block()
            criticalExecuted.incrementAndGet()
            work.done.complete(Unit)
        } catch (error: CancellationException) {
            work.done.completeExceptionally(error)
            throw error
        } catch (error: Exception) {
            work.done.completeExceptionally(error)
        }
    }

    private companion object {
        const val MaxCritical = 128
        const val MaxSnapshots = 128
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
