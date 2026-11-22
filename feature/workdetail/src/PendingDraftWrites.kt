package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 页面主线程拥有待写集合；即使 VM 的 scope 已取消，退出时仍能提交最后一次输入。 */
internal class PendingDraftWrites(
    private val scope: CoroutineScope,
    private val save: (String, String) -> Unit,
) {
    private data class Pending(val body: String, val timer: Job)

    private val pending = mutableMapOf<String, Pending>()

    fun schedule(key: String, body: String) {
        discard(key)
        val timer = scope.launch {
            delay(400)
            save(key, body)
            pending.remove(key)
        }
        pending[key] = Pending(body, timer)
    }

    fun discard(key: String) {
        pending.remove(key)?.timer?.cancel()
    }

    fun flush(key: String) {
        val entry = pending.remove(key) ?: return
        entry.timer.cancel()
        save(key, entry.body)
    }

    fun flushAll() {
        for ((key, entry) in pending) {
            entry.timer.cancel()
            save(key, entry.body)
        }
        pending.clear()
    }
}
