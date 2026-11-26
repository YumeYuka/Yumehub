package moe.yumeyuka.yumehub.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.data.twofactor.MobileAuthRequest
import moe.yumeyuka.yumehub.data.twofactor.MobileAuthStatus
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorException
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorNotifier
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorRegistrationUncertainException
import moe.yumeyuka.yumehub.data.twofactor.TwoFactorRepository
import moe.yumeyuka.yumehub.locale.GithubTxt
import retrofit2.HttpException

enum class TwoFactorPhase {
    Idle,
    Prompt,
    Submitting,
}

data class TwoFactorUiState(
    val request: MobileAuthRequest? = null,
    val digits: String = "",
    val phase: TwoFactorPhase = TwoFactorPhase.Idle,
    val error: String? = null,
    val receivingError: String? = null,
    val notificationWarning: String? = null,
    val submissionUncertain: Boolean = false,
    val registrationUncertain: Boolean = false,
) {
    val isVisible: Boolean
        get() = phase == TwoFactorPhase.Prompt && (request != null || submissionUncertain)

    val requiresDigits: Boolean
        get() = request?.requiresChallenge == true

    val canSubmit: Boolean
        get() =
            phase == TwoFactorPhase.Prompt &&
                !submissionUncertain &&
                (!requiresDigits || isValidDigits(digits))

    val canReject: Boolean
        get() = phase == TwoFactorPhase.Prompt && !submissionUncertain
}

