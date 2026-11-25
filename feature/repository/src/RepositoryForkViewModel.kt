package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.repository.RepositoryForkRepository
import moe.yumeyuka.yumehub.locale.GithubTxt
import retrofit2.HttpException

internal const val ForkNameMaxLength = 100

internal enum class ForkNameStatus {
    Checking,
    Available,
    Taken,
    Invalid,
    Failed,
}

internal data class RepositoryForkState(
    val visible: Boolean = true,
    val parent: RepositoryDto,
    val account: String,
    val name: String,
    val defaultBranchOnly: Boolean = true,
    val nameStatus: ForkNameStatus = ForkNameStatus.Checking,
    val submitting: Boolean = false,
    val error: String? = null,
    val created: RepositoryDto? = null,
) {
    val canCreate: Boolean
        get() = visible && !submitting && nameStatus == ForkNameStatus.Available
}

internal class RepositoryForkViewModel(private val source: RepositoryForkRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<RepositoryForkState?>(null)
    val uiState = mutableState.asStateFlow()
    private var session: RepositoryForkRepository.Session? = null
    private var nameJob: Job? = null
    private var revision = 0L

    fun open(parent: RepositoryDto) {
        val previous = mutableState.value
        if (previous?.submitting == true) {
            mutableState.value = previous.copy(visible = true)
            return
        }
        val currentSession = source.session() ?: return
        session = currentSession
        mutableState.value =
            RepositoryForkState(
                parent = parent,
                account = currentSession.account,
                name = parent.name.orEmpty(),
            )
        checkName()
    }

    fun dismiss() {
        val state = mutableState.value ?: return
        nameJob?.cancel()
        revision++
        mutableState.value = state.copy(visible = false)
    }

    fun name(value: String) {
        val state = mutableState.value ?: return
        if (state.submitting || value.length > ForkNameMaxLength) return
        mutableState.value = state.copy(name = value, error = null)
        checkName()
    }

    fun defaultBranchOnly(value: Boolean) {
        val state = mutableState.value ?: return
        if (!state.submitting)
            mutableState.value = state.copy(defaultBranchOnly = value, error = null)
    }

    fun checkName() {
        nameJob?.cancel()
        val currentRevision = ++revision
        val state = mutableState.value ?: return
        val currentSession = session ?: return
        if (!source.isActive(currentSession) || !state.visible || state.submitting) return
        // REST 仓库名称只允许 ASCII 字母、数字、连字符、下划线和点。
        if (
            state.name.isBlank() ||
                !state.name.all {
                    it in 'a'..'z' ||
                        it in 'A'..'Z' ||
                        it in '0'..'9' ||
                        it == '-' ||
                        it == '_' ||
                        it == '.'
                } ||
                state.name == "." ||
                state.name == ".."
        ) {
            mutableState.value = state.copy(nameStatus = ForkNameStatus.Invalid)
            return
        }
        mutableState.value = state.copy(nameStatus = ForkNameStatus.Checking, error = null)
        nameJob = viewModelScope.launch {
            delay(400)
            try {
                val exists = source.nameExists(currentSession, state.name)
                if (currentRevision == revision && source.isActive(currentSession))
                    mutableState.value =
                        mutableState.value?.copy(
                            nameStatus =
                                if (exists) ForkNameStatus.Taken else ForkNameStatus.Available,
                        )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentRevision == revision && source.isActive(currentSession))
                    mutableState.value =
                        mutableState.value?.copy(
                            nameStatus = ForkNameStatus.Failed,
                            error = message(error),
                        )
            }
        }
    }

    fun create() {
        val state = mutableState.value ?: return
        val currentSession = session ?: return
        if (!state.canCreate || !source.isActive(currentSession)) return
        val owner =
            state.parent.owner?.login ?: state.parent.fullName?.substringBefore('/') ?: return
        val repository = state.parent.name ?: return
        mutableState.value = state.copy(submitting = true, error = null)
        viewModelScope.launch {
            try {
                // 再检查一次，避免表单停留期间名称被其他客户端占用。
                if (source.nameExists(currentSession, state.name)) {
                    mutableState.value =
                        mutableState.value?.copy(
                            submitting = false,
                            nameStatus = ForkNameStatus.Taken,
                            error = null,
                        )
                    return@launch
                }
                val created =
                    source.create(
                        currentSession,
                        owner,
                        repository,
                        state.name,
                        state.defaultBranchOnly,
                    )
                val latest = mutableState.value ?: return@launch
                mutableState.value =
                    latest.copy(
                        visible = false,
                        submitting = false,
                        // Closing the sheet opts out of the success navigation, not the write.
                        created = created.takeIf { latest.visible },
                    )
            } catch (error: CancellationException) {
                mutableState.value = null
                throw error
            } catch (error: Exception) {
                mutableState.value =
                    if (source.isActive(currentSession)) {
                        mutableState.value?.copy(
                            submitting = false,
                            error = message(error, submitting = true),
                        )
                    } else null
            }
        }
    }

    fun consumeCreated() {
        mutableState.value = mutableState.value?.copy(created = null)
    }

    private fun message(error: Exception, submitting: Boolean = false): String {
        val strings = GithubTxt.current.Repository
        return when {
            error is HttpException ->
                when (error.code()) {
                    401 -> strings.ForkUnauthorized
                    403 -> strings.ForkForbidden
                    422 -> strings.ForkRejected
                    429 -> strings.ForkRateLimited
                    else ->
                        if (submitting && error.code() >= 500) strings.ForkOutcomeUnknown
                        else strings.OperationFailed
                }
            submitting && error is IOException -> strings.ForkOutcomeUnknown
            else -> strings.OperationFailed
        }
    }
}
