package moe.yumeyuka.yumehub.data.repository

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

private val exploreTraceId = AtomicInteger()

/** 系统追踪中区分请求等待与节点映射，不记录账号、正文、游标或认证数据。 */
internal suspend fun <T> traceExplore(stage: String, block: suspend () -> T): T {
    if (!runCatching { Trace.isEnabled() }.getOrDefault(false)) return block()
    val id = exploreTraceId.incrementAndGet()
    Trace.beginAsyncSection(stage, id)
    return try {
        block()
    } finally {
        Trace.endAsyncSection(stage, id)
    }
}
