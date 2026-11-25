package moe.yumeyuka.yumehub.data.storage.mmkv

import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** 评论草稿与凭据分库存储，账号与目标共同决定隔离键。 */
class CommentDraftStore(provider: MMKVProvider) {
    private val operations = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        // Store is a Koin singleton. Writes accepted on route teardown must outlive the ViewModel.
        CoroutineScope(Dispatchers.IO).launch {
            for (operation in operations) operation()
        }
    }

    private val storage by lazy { provider.getStorage("comment-drafts") }

    suspend fun load(account: String, target: String): String = loadOrNull(account, target).orEmpty()

    suspend fun loadOrNull(account: String, target: String): String? =
        suspendCancellableCoroutine { result ->
            operations.trySend {
                if (result.isActive) {
                    result.resume(access { storage.decodeString(key(account, target)) })
                }
            }
        }

    fun save(account: String, target: String, body: String) {
        operations.trySend { access { storage.encode(key(account, target), body) } }
    }

    /** 接受提交时等待同一草稿写入 actor 真正把完整草稿 encode 到磁盘后再返回。 */
    suspend fun saveConfirmed(account: String, target: String, body: String): Boolean =
        suspendCancellableCoroutine { result ->
            operations.trySend {
                val ok = access { storage.encode(key(account, target), body) } == true
                if (result.isActive) result.resume(ok)
            }
        }

    fun remove(account: String, target: String) {
        operations.trySend { access { storage.removeValueForKey(key(account, target)) } }
    }

    private inline fun <T> access(block: () -> T): T? =
        try {
            block()
        } catch (error: RuntimeException) {
            Log.w("CommentDraftStore", "Unable to access comment draft storage", error)
            null
        }

    private fun key(account: String, target: String): String {
        val normalized = account.lowercase(Locale.ROOT)
        return "${normalized.length}:$normalized:$target"
    }
}
