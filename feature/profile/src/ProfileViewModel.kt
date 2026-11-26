package moe.yumeyuka.yumehub.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileOrganizationDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileStatusDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.NullableValue
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.ProfileRepository

internal sealed interface ProfileUiState {
    data object Loading : ProfileUiState

    data class Content(
        val profile: ProfileDto,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
        val statusPhase: WritePhase = WritePhase.IDLE,
    ) : ProfileUiState

    data object Error : ProfileUiState
}

internal data class StatusEditorState(
    val visible: Boolean = true,
    val hadStatus: Boolean = false,
    val emoji: String = "",
    val message: String = "",
    val limitedAvailability: Boolean = false,
    val expiry: StatusExpiry = StatusExpiry.Never,
    val organizationId: String? = null,
    val organizations: List<ProfileOrganizationDto> = emptyList(),
    val submitting: Boolean = false,
    val failed: Boolean = false,
) {
    val blocksRefresh: Boolean get() = visible || submitting
}

internal enum class StatusExpiry(val millis: Long) {
    Never(0L),
    ThirtyMinutes(30 * 60 * 1_000L),
    OneHour(60 * 60 * 1_000L),
    FourHours(4 * 60 * 60 * 1_000L),
    OneDay(24 * 60 * 60 * 1_000L),
    OneWeek(7 * 24 * 60 * 60 * 1_000L),
}

