package moe.yumeyuka.yumehub.data.twofactor

/** Sends the Android notification that asks the user to approve a 2FA login. */
interface TwoFactorNotifier {
    fun canNotify(): Boolean

    fun showRequest(): Boolean

    fun cancel()
}
