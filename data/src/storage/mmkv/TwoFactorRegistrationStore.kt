package moe.yumeyuka.yumehub.data.storage.mmkv

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class TwoFactorRegistrationRecord(
    val candidateAlias: String,
    val phase: MarkerPhase,
    val startedAt: Long,
)

/**
 * 2FA 设备密钥候选注册标记：独立 MMKV `two-factor-registration-v1`，只保存候选 alias 与阶段。
 *
 * `PREPARED` 证明注册请求尚未发送，可安全删除候选；`MAY_HAVE_SENT` 必须保留候选与旧激活密钥，等待用户到 GitHub 核对。
 */
internal class TwoFactorRegistrationStore(provider: MMKVProvider, private val json: Json) {
    private val storage by lazy { provider.getStorage("two-factor-registration-v1") }
    private val mutex = Mutex()

    suspend fun load(account: String): TwoFactorRegistrationRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage.decodeString(accountKey(account))?.let { raw ->
                    json.decodeFromString(TwoFactorRegistrationRecord.serializer(), raw)
                }
            }
        }

    suspend fun put(account: String, record: TwoFactorRegistrationRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(
                    storage.encode(
                        accountKey(account),
                        json.encodeToString(TwoFactorRegistrationRecord.serializer(), record),
                    ),
                ) {
                    "Unable to persist 2FA registration state"
                }
                if (record.phase == MarkerPhase.MAY_HAVE_SENT) storage.sync()
            }
        }

    suspend fun clear(account: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage.removeValueForKey(accountKey(account))
                storage.sync()
            }
        }

    private fun accountKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(account.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return "2fa-reg:${digest.digest().toHexString()}"
    }
}
