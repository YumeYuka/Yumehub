package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.dto.ForkRepositoryRequest
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.MutationRejectedException
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.RepositoryForkRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
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
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
    val error: String? = null,
    val draftReady: Boolean = false,
) {
    val canCreate: Boolean
        get() =
            visible &&
                draftReady &&
                !submitting &&
                !uncertain &&
                nameStatus == ForkNameStatus.Available
}

private class ForkNameTakenException : MutationRejectedException()

internal class RepositoryForkViewModel(
    private val source: RepositoryForkRepository,
    private val drafts: CommentDraftStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow<RepositoryForkState?>(null)
    val uiState = mutableState.asStateFlow()
    private var session: RepositoryForkRepository.Session? = null
    private var nameJob: Job? = null
    private var revision = 0L
    private var formGeneration = 0L
    val created = MutableSharedFlow<RepositoryDto>(extraBufferCapacity = 1)

    private fun draftKey(state: RepositoryForkState): String =
        "fork/${(state.parent.fullName ?: "${state.parent.owner?.login}/${state.parent.name}").lowercase(Locale.ROOT)}"

    private fun saveDraft(state: RepositoryForkState) {
        val request = ForkRepositoryRequest(state.name, state.defaultBranchOnly)
        drafts.saveEncoded(state.account, draftKey(state)) { Json.encodeToString(request) }
    }

    fun open(parent: RepositoryDto) {
        val previous = mutableState.value
        if (
            previous != null &&
                previous.parent.fullName == parent.fullName &&
                (previous.submitting || previous.uncertain)
        ) {
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
        val generation = ++formGeneration
        val initial = checkNotNull(mutableState.value)
        viewModelScope.launch {
            val encoded = drafts.loadOrNull(currentSession.account, draftKey(initial))
            if (generation != formGeneration || !source.isActive(currentSession)) return@launch
            val draft =
                try {
                    encoded?.let { Json.decodeFromString<ForkRepositoryRequest>(it) }
                } catch (_: kotlinx.serialization.SerializationException) {
                    null
                }
            mutableState.value =
                initial.copy(
                    name = draft?.name ?: initial.name,
                    defaultBranchOnly = draft?.defaultBranchOnly ?: initial.defaultBranchOnly,
                    draftReady = true,
                )
            checkName()
        }
    }

    fun dismiss() {
        val state = mutableState.value ?: return
        nameJob?.cancel()
        formGeneration++
        revision++
        mutableState.value = state.copy(visible = false)
    }

    fun name(value: String) {
        val state = mutableState.value ?: return
        if (
            !state.draftReady ||
                state.submitting ||
                state.uncertain ||
                value.length > ForkNameMaxLength
        )
            return
        mutableState.value = state.copy(name = value, error = null).also(::saveDraft)
        checkName()
    }

    fun defaultBranchOnly(value: Boolean) {
        val state = mutableState.value ?: return
        if (state.draftReady && !state.submitting && !state.uncertain)
            mutableState.value =
                state.copy(defaultBranchOnly = value, error = null).also(::saveDraft)
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
        val key =
            MutationKey(
                MutationKind.FORK,
                "${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}:${currentSession.account.lowercase(Locale.ROOT)}",
                RepositoryDto.serializer(),
            )
        val tags = setOf(CacheTag(DataDomain.Repositories, "my-repositories"))
        val generation = formGeneration
        mutableState.value = state.copy(submitting = true, error = null)
        viewModelScope.launch {
            val store = drafts
            val storedKey = draftKey(state)
            val account = currentSession.account
            val encoded =
                Json.encodeToString(ForkRepositoryRequest(state.name, state.defaultBranchOnly))
            if (!store.saveConfirmed(account, storedKey, encoded)) {
                mutableState.value =
                    mutableState.value?.copy(
                        submitting = false,
                        error = GithubTxt.current.Common.MutationNotSubmitted,
                    )
                return@launch
            }
            val coord = source.mutations(currentSession.account)
            var capturedError: Exception? = null
            val receipt =
                coord.submitCommand(
                    key = key,
                    tags = tags,
                    onConfirmed = { store.removeIfUnchanged(account, storedKey, encoded) },
                    send = {
                        try {
                            // 再检查一次，避免表单停留期间名称被其他客户端占用；此预检明确拒绝，不作已存在确认。
                            if (source.nameExists(currentSession, state.name)) {
                                throw ForkNameTakenException()
                            }
                            source.create(
                                currentSession,
                                owner,
                                repository,
                                state.name,
                                state.defaultBranchOnly,
                            )
                        } catch (error: Exception) {
                            capturedError = error
                            throw error
                        }
                    },
                    readBack = { null },
                )
            val cmd = receipt?.await() ?: coord.currentCommand(key)
            if (!source.isActive(currentSession)) return@launch
            val latest = mutableState.value ?: return@launch
            if (latest.parent.fullName != state.parent.fullName) return@launch
            val result = cmd.result
            val navigate =
                receipt != null &&
                    cmd.phase == WritePhase.IDLE &&
                    result != null &&
                    latest.visible &&
                    generation == formGeneration
            mutableState.value =
                latest.copy(
                    submitting = false,
                    visible = latest.visible && !navigate,
                    uncertain = cmd.phase == WritePhase.UNCERTAIN,
                    uncertainOperationId =
                        cmd.submissionId.takeIf { cmd.phase == WritePhase.UNCERTAIN },
                    nameStatus =
                        if (capturedError is ForkNameTakenException) ForkNameStatus.Taken
                        else latest.nameStatus,
                    error =
                        when {
                            cmd.phase == WritePhase.UNCERTAIN ->
                                GithubTxt.current.Repository.ForkOutcomeUnknown
                            cmd.phase == WritePhase.REJECTED || receipt == null ->
                                capturedError
                                    ?.takeUnless { it is ForkNameTakenException }
                                    ?.let { message(it, submitting = true) }
                                    ?: cmd.message
                                    ?: GithubTxt.current.Common.MutationNotSubmitted
                            else -> null
                        },
                )
            if (navigate && result != null) created.tryEmit(result)
        }
    }

    fun acknowledgeUncertain() {
        val state = mutableState.value ?: return
        val opId = state.uncertainOperationId
        val owner = state.parent.owner?.login ?: state.parent.fullName?.substringBefore('/')
        val repository = state.parent.name
        viewModelScope.launch {
            val coord = source.mutations(state.account)
            val id =
                opId
                    ?: if (owner != null && repository != null) {
                        val target =
                            "${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}:${state.account.lowercase(Locale.ROOT)}"
                        coord.pending.value
                            .firstOrNull { it.kind == MutationKind.FORK && it.target == target }
                            ?.operationId
                    } else null
            if (id == null) return@launch
            coord.acknowledge(id).join()
            if (coord.pending.value.any { it.operationId == id }) return@launch
            mutableState.value =
                mutableState.value?.copy(
                    uncertain = false,
                    uncertainOperationId = null,
                    error = null,
                )
        }
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
