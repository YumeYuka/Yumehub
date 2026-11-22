package moe.yumeyuka.yumehub.feature.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.auth.AuthException
import moe.yumeyuka.yumehub.data.auth.AuthRepository

internal enum class LoginState {
    Ready,
    Authorizing,
    Completing,
    Failed,
    Completed,
}

internal class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginState.Ready)
    val state = mutableState.asStateFlow()

    fun start(launch: (Intent) -> Unit) {
        if (!mutableState.compareAndSet(LoginState.Ready, LoginState.Authorizing)) return
        try {
            launch(auth.createAuthorizationIntent())
        } catch (error: Exception) {
            mutableState.value = LoginState.Failed
        }
    }

    fun handleResult(result: Intent?) {
        if (result == null) {
            mutableState.value = LoginState.Ready
            return
        }
        mutableState.value = LoginState.Completing
        viewModelScope.launch {
            try {
                auth.completeLogin(result)
                mutableState.value = LoginState.Completed
            } catch (error: CancellationException) {
                throw error
            } catch (error: AuthException) {
                mutableState.value = if (error.isCanceled) LoginState.Ready else LoginState.Failed
            } catch (_: Exception) {
                mutableState.value = LoginState.Failed
            }
        }
    }

    fun acknowledgeFailure() {
        mutableState.compareAndSet(LoginState.Failed, LoginState.Ready)
    }
}
