package moe.yumeyuka.yumehub.data.storage.mmkv

import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.yumeyuka.yumehub.data.cache.ConfirmedWrites

/** 评论草稿与凭据分库存储，账号与目标共同决定隔离键。 */
class CommentDraftStore(provider: MMKVProvider) {
    private val operations = Channel<() -> Unit>(Channel.UNLIMITED)
    private class Write(var encode: () -> String)
    private val queued = mutableMapOf<String, Write>()

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
            barrier(key(account, target)) {
                if (result.isActive) {
                    result.resume(access { storage.decodeString(key(account, target)) })
                }
            }
        }

    fun save(account: String, target: String, body: String) = saveEncoded(account, target) { body }

    fun saveEncoded(account: String, target: String, encode: () -> String) {
        val id = key(account, target)
        synchronized(queued) {
            val waiting = queued[id]
            if (waiting != null) { waiting.encode = encode; return }
            val write = Write(encode)
            queued[id] = write
            operations.trySend {
                val latest = synchronized(queued) { queued.remove(id, write); write.encode }
                access { storage.encode(id, latest()) }
            }
        }
    }

    private fun barrier(id: String, action: () -> Unit) {
        synchronized(queued) {
            queued.remove(id)
            operations.trySend(action)
        }
    }

    /** 接受提交时等待同一草稿写入 actor 真正把完整草稿 encode 到磁盘后再返回。 */
    suspend fun saveConfirmed(account: String, target: String, body: String): Boolean =
        suspendCancellableCoroutine { result ->
            barrier(key(account, target)) {
                val ok = access { storage.encode(key(account, target), body) } == true
                if (result.isActive) result.resume(ok)
            }
        }

    /** A late response to a closed editor must not delete a subsequently edited draft. */
    fun removeIfUnchanged(account: String, target: String, submitted: String) {
        val id = key(account, target)
        afterConfirmation { barrier(id) { access { if (storage.decodeString(id) == submitted) storage.removeValueForKey(id) } } }
    }

    fun remove(account: String, target: String) {
        val id = key(account, target)
        afterConfirmation { barrier(id) { access { storage.removeValueForKey(id) } } }
    }

    private fun afterConfirmation(cleanup: () -> Unit) {
        val write = ConfirmedWrites.current.get()
        if (write == null) cleanup() else write.onCommit(cleanup)
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
