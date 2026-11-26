package moe.yumeyuka.yumehub.data.cache

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

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
        val queuedAt: Long,
        val sync: (() -> Unit)?,
    )

    private val critical = Channel<CriticalWork>(128)
    private val ready = Channel<CriticalWork>(2)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val encoding = Channel<Unit>(2)
    private val snapshots = LinkedHashMap<String, suspend () -> (suspend () -> Unit)?>()
    private var closed = false
    internal val lastCriticalQueueNanos = AtomicLong()
    internal val lastSyncNanos = AtomicLong()
    internal val snapshotExecuted = AtomicInteger()

    init {
        repeat(2) {
            scope.launch(Dispatchers.IO) {
                for (signal in encoding) while (true) {
                    val prepare =
                        synchronized(snapshots) {
                            val iterator = snapshots.values.iterator()
                            if (iterator.hasNext()) iterator.next().also { iterator.remove() }
                            else null
                        } ?: break
                    try {
                        val commit = prepare() ?: continue
                        snapshotExecuted.incrementAndGet()
                        val done = CompletableDeferred<Unit>()
                        ready.send(CriticalWork(commit, done, 0L, null))
                        wake.trySend(Unit)
                        done.await()
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        // A rebuildable snapshot is never a prerequisite for a read or mutation.
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (signal in wake) {
                var next = critical.tryReceive().getOrNull()
                while (true) {
                    val work =
                        next
                            ?: critical.tryReceive().getOrNull()
                            ?: ready.tryReceive().getOrNull()
                            ?: break
                    next = null
                    if (work.queuedAt != 0L)
                        lastCriticalQueueNanos.set(System.nanoTime() - work.queuedAt)
                    val sync = work.sync
                    if (sync == null) {
                        if (execute(work)) work.done.complete(Unit)
                    } else {
                        val waiting = ArrayList<CriticalWork>()
                        var batch: CriticalWork? = work
                        do {
                            if (execute(batch!!)) waiting.add(batch)
                            batch = critical.tryReceive().getOrNull()
                        } while (batch != null && batch.sync === sync)
                        next = batch
                        if (waiting.isNotEmpty())
                            try {
                                val start = System.nanoTime()
                                sync()
                                lastSyncNanos.set(System.nanoTime() - start)
                                waiting.forEach { it.done.complete(Unit) }
                            } catch (error: Exception) {
                                waiting.forEach { it.done.completeExceptionally(error) }
                            }
                    }
                }
            }
        }
    }

    fun snapshot(key: String, prepare: suspend () -> (suspend () -> Unit)?) {
        synchronized(snapshots) {
            if (closed) return
            snapshots.remove(key)
            if (snapshots.size == 128) snapshots.remove(snapshots.keys.first())
            snapshots[key] = prepare
        }
        encoding.trySend(Unit)
    }

    suspend fun run(sync: (() -> Unit)? = null, block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        critical.send(CriticalWork(block, done, System.nanoTime(), sync))
        wake.trySend(Unit)
        done.await()
    }

    private suspend fun execute(work: CriticalWork): Boolean =
        try {
            work.block()
            true
        } catch (error: Exception) {
            work.done.completeExceptionally(error)
            currentCoroutineContext().ensureActive()
            false
        }

    fun close() {
        synchronized(snapshots) {
            closed = true
            snapshots.clear()
        }
        critical.close()
        ready.close()
        encoding.close()
        wake.close()
        for (channel in listOf(critical, ready)) while (true) {
            val work = channel.tryReceive().getOrNull() ?: break
            work.done.completeExceptionally(expiredSession())
        }
    }
}
