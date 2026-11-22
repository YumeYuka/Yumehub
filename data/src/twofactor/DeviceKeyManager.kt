package moe.yumeyuka.yumehub.data.twofactor

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/**
 * AndroidKeyStore EC P-256 signing key used for GitHub mobile two-factor device approval.
 *
 * Mirrors the official AUTH client (`defpackage.gik` / `defpackage.u6u` / `defpackage.cw9`):
 * - Key: EC `secp256r1`, purpose SIGN, digest SHA-256, non-exportable, hardware-backed when
 *   available.
 * - Signature algorithm: `SHA256withECDSA` (DER output), Base64 `NO_WRAP`.
 * - `publicKey` field uploaded to GitHub is Base64(PEM-wrapped X.509 SubjectPublicKeyInfo).
 */
class DeviceKeyManager(
    private val context: Context,
    private val tokenStore: TokenStore,
) {
    data class DeviceKeyRegistration(
        val alias: String,
        val publicKey: String,
        val verificationMessage: String,
        val verificationSignature: String,
        val deviceName: String,
        val deviceModel: String,
        val isHardwareBacked: Boolean,
    )

    /** Keeps the current signing key intact until GitHub acknowledges the candidate. */
    fun buildRegistration(login: String): DeviceKeyRegistration {
        val alias = "2fa_${login}_sign_${UUID.randomUUID()}"
        return try {
            ensureKey(alias)
            val certificate =
                keyStore.getCertificate(alias)
                    ?: throw TwoFactorException("Device key certificate is missing")

            val verificationMessage = UUID.randomUUID().toString()
            val messageBytes = verificationMessage.toByteArray(Charsets.UTF_8)
            val signatureBytes = sign(alias, messageBytes)
            val verified =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(certificate.publicKey)
                    update(messageBytes)
                    verify(signatureBytes)
                }
            if (!verified) throw TwoFactorException("设备密钥本地验签失败，未向 GitHub 提交")
            val verificationSignature = encode(signatureBytes)

            val deviceName =
                Settings.Global.getString(context.contentResolver, "device_name")?.takeIf {
                    it.isNotBlank()
                } ?: android.os.Build.MODEL
            val deviceModel = android.os.Build.MODEL

            DeviceKeyRegistration(
                alias = alias,
                publicKey = encodePublicKey(certificate),
                verificationMessage = verificationMessage,
                verificationSignature = verificationSignature,
                deviceName = deviceName,
                deviceModel = deviceModel,
                isHardwareBacked = isHardwareBacked(alias),
            )
        } catch (error: Exception) {
            try {
                keyStore.deleteEntry(alias)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    fun activateRegistration(login: String, registration: DeviceKeyRegistration) {
        tokenStore.setDeviceKeyAlias(login, registration.alias)
    }

    fun discardRegistration(registration: DeviceKeyRegistration) {
        keyStore.deleteEntry(registration.alias)
    }

    /**
     * Produces the approval signature over `1|<payloadBytes>[|<digits>]`.
     *
     * @param payload Base64 payload from the active auth request.
     * @param digits The 1-5 digit code shown on the other device, or null for no-challenge
     *   requests.
     */
    fun signApproval(login: String, payload: String, digits: Int?): String {
        val alias = signAlias(login)
        val payloadBytes = Base64.decode(payload, Base64.DEFAULT)
        var message = VERSION_PREFIX.toByteArray(Charsets.UTF_8) + payloadBytes
        if (digits != null) {
            message += ("|$digits").toByteArray(Charsets.UTF_8)
        }
        return encode(sign(alias, message))
    }

    fun deleteKey(login: String) {
        runCatching { keyStore.deleteEntry(signAlias(login)) }
    }

    private fun ensureKey(alias: String) {
        if (keyStore.containsAlias(alias)) return
        val generator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun sign(alias: String, data: ByteArray): ByteArray {
        val privateKey =
            keyStore.getKey(alias, null) as? PrivateKey
                ?: throw TwoFactorException("Device signing key is unavailable")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    private fun encodePublicKey(certificate: Certificate): String {
        val derBase64 = encode(certificate.publicKey.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$derBase64\n-----END PUBLIC KEY-----\n"
        return encode(pem.toByteArray(Charsets.UTF_8))
    }

    private fun isHardwareBacked(alias: String): Boolean = runCatching {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return false
        val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
        val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
        @Suppress("DEPRECATION") keyInfo.isInsideSecureHardware
    }
        .getOrDefault(false)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun signAlias(login: String): String =
        tokenStore.deviceKeyAlias(login) ?: "2fa_${login}_sign"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    private companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val VERSION_PREFIX = "1|"
    }
}
