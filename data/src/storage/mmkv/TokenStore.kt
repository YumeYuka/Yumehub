package moe.yumeyuka.yumehub.data.storage.mmkv

import com.tencent.mmkv.MMKV
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.auth.AuthSession
import moe.yumeyuka.yumehub.data.cache.CacheSession

class TokenStore internal constructor(private val externalStorage: KeyValueStorage) :
    MMKVPreference(mmkv = externalStorage) {
    constructor(externalMmkv: MMKV) : this(RealMMKVStorage(externalMmkv))
    internal val responseCacheSession = CacheSession()
    private val mutableSessionGeneration = MutableStateFlow(0L)
    val sessionGeneration = mutableSessionGeneration.asStateFlow()
    private val records = MutableStateFlow(restoreAccounts())
    private val current
        get() = records.value.firstOrNull() ?: SavedAccount()

    private val mutableAccounts = MutableStateFlow(avatars(records.value))
    val accounts = mutableAccounts.asStateFlow()
    private val mutableAuthSession = MutableStateFlow(currentAuthSession())
    internal val authSession = mutableAuthSession.asStateFlow()

    private fun currentAuthSession(): AuthSession? =
        current
            .takeIf { it.login.isNotBlank() && it.accessToken.isNotBlank() }
            ?.let { AuthSession(it.login, it.scope, responseCacheSession.generation) }

    private val observers = mutableListOf<() -> Unit>()
    val accessToken = field(SavedAccount::accessToken) { copy(accessToken = it) }
    val refreshToken = field(SavedAccount::refreshToken) { copy(refreshToken = it) }
    val accountLogin = field(SavedAccount::login) { copy(login = it) }
    val scope = field(SavedAccount::scope) { copy(scope = it) }
    val deviceKeyRegistered =
        field(SavedAccount::deviceKeyRegistered) { copy(deviceKeyRegistered = it) }

    init {
        prepareResponseCacheSession()
    }

    private fun restoreAccounts(): List<SavedAccount> {
        val key = "savedAccounts.v1"
        val oldKeys =
            arrayOf("accountLogin", "accessToken", "refreshToken", "scope", "deviceKeyRegistered")
        val stored = externalStorage.decodeString(key)
        val list =
            if (stored != null) Json.decodeFromString<List<SavedAccount>>(stored)
            else {
                val login = externalStorage.decodeString("accountLogin").orEmpty()
                val token = externalStorage.decodeString("accessToken").orEmpty()
                if (login.isBlank() || token.isBlank()) emptyList()
                else
                    listOf(
                        SavedAccount(
                            login = login,
                            accessToken = token,
                            refreshToken = externalStorage.decodeString("refreshToken").orEmpty(),
                            scope = externalStorage.decodeString("scope").orEmpty(),
                            deviceKeyRegistered = externalStorage.decodeBool("deviceKeyRegistered"),
                        ),
                    )
            }
        // 旧记录缺会话 ID 时生成并原子保存；保存失败保留认证，但本会话禁用持久缓存。
        val migrated =
            list.map { account ->
                if (account.cacheSessionId.isBlank())
                    account.copy(cacheSessionId = newSessionId())
                else account
            }
        if (stored == null || migrated != list) {
            try {
                persist(migrated)
            } catch (_: Exception) {
                responseCacheSession.persistenceEnabled = false
            }
        }
        if (migrated.isNotEmpty()) externalStorage.removeValueForKey("responseCacheSessionId")
        externalStorage.removeValuesForKeys(oldKeys)
        return migrated
    }

    private fun persist(accounts: List<SavedAccount>) {
        check(externalStorage.encode("savedAccounts.v1", Json.encodeToString(accounts)))
    }

    private fun avatars(list: List<SavedAccount>) = list.map {
        AccountAvatar(it.login, it.avatarUrl)
    }

    private fun <T> field(
        read: (SavedAccount) -> T,
        write: SavedAccount.(T) -> SavedAccount,
    ): Preference<T> {
        val state = MutableStateFlow(read(current))
        observers += { state.value = read(current) }
        return Preference(
            state.asStateFlow(),
            update = { value ->
                synchronized(responseCacheSession.lock) {
                    replace(listOf(current.write(value)) + records.value.drop(1))
                }
            },
            get = { read(current) },
        )
    }

    // 凭据、当前身份与会话 ID 只写一个 MMKV 值；发布前完成磁盘提交。
    private fun replace(next: List<SavedAccount>, renewSession: Boolean = false) {
        val previousLogin = current.login
        persist(next)
        records.value = next
        observers.forEach { it() }
        val active = next.firstOrNull()
        if (next.isEmpty() && previousLogin.isNotEmpty()) {
            responseCacheSession.clear(newSessionId())
        } else if (active != null) {
            responseCacheSession.bind(active.login, { active.cacheSessionId }, renewSession)
        }
        mutableSessionGeneration.value = responseCacheSession.generation
        mutableAccounts.value = avatars(next)
        mutableAuthSession.value = currentAuthSession()
    }

    internal fun saveAccount(account: SavedAccount) =
        synchronized(responseCacheSession.lock) {
            // 重新授权必须换会话 ID，旧缓存不能跨凭证复用。
            val rotated = account.copy(cacheSessionId = newSessionId())
            replace(records.value.activate(rotated), renewSession = true)
            checkNotNull(authSession.value)
        }

    internal fun selectAccount(login: String) =
        synchronized(responseCacheSession.lock) {
            val account = records.value.first { it.login.equals(login, ignoreCase = true) }
            if (!account.login.equals(current.login, ignoreCase = true)) {
                // 切号同时更换目标账号的会话 ID，旧账号缓存不能恢复。
                replace(
                    records.value.activate(account.copy(cacheSessionId = newSessionId())),
                    renewSession = true,
                )
            }
        }

    internal fun removeActiveAccount() =
        synchronized(responseCacheSession.lock) { replace(records.value.drop(1)) }

    internal fun rememberAvatar(login: String, generation: Long, url: String) =
        synchronized(responseCacheSession.lock) {
            requireSession(login, generation)
            if (
                current.login.equals(login, ignoreCase = true) &&
                    url.isNotBlank() &&
                    current.avatarUrl != url
            ) {
                replace(listOf(current.copy(avatarUrl = url)) + records.value.drop(1))
            }
        }

    internal fun updateTokens(access: String, refresh: String?, newScope: String?) =
        synchronized(responseCacheSession.lock) {
            replace(
                listOf(
                    current.copy(
                        accessToken = access,
                        refreshToken = refresh?.takeIf(String::isNotBlank) ?: current.refreshToken,
                        scope = newScope ?: current.scope,
                    ),
                ) + records.value.drop(1),
            )
        }

    fun requireSession(account: String, generation: Long) =
        synchronized(responseCacheSession.lock) {
            check(account.isNotBlank())
            if (!responseCacheSession.accepts(account.lowercase(Locale.ROOT), generation)) {
                throw CancellationException("Account session changed")
            }
        }

    internal fun authorization(account: String, generation: Long): Map<String, String> =
        synchronized(responseCacheSession.lock) {
            requireSession(account, generation)
            if (!current.login.equals(account, true) || current.accessToken.isBlank()) {
                throw CancellationException("Account session changed")
            }
            mapOf("Authorization" to "Bearer ${current.accessToken}")
        }

    fun deviceKeyAlias(login: String): String? =
        externalStorage.decodeString("twoFactorKeyAlias.$login")

    fun setDeviceKeyAlias(login: String, alias: String) {
        check(externalStorage.encode("twoFactorKeyAlias.$login", alias)) {
            "Unable to persist the registered device key alias"
        }
    }

    internal fun prepareResponseCacheSession() =
        synchronized(responseCacheSession.lock) {
            if (responseCacheSession.id == null) {
                val active = records.value.firstOrNull()
                val id =
                    active?.cacheSessionId?.takeIf(String::isNotBlank) ?: newSessionId()
                responseCacheSession.restore(active?.login.orEmpty(), id)
            }
        }

    private fun newSessionId(): String = UUID.randomUUID().toString()

    fun clear() =
        synchronized(responseCacheSession.lock) { replace(emptyList(), renewSession = true) }

    companion object {
        fun forTesting(
            storage: KeyValueStorage,
            vararg accounts: Pair<String, String>,
        ): TokenStore {
            if (accounts.isNotEmpty()) {
                val saved =
                    accounts.map { (login, token) ->
                        SavedAccount(
                            login = login,
                            accessToken = token,
                            cacheSessionId = "session-${login.lowercase(Locale.ROOT)}",
                        )
                    }
                storage.encode(
                    "savedAccounts.v1",
                    Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(SavedAccount.serializer()),
                        saved,
                    ),
                )
            }
            return TokenStore(storage)
        }
    }
}
