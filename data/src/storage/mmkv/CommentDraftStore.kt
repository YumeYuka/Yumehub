package moe.yumeyuka.yumehub.data.storage.mmkv

import java.util.Locale

/** 评论草稿与凭据分库存储，账号与目标共同决定隔离键。 */
class CommentDraftStore(provider: MMKVProvider) {
    private val storage = provider.getMMKV("comment-drafts")

    fun load(account: String, target: String): String = loadOrNull(account, target).orEmpty()

    fun loadOrNull(account: String, target: String): String? =
        storage.decodeString(key(account, target))

    fun save(account: String, target: String, body: String) {
        val key = key(account, target)
        storage.encode(key, body)
    }

    fun remove(account: String, target: String) {
        storage.removeValueForKey(key(account, target))
    }

    private fun key(account: String, target: String): String {
        val normalized = account.lowercase(Locale.ROOT)
        return "${normalized.length}:$normalized:$target"
    }
}
