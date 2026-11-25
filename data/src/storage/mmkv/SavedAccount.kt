package moe.yumeyuka.yumehub.data.storage.mmkv

import kotlinx.serialization.Serializable

data class AccountAvatar(val login: String, val avatarUrl: String?)

const val MaxSavedAccounts = 3

@Serializable
internal data class SavedAccount(
    val login: String = "",
    val avatarUrl: String? = null,
    val accessToken: String = "",
    val refreshToken: String = "",
    val scope: String = "",
    val deviceKeyRegistered: Boolean = false,
    /** 账号的持久缓存会话 ID；随授权、切号和退出后重新登录更换。 */
    val cacheSessionId: String = "",
) {
    override fun toString(): String = "SavedAccount(login=$login)"
}

/** 首项是当前账号，其余保持最近使用顺序。重复授权替换凭据，不占新位置。 */
internal fun List<SavedAccount>.activate(account: SavedAccount): List<SavedAccount> {
    val others = filterNot { it.login.equals(account.login, ignoreCase = true) }
    require(others.size < MaxSavedAccounts)
    return listOf(account) + others
}
