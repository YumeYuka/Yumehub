package moe.yumeyuka.yumehub.data.cache

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred

/** 与 TokenStore 共用的同步屏障，不依赖可能合并中间值的 Flow 通知。 */
internal class CacheSession {
    val lock = Any()
    var generation = 0L
        private set

    var id: String? = null
        private set

    var account: String? = null
        private set

    var accountReady = CompletableDeferred<String>()
        private set

    /** 旧账号列表缺会话 ID 且迁移保存失败时，本进程禁用持久响应/分页缓存。 */
    var persistenceEnabled = true

    private var signedOut = false
    val listeners = mutableSetOf<() -> Unit>()

    // 以下操作均由调用方持有 lock；启动恢复不换代，允许提前创建的资源认领会话。
    fun restore(login: String, sessionId: String) {
        if (id != null) return
        id = sessionId
        account = normalizeAccount(login).ifBlank { null }
        account?.let { accountReady.complete(it) }
        listeners.toList().forEach { it() }
    }

    fun bind(login: String, nextId: () -> String, renew: Boolean = false) {
        val normalized =
            normalizeAccount(login).ifBlank {
                return
            }
        if (account == normalized && !renew) return
        // 空账号首次绑定也换 ID，隔离退出时元数据写入失败留下的旧快照。
        // 不增加 generation，使启动阶段已创建的同账号 placeholder 仍能认领会话。
        id = nextId()
        if (account != null) {
            generation++
            accountReady.cancel(expiredSession())
            accountReady = CompletableDeferred()
        }
        account = normalized
        signedOut = false
        accountReady.complete(normalized)
        listeners.toList().forEach { it() }
    }

    fun clear(nextId: String) {
        id = nextId
        account = null
        generation++
        signedOut = true
        accountReady.cancel(expiredSession())
        accountReady = CompletableDeferred()
        listeners.toList().forEach { it() }
    }

    fun accepts(login: String, version: Long): Boolean =
        generation == version && !signedOut && (account == null || account == login)
}

internal fun normalizeAccount(account: String): String = account.trim().lowercase(Locale.ROOT)