class TwoFactorViewModel(
    private val twoFactorRepository: TwoFactorRepository,
    private val authRepository: AuthRepository,
    private val tokenStore: TokenStore,
    private val notifier: TwoFactorNotifier,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TwoFactorUiState())
    val uiState: StateFlow<TwoFactorUiState> = mutableState.asStateFlow()

    private var submission: Job? = null
    private var sessionGeneration: Long? = null
    private var currentLogin: String? = null
    private val foregroundState = MutableStateFlow(true)
    private var notifiedRequestId: Int? = null
    private var settledRequestId: Int? = null
    private var uncertainRequestId: Int? = null
    private var receivingErrorReported = false
    private var notificationWarningShown = false

    init {
        viewModelScope.launch {
            authRepository.session
                .map { it?.login to it?.generation }
                .distinctUntilChanged()
                .collectLatest { (login, generation) ->
                    currentLogin = login
                    receivingErrorReported = false
                    notificationWarningShown = false
                    sessionGeneration = generation
                    submission?.cancel()
                    settledRequestId = null
                    uncertainRequestId = null
                    mutableState.value = TwoFactorUiState()
                    notifier.cancel()
                    notifiedRequestId = null
                    if (!login.isNullOrBlank() && generation != null) pollLoop(login, generation)
                }
        }
        viewModelScope.launch {
            uiState
                .map { if (it.phase == TwoFactorPhase.Prompt) it.request?.id else null }
                .distinctUntilChanged()
                .collect { syncNotification() }
        }
    }

    fun setForeground(value: Boolean) {
        foregroundState.value = value
        refreshNotificationPermission()
    }

    fun refreshNotificationPermission() {
        val permitted = notifier.canNotify()
        if (permitted) notificationWarningShown = false
        mutableState.value =
            mutableState.value.copy(
                notificationWarning = if (permitted) null else "未开启系统通知，后台验证请求无法提醒",
            )
        syncNotification()
    }

    fun consumeNotice(message: String): Boolean {
        val state = mutableState.value
        if (state.isVisible) return false
        if (message == state.receivingError) {
            mutableState.value = state.copy(receivingError = null)
            return true
        }
        if (message != state.notificationWarning || notificationWarningShown) return false
        notificationWarningShown = true
        return true
    }

    private fun syncNotification() {
        val state = mutableState.value
        val requestId =
            state.request?.id.takeIf {
                !foregroundState.value && state.phase == TwoFactorPhase.Prompt
            }
        if (requestId == null) {
            notifier.cancel()
            notifiedRequestId = null
        } else if (notifiedRequestId != requestId) {
            if (notifier.showRequest()) {
                notifiedRequestId = requestId
            } else {
                mutableState.value = state.copy(notificationWarning = "未开启系统通知，后台验证请求无法提醒")
            }
        }
    }

    fun onDigitsChange(value: String) {
        if (mutableState.value.phase != TwoFactorPhase.Prompt) return
        val sanitized = value.filter { it in '0'..'9' }.take(MAX_DIGITS)
        mutableState.value = mutableState.value.copy(digits = sanitized, error = null)
    }

    fun approve() {
        val state = mutableState.value
        val request = state.request ?: return
        val login = currentLogin ?: return
        if (!state.canSubmit) return

        val digits = state.digits.toIntOrNull().takeIf { state.requiresDigits }
        submit(request, login) { generation ->
            twoFactorRepository.approve(login, generation, request, digits)
        }
    }

    fun reject() {
        val state = mutableState.value
        val request = state.request ?: return
        val login = currentLogin ?: return
        if (!state.canReject) return
        submit(request, login) { generation ->
            twoFactorRepository.reject(login, generation, request)
        }
    }

    fun acknowledgeSubmissionUncertain() {
        val state = mutableState.value
        if (!state.submissionUncertain) return
        val id = uncertainRequestId ?: return
        val login = currentLogin ?: return
        val generation = sessionGeneration ?: return
        val coord = twoFactorRepository.decisions(login)
        val marker = coord.pending.value.firstOrNull { it.kind == MutationKind.TWO_FACTOR_DECISION && it.target == id.toString() } ?: return
        viewModelScope.launch {
            coord.acknowledge(marker.operationId).join()
            if (!ownsSession(login, generation) || uncertainRequestId != id || coord.currentCommand(decisionKey(id)).phase == WritePhase.UNCERTAIN) return@launch
            uncertainRequestId = null
            settledRequestId = id
            mutableState.value = state.withoutRequest()
        }
    }

    fun acknowledgeRegistrationUncertain() {
        val login = currentLogin ?: return
        val generation = sessionGeneration ?: return
        viewModelScope.launch {
            try {
                twoFactorRepository.acknowledgeUncertainRegistration(login, generation)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { return@launch }
            if (ownsSession(login, generation)) {
                receivingErrorReported = false
                mutableState.value =
                    mutableState.value.copy(registrationUncertain = false, receivingError = null)
            }
        }
    }

    private fun decisionKey(id: Int) = MutationKey(MutationKind.TWO_FACTOR_DECISION, id.toString(), CommandAccepted.serializer())

    private fun submit(
        request: MobileAuthRequest,
        login: String,
        action: suspend (Long) -> Unit,
    ) {
        val generation = sessionGeneration ?: return
        val coord = twoFactorRepository.decisions(login)
        val key = decisionKey(request.id)
        if (uncertainRequestId == request.id || coord.currentCommand(key).result != null) return
        val receipt = coord.submitCommand(key, emptySet(), send = {
            action(generation)
            CommandAccepted
        }, readBack = { null })
        mutableState.value = mutableState.value.copy(phase = TwoFactorPhase.Submitting, error = null)
        submission = viewModelScope.launch {
            val result = receipt?.await() ?: coord.currentCommand(key)
            updateIfCurrent(request.id, login, generation) { current ->
                when {
                    result.phase == WritePhase.UNCERTAIN -> {
                        uncertainRequestId = request.id
                        current.copy(phase = TwoFactorPhase.Prompt, submissionUncertain = true, error = GithubTxt.current.Common.MutationUncertain)
                    }
                    receipt != null && result.phase == WritePhase.IDLE && result.result == CommandAccepted -> {
                        settledRequestId = request.id
                        current.withoutRequest()
                    }
                    else -> current.copy(phase = TwoFactorPhase.Prompt, error = result.message ?: GithubTxt.current.Common.MutationNotSubmitted)
                }
            }
        }
    }

    fun dismiss() {
        val current = mutableState.value
        if (current.phase != TwoFactorPhase.Submitting && !current.submissionUncertain) {
            mutableState.value = current.withoutRequest()
        }
    }

    private fun TwoFactorUiState.withoutRequest() =
        TwoFactorUiState(
            receivingError = receivingError,
            notificationWarning = notificationWarning,
            registrationUncertain = registrationUncertain,
        )

    /** Applies [transform] only if the shown request and account still match the completed work. */
    private fun updateIfCurrent(
        requestId: Int,
        login: String,
        generation: Long,
        transform: (TwoFactorUiState) -> TwoFactorUiState,
    ) {
        val current = mutableState.value
        if (ownsSession(login, generation) && current.request?.id == requestId) {
            mutableState.value = transform(current)
        }
    }

    private fun ownsSession(login: String, generation: Long): Boolean =
        currentLogin == login && tokenStore.sessionGeneration.value == generation

    private suspend fun pollLoop(login: String, generation: Long) {
        var nextErrorDelayMillis = ERROR_RETRY_INITIAL_MILLIS
        while (currentCoroutineContext().isActive) {
            val phase = mutableState.value.phase
            var requestFailed = false
            if (phase != TwoFactorPhase.Submitting) {
                try {
                    withTimeout(REQUEST_TIMEOUT_MILLIS) {
                        val regUncertain = twoFactorRepository.hasUncertainRegistration(login)
                        if (regUncertain) {
                            if (ownsSession(login, generation)) {
                                mutableState.value =
                                    mutableState.value.copy(registrationUncertain = true)
                            }
                        } else {
                            twoFactorRepository.ensureDeviceKey(login, generation, force = false)
                        }
                        val status = twoFactorRepository.fetchStatus(login, generation)
                        if (!status.hasValidDeviceAuthKey && !twoFactorRepository.hasUncertainRegistration(login)) {
                            twoFactorRepository.ensureDeviceKey(login, generation, force = true)
                        }
                        if (ownsSession(login, generation)) {
                            receivingErrorReported = false
                            mutableState.value =
                                mutableState.value.copy(
                                    receivingError = null,
                                    registrationUncertain = twoFactorRepository.hasUncertainRegistration(login),
                                )
                            reconcile(login, status)
                        }
                    }
                    nextErrorDelayMillis = ERROR_RETRY_INITIAL_MILLIS
                } catch (_: TimeoutCancellationException) {
                    reportReceivingError(login, generation, "验证请求检查超时，正在重试")
                    requestFailed = true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: TwoFactorRegistrationUncertainException) {
                    if (ownsSession(login, generation)) {
                        mutableState.value =
                            mutableState.value.copy(
                                registrationUncertain = true,
                                receivingError = GithubTxt.current.Common.MutationUncertain,
                            )
                    }
                    requestFailed = true
                } catch (error: Exception) {
                    val cause = error.cause ?: error
                    val message =
                        when (cause) {
                            is HttpException ->
                                if (error is TwoFactorException) {
                                    "验证请求检查失败：${error.message ?: "HTTP ${cause.code()}"}"
                                } else {
                                    "验证请求检查失败（HTTP ${cause.code()}），请检查登录状态"
                                }
                            is IOException -> "无法连接 GitHub，验证请求暂时无法接收"
                            else -> "验证请求检查失败：${error.message ?: "未知错误"}"
                        }
                    reportReceivingError(login, generation, message)
                    requestFailed = true
                }
            }
            val delayMillis =
                if (requestFailed) {
                    nextErrorDelayMillis.also {
                        nextErrorDelayMillis =
                            (nextErrorDelayMillis * 2).coerceAtMost(ERROR_RETRY_MAX_MILLIS)
                    }
                } else {
                    if (foregroundState.value) FOREGROUND_POLL_INTERVAL_MILLIS
                    else BACKGROUND_POLL_INTERVAL_MILLIS
                }
            waitForNextPoll(delayMillis)
        }
    }

    private suspend fun waitForNextPoll(delayMillis: Long) {
        val foregroundAtWait = foregroundState.value
        withTimeoutOrNull(delayMillis) { foregroundState.first { it != foregroundAtWait } }
    }

    private fun reportReceivingError(login: String, generation: Long, message: String) {
        if (ownsSession(login, generation) && !receivingErrorReported) {
            receivingErrorReported = true
            mutableState.value = mutableState.value.copy(receivingError = message)
        }
    }

    /** Reconciles the shown prompt with the latest server state (new, cancelled, or expired). */
    private fun reconcile(login: String, status: MobileAuthStatus) {
        if (currentLogin != login) return
        val current = mutableState.value
        if (current.phase == TwoFactorPhase.Submitting) {
            return
        }
        val active = status.activeRequest?.takeUnless { it.id == settledRequestId }
        val decisions = twoFactorRepository.decisions(login)
        val previous = uncertainRequestId
        if (current.submissionUncertain && previous != null && decisions.currentCommand(decisionKey(previous)).phase == WritePhase.IDLE) {
            settledRequestId = previous
            uncertainRequestId = null
            mutableState.value = current.withoutRequest()
            return
        }
        val pendingId = active?.id ?: decisions.pending.value.firstOrNull { it.kind == MutationKind.TWO_FACTOR_DECISION }?.target?.toIntOrNull()
        if (pendingId != null) {
            val decision = decisions.currentCommand(decisionKey(pendingId))
            if (decision.phase == WritePhase.UNCERTAIN) {
                uncertainRequestId = pendingId
                mutableState.value = current.copy(request = active, digits = "", phase = TwoFactorPhase.Prompt, submissionUncertain = true)
                return
            }
            if (decision.phase == WritePhase.PENDING || decision.result != null) return
        }
        when {
            active == null -> {
                // 不因 fetchStatus 消失而推断结果未知的审批已成功；保留待确认提示直到用户确认或有新请求到来。
                if (current.phase == TwoFactorPhase.Prompt && !current.submissionUncertain) {
                    mutableState.value = current.withoutRequest()
                }
            }
            current.request?.id != active.id -> {
                uncertainRequestId = null
                mutableState.value =
                    current.withoutRequest().copy(request = active, phase = TwoFactorPhase.Prompt)
            }
        }
    }

    override fun onCleared() {
        notifier.cancel()
        super.onCleared()
    }

    private companion object {
        private const val FOREGROUND_POLL_INTERVAL_MILLIS = 5_000L
        private const val BACKGROUND_POLL_INTERVAL_MILLIS = 15_000L
        private const val ERROR_RETRY_INITIAL_MILLIS = 15_000L
        private const val ERROR_RETRY_MAX_MILLIS = 60_000L
        private const val REQUEST_TIMEOUT_MILLIS = 20_000L
        private const val MAX_DIGITS = 2
    }
}

internal fun isValidDigits(value: String): Boolean =
    value.length == 2 && value.all { it in '0'..'9' }
