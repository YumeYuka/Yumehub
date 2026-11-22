package moe.yumeyuka.yumehub.data.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

data class AuthSession(
    val login: String,
    val scope: String,
    val generation: Long,
)

class AuthException(
    message: String,
    cause: Throwable? = null,
    val isCanceled: Boolean = false,
) : Exception(message, cause)

enum class SessionRecovery {
    Ignored,
    Refreshed,
    Expired,
}

class SessionFailures {
    private val unauthorized = Channel<String>(Channel.CONFLATED)
    val rejectedTokens = unauthorized.receiveAsFlow()

    fun reportUnauthorized(token: String) {
        unauthorized.trySend(token)
    }
}