internal class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val account = repository.account
    private val resource = repository.resource(account)
    private var latestCached: CachedDataState<ProfileDto> = resource.state.value
    private val mutableUiState = MutableStateFlow(latestCached.toUiState())
    private val mutableStatusEditor = MutableStateFlow<StatusEditorState?>(null)
    private var cachedOrganizations: List<ProfileOrganizationDto> = emptyList()
    private var organizationsJob: Job? = null
    private var coordinator: MutationCoordinator? = null
    private val statusKey =
        MutationKey(
            MutationKind.VIEWER_STATUS,
            "viewer",
            NullableValue.serializer(ProfileStatusDto.serializer()),
        )

    val uiState: StateFlow<ProfileUiState> = mutableUiState.asStateFlow()
    val statusEditor: StateFlow<StatusEditorState?> = mutableStatusEditor.asStateFlow()

    init {
        viewModelScope.launch {
            resource.state.collect {
                latestCached = it
                mutableUiState.value = it.toUiState()
            }
        }
        viewModelScope.launch {
            val loaded =
                try {
                    repository.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
            if (repository.account != account) return@launch
            coordinator = loaded
            loaded.observe(statusKey).collect { write ->
                if (repository.account != account) return@collect
                mutableUiState.value = latestCached.toUiState()
                mutableStatusEditor.update { editor ->
                    when (write.phase) {
                        WritePhase.PENDING -> editor?.copy(submitting = true, failed = false)
                        WritePhase.IDLE -> if (editor?.submitting == true) null else editor
                        WritePhase.REJECTED,
                        WritePhase.UNCERTAIN ->
                            editor?.copy(submitting = false, failed = true)
                    }
                }
            }
        }
    }

    fun refresh() {
        if (mutableStatusEditor.value?.blocksRefresh == true) return
        val coord = coordinator
        val stamp = coord?.beginRead(statusKey)
        resource.refresh(force = true) {
            repository.loadUserForAccount(account, account).also { loaded ->
                if (coord != null && stamp != null) {
                    coord.publishConfirmed(statusKey, stamp, NullableValue(loaded.status))
                }
            }
        }
    }

    fun openStatusEditor() {
        val existing = mutableStatusEditor.value
        if (existing != null) {
            mutableStatusEditor.value = existing.copy(visible = true)
            return
        }
        val profile = (uiState.value as? ProfileUiState.Content)?.profile ?: return
        val status = profile.status
        mutableStatusEditor.value =
            StatusEditorState(
                hadStatus = status != null,
                emoji = status?.emoji.orEmpty(),
                message = status?.message.orEmpty(),
                limitedAvailability = status?.indicatesLimitedAvailability == true,
                expiry = expiryFrom(status?.expiresAt),
                organizationId = status?.organization?.id,
                organizations =
                    (listOfNotNull(status?.organization) + cachedOrganizations).distinctBy {
                        it.id
                    },
            )
        loadOrganizations()
    }

    private fun loadOrganizations() {
        if (organizationsJob?.isActive == true) return
        organizationsJob = viewModelScope.launch {
            val loaded =
                try {
                    repository.organizations(account)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (loaded == null) return@launch
            cachedOrganizations = loaded
            mutableStatusEditor.update { editor ->
                editor?.copy(
                    organizations =
                        (loaded + editor.organizations.filter { it.id == editor.organizationId })
                            .distinctBy { it.id },
                )
            }
        }
    }

    fun dismissStatusEditor() {
        val editor = mutableStatusEditor.value ?: return
        // 关闭编辑器保留草稿与状态；已发起写入仍在会话 scope 继续。
        mutableStatusEditor.value = editor.copy(visible = false)
    }

    fun setStatusEmoji(emoji: String) {
        mutableStatusEditor.update { it?.copy(emoji = emoji, failed = false) }
    }

    fun setStatusMessage(message: String) {
        mutableStatusEditor.update { it?.copy(message = message, failed = false) }
    }

    fun setStatusLimitedAvailability(enabled: Boolean) {
        mutableStatusEditor.update { it?.copy(limitedAvailability = enabled) }
    }

    fun setStatusExpiry(expiry: StatusExpiry) {
        mutableStatusEditor.update { it?.copy(expiry = expiry) }
    }

    fun setStatusOrganization(organizationId: String?) {
        mutableStatusEditor.update { editor ->
            if (
                editor == null ||
                    editor.submitting ||
                    (organizationId != null &&
                        editor.organizations.none { it.id == organizationId })
            ) {
                editor
            } else {
                editor.copy(organizationId = organizationId)
            }
        }
    }

    fun saveStatus() {
        submitStatus(clear = false)
    }

    fun clearStatus() {
        submitStatus(clear = true)
    }

    private fun submitStatus(clear: Boolean) {
        val editor = mutableStatusEditor.value ?: return
        if (!editor.visible || editor.submitting) return
        val expiresAt =
            if (clear) null
            else editor.expiry.millis.takeIf { it > 0L }?.let { Instant.now().plusMillis(it).toString() }
        val message = if (clear) null else editor.message.trim().ifBlank { null }
        val emoji = if (clear) null else editor.emoji.trim().ifBlank { null }
        val limitedAvailability = if (clear) false else editor.limitedAvailability
        val organizationId = if (clear) null else editor.organizationId
        val organization =
            organizationId?.let { id -> editor.organizations.firstOrNull { it.id == id } }
        val desiredStatus =
            if (clear || (message == null && emoji == null && !limitedAvailability)) null
            else
                ProfileStatusDto(
                    emoji = emoji,
                    message = message,
                    indicatesLimitedAvailability = limitedAvailability,
                    expiresAt = expiresAt,
                    organization = organization,
                )
        viewModelScope.launch {
            val coord =
                coordinator
                    ?: try {
                        repository.mutations(account).also { coordinator = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableStatusEditor.update { it?.copy(submitting = false, failed = true) }
                        return@launch
                    }
            val currentConfirmed =
                (uiState.value as? ProfileUiState.Content)?.profile?.status ?: latestCached.data?.status
            val accepted =
                coord.submit(
                    statusKey,
                    confirmed = NullableValue(currentConfirmed),
                    desired = NullableValue(desiredStatus),
                    tags = setOf(CacheTag(DataDomain.Profile, null)),
                    send = { _ ->
                        val updated =
                            repository.updateStatus(
                                account = account,
                                message = message,
                                emoji = emoji,
                                limitedAvailability = limitedAvailability,
                                expiresAt = expiresAt,
                                organizationId = organizationId,
                            )
                        NullableValue(updated)
                    },
                    readBack = {
                        NullableValue(repository.loadUserForAccount(account, account).status)
                    },
                )
            if (accepted) {
                mutableStatusEditor.value = editor.copy(visible = false, submitting = true, failed = false)
            } else {
                mutableStatusEditor.update { it?.copy(submitting = false, failed = true) }
            }
        }
    }

    private fun expiryFrom(value: String?): StatusExpiry {
        if (value.isNullOrBlank()) return StatusExpiry.Never
        val remaining =
            runCatching { Instant.parse(value).toEpochMilli() - System.currentTimeMillis() }
                .getOrNull() ?: return StatusExpiry.Never
        if (remaining <= 0L) return StatusExpiry.Never
        return when {
            remaining >= StatusExpiry.OneWeek.millis -> StatusExpiry.OneWeek
            remaining >= StatusExpiry.OneDay.millis -> StatusExpiry.OneDay
            remaining >= StatusExpiry.FourHours.millis -> StatusExpiry.FourHours
            remaining >= StatusExpiry.OneHour.millis -> StatusExpiry.OneHour
            else -> StatusExpiry.ThirtyMinutes
        }
    }

    private fun CachedDataState<ProfileDto>.toUiState(): ProfileUiState {
        val statusWrite = coordinator?.current(statusKey)
        val statusOverride = statusWrite?.visible
        val projectedData =
            data?.let { profile ->
                if (statusOverride != null) profile.copy(status = statusOverride.value) else profile
            }
        return projectedData?.let {
            ProfileUiState.Content(
                it,
                loading,
                error != null,
                statusPhase = statusWrite?.phase ?: WritePhase.IDLE,
            )
        } ?: if (error != null) ProfileUiState.Error else ProfileUiState.Loading
    }
}
