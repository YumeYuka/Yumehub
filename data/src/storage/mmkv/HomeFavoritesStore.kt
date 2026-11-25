package moe.yumeyuka.yumehub.data.storage.mmkv

import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto

/** 首页收藏的本地快照；按账号隔离，读取失败时只当作没有缓存。 */
class HomeFavoritesStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getMMKV("home-favorites") }
    private val serializer = ListSerializer(RepositoryDto.serializer())

    fun load(account: String): List<RepositoryDto>? {
        val raw = storage.decodeString(key(account)) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    fun save(account: String, repositories: List<RepositoryDto>) {
        storage.encode(key(account), json.encodeToString(serializer, repositories))
    }

    fun clear(account: String) {
        storage.removeValueForKey(key(account))
    }

    private fun key(account: String): String {
        val normalized = account.lowercase(Locale.ROOT)
        return "v1:${normalized.length}:$normalized"
    }
}
