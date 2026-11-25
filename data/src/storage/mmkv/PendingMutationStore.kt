package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 待核对写操作阶段；PREPARED 证明请求尚未发送。 */
enum class MarkerPhase { PREPARED, MAY_HAVE_SENT }

@Serializable
internal data class MutationMarker(
    val operationId: String,
    val kind: String,
    val target: String,
    /** 相关失效标签的磁盘表示（domain/类型/摘要），不含业务正文。 */
    val tags: List<String> = emptyList(),
    /** desired 的规范化 SHA-256；无法唯一读回的命令为 null。 */
    val desiredDigest: String? = null,
    val startedAt: Long,
    val phase: MarkerPhase,
)

/**
 * 会话级未确认写标记。只存操作类型、必要目标、标签摘要、desired 摘要和时间，不存 token、正文或可执行请求。
 *
 * 每账号最多 512 条；达到上限时拒绝新提交，绝不淘汰未知项。
 */
internal class PendingMutationStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getStorage("pending-mutations-v1") }
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, List<MutationMarker>>()

    suspend fun markers(account: String): List<MutationMarker> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                cache[key] ?: load(key).also { cache[key] = it }
            }
        }

    suspend fun put(account: String, marker: MutationMarker) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = cache[key] ?: load(key)
                val existing = current.indexOfFirst { it.operationId == marker.operationId }
                val next =
                    if (existing >= 0) current.toMutableList().also { it[existing] = marker }
                    else {
                        if (current.size >= MaxMarkers) throw MutationLimitException()
                        current + marker
                    }
                write(key, next)
            }
        }

    suspend fun updatePhase(account: String, operationId: String, phase: MarkerPhase) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = cache[key] ?: load(key)
                if (current.none { it.operationId == operationId }) return@withLock
                write(key, current.map { if (it.operationId == operationId) it.copy(phase = phase) else it })
            }
        }

    suspend fun remove(account: String, operationId: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                val current = cache[key] ?: load(key)
                if (current.none { it.operationId == operationId }) return@withLock
                write(key, current.filterNot { it.operationId == operationId })
            }
        }

    suspend fun clear(account: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = accountKey(account)
                storage.removeValueForKey(key)
                cache.remove(key)
            }
        }

    /** 将 marker 真正刷到磁盘；不宣称硬件掉电可证明事务一致。 */
    fun sync() {
        storage.sync()
    }

    /** 恢复失败必须向上抛出，绝不能把损坏的 marker 当成空列表悄悄丢弃。 */
    private fun load(key: String): List<MutationMarker> =
        storage.decodeString(key)?.let { json.decodeFromString(ListSerializer, it) } ?: emptyList()

    private fun write(key: String, markers: List<MutationMarker>) {
        check(storage.encode(key, json.encodeToString(ListSerializer, markers))) {
            "Unable to persist pending mutation"
        }
        cache[key] = markers
    }

    private fun accountKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(account.trim().lowercase().toByteArray(Charsets.UTF_8))
        return "pending:${digest.digest().toHexString()}"
    }

    private companion object {
        const val MaxMarkers = 512
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(MutationMarker.serializer())
    }
}

class MutationLimitException : Exception("Too many unconfirmed actions")
