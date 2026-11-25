package moe.yumeyuka.yumehub.data.twofactor

import kotlinx.serialization.Serializable

/**
 * One pending mobile-auth device approval request, as returned by the GitHub `MobileAuthRequests`
 * GraphQL query (`viewer.mobileAuthStatus.activeAuthRequest`).
 */
data class MobileAuthRequest(
    val id: Int,
    val payload: String,
    val requiresChallenge: Boolean,
    val type: MobileAuthRequestType,
)

enum class MobileAuthRequestType(val wireName: String) {
    TwoFactorLogin("TWO_FACTOR_LOGIN"),
    DeviceVerification("DEVICE_VERIFICATION"),
    TwoFactorPasswordReset("TWO_FACTOR_PASSWORD_RESET"),
    TwoFactorSudoChallenge("TWO_FACTOR_SUDO_CHALLENGE"),
    Unknown("UNKNOWN");

    companion object {
        fun fromWire(value: String?): MobileAuthRequestType =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: Unknown
    }
}

/** Raw shape of the `MobileAuthRequests` GraphQL response `data` node. */
@Serializable data class MobileAuthViewerData(val viewer: MobileAuthViewer? = null)

@Serializable
data class MobileAuthViewer(
    val login: String? = null,
    val mobileAuthStatus: MobileAuthStatusDto? = null,
)

@Serializable
data class MobileAuthStatusDto(
    val hasValidDeviceAuthKey: Boolean = false,
    val hasExpiredAuthRequest: Boolean = false,
    val activeAuthRequest: ActiveAuthRequestDto? = null,
)

@Serializable
data class ActiveAuthRequestDto(
    val id: Int,
    val payload: String,
    val challengeRequired: Boolean = false,
    val type: String? = null,
)

/** Aggregated mobile-auth status for the signed-in viewer. */
data class MobileAuthStatus(
    val hasValidDeviceAuthKey: Boolean,
    val hasExpiredAuthRequest: Boolean,
    val activeRequest: MobileAuthRequest?,
)

open class TwoFactorException(
    message: String,
    cause: Throwable? = null,
    val definiteRejection: Boolean = false,
) : Exception(message, cause)

class TwoFactorUncertainSubmissionException(
    message: String,
    cause: Throwable? = null,
) : TwoFactorException(message, cause, definiteRejection = false)

class TwoFactorRegistrationUncertainException(
    val candidateAlias: String,
    message: String = "设备密钥注册结果未确认，请先在 GitHub 核对后重试",
    cause: Throwable? = null,
) : TwoFactorException(message, cause, definiteRejection = false)
