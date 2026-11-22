package moe.yumeyuka.yumehub.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.auth.AuthException
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.auth.SessionFailures
import moe.yumeyuka.yumehub.data.auth.SessionRecovery
import moe.yumeyuka.yumehub.locale.GithubTxt

sealed interface AuthUiState {
    data object Restoring : AuthUiState

    data object SignedOut : AuthUiState

    data class Authenticated(
        val login: String,
        val generation: Long,
        val refreshCount: Int,
    ) : AuthUiState
}

sealed interface AuthEvent {
    data class AuthorizationFailed(val message: String) : AuthEvent

    data object SignedOut : AuthEvent
}

class AuthViewModel(
    private val authRepository: AuthRepository,
    sessionFailures: SessionFailures,
) : ViewModel() {
    private val refreshCount = MutableStateFlow(0)

    private val mutableEvents =
        MutableSharedFlow<AuthEvent>(
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<AuthEvent> = mutableEvents.asSharedFlow()

    val uiState: StateFlow<AuthUiState> =
        combine(authRepository.session, refreshCount) { session, count ->
                session?.let { AuthUiState.Authenticated(it.login, it.generation, count) }
                    ?: AuthUiState.SignedOut
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = AuthUiState.Restoring,
            )

    init {
        viewModelScope.launch {
            sessionFailures.rejectedTokens.collect { token ->
                try {
                    when (authRepository.recoverSession(token)) {
                        SessionRecovery.Ignored -> Unit
                        SessionRecovery.Refreshed -> refreshCount.value++
                        SessionRecovery.Expired -> {
                            mutableEvents.tryEmit(AuthEvent.AuthorizationFailed("登录已失效，请重新登录"))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: AuthException) {
                    // 暂时无法刷新时保留会话，用户可以从原页面重试。
                }
            }
        }
    }

    suspend fun switchAccount(login: String): Boolean =
        try {
            authRepository.switchAccount(login)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableEvents.tryEmit(
                AuthEvent.AuthorizationFailed(GithubTxt.current.Profile.AccountFailed),
            )
            false
        }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            mutableEvents.tryEmit(AuthEvent.SignedOut)
        }
    }

    private companion object {
        private const val EVENT_BUFFER_CAPACITY = 16
        private const val STATE_SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
