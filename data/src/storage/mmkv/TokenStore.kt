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

class TokenStore(private val externalMmkv: MMKV) : MMKVPreference(externalMmkv = externalMmkv) {
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

    private fun restoreAccounts(): List<SavedAccount> {
        val key = "savedAccounts.v1"
        val oldKeys =
            arrayOf("accountLogin", "accessToken", "refreshToken", "scope", "deviceKeyRegistered")
        val stored = externalMmkv.decodeString(key)
        val list =
            if (stored != null) Json.decodeFromString<List<SavedAccount>>(stored)
            else {
                val login = externalMmkv.decodeString("accountLogin").orEmpty()
                val token = externalMmkv.decodeString("accessToken").orEmpty()
                if (login.isBlank() || token.isBlank()) emptyList()
                else
                    listOf(
                        SavedAccount(
                            login = login,
                            accessToken = token,
                            refreshToken = externalMmkv.decodeString("refreshToken").orEmpty(),
                            scope = externalMmkv.decodeString("scope").orEmpty(),
                            deviceKeyRegistered = externalMmkv.decodeBool("deviceKeyRegistered"),
                        ),
                    )
            }
        if (stored == null) persist(list)
        externalMmkv.removeValuesForKeys(oldKeys)
        return list
    }

    private fun persist(accounts: List<SavedAccount>) {
        check(externalMmkv.encode("savedAccounts.v1", Json.encodeToString(accounts)))
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

    // 凭据与当前身份只写一个 MMKV 值；缓存失效和旧 Preference 观察者共用同一把锁。
    private fun replace(next: List<SavedAccount>, renewSession: Boolean = false) {
        val previousLogin = current.login
        prepareResponseCacheSession()
        persist(next)
        records.value = next
        observers.forEach { it() }
        if (renewSession || (next.isEmpty() && previousLogin.isNotEmpty())) {
            responseCacheSession.clear(newResponseCacheSession())
        }
        responseCacheSession.bind(current.login, ::newResponseCacheSession)
        mutableSessionGeneration.value = responseCacheSession.generation
        mutableAccounts.value = avatars(next)
        mutableAuthSession.value = currentAuthSession()
    }

    internal fun saveAccount(account: SavedAccount) =
        synchronized(responseCacheSession.lock) {
            replace(records.value.activate(account), renewSession = true)
            checkNotNull(authSession.value)
        }

    internal fun selectAccount(login: String) =
        synchronized(responseCacheSession.lock) {
            val account = records.value.first { it.login.equals(login, ignoreCase = true) }
            if (account != current) replace(records.value.activate(account))
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
        externalMmkv.decodeString("twoFactorKeyAlias.$login")

    fun setDeviceKeyAlias(login: String, alias: String) {
        check(externalMmkv.encode("twoFactorKeyAlias.$login", alias)) {
            "Unable to persist the registered device key alias"
        }
    }

    internal fun prepareResponseCacheSession() =
        synchronized(responseCacheSession.lock) {
            if (responseCacheSession.id == null) {
                val storedId =
                    try {
                        externalMmkv.decodeString("responseCacheSessionId")
                    } catch (_: Exception) {
                        null
                    }
                val id = storedId?.takeIf { it.isNotBlank() } ?: newResponseCacheSession()
                responseCacheSession.restore(accountLogin.state.value, id)
            }
        }

    private fun newResponseCacheSession(): String =
        UUID.randomUUID().toString().also { id ->
            try {
                // 缓存元数据只做尽力持久化；返回 false 或抛异常均不阻止认证和内存会话换代。
                externalMmkv.encode("responseCacheSessionId", id)
            } catch (_: Exception) {
                // 运行时 UUID 仍然有效，不允许回退成旧磁盘 ID。
            }
        }

    fun clear() =
        synchronized(responseCacheSession.lock) { replace(emptyList(), renewSession = true) }
}
