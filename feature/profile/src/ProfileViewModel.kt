package moe.yumeyuka.yumehub.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileOrganizationDto
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.repository.ProfileRepository

internal sealed interface ProfileUiState {
    data object Loading : ProfileUiState

    data class Content(
        val profile: ProfileDto,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
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
    private val mutableUiState = MutableStateFlow(resource.state.value.toUiState())
    private val mutableStatusEditor = MutableStateFlow<StatusEditorState?>(null)
    private var cachedOrganizations: List<ProfileOrganizationDto> = emptyList()
    private var organizationsJob: Job? = null

    val uiState: StateFlow<ProfileUiState> = mutableUiState.asStateFlow()
    val statusEditor: StateFlow<StatusEditorState?> = mutableStatusEditor.asStateFlow()

    init {
        viewModelScope.launch { resource.state.collect { mutableUiState.value = it.toUiState() } }
    }

    fun refresh() {
        if (mutableStatusEditor.value?.blocksRefresh == true) return
        repository.refresh(account, force = true)
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
        // Closing the sheet is not a cancellation of a write already sent to GitHub.
        mutableStatusEditor.value = if (editor.submitting) editor.copy(visible = false) else null
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
        mutableStatusEditor.value = editor.copy(submitting = true, failed = false)
        viewModelScope.launch {
            try {
                repository.updateStatus(
                    account = account,
                    message = if (clear) null else editor.message.trim().ifBlank { null },
                    emoji = if (clear) null else editor.emoji.trim().ifBlank { null },
                    limitedAvailability = if (clear) false else editor.limitedAvailability,
                    expiresAt =
                        if (clear) null
                        else
                            editor.expiry.millis
                                .takeIf { it > 0L }
                                ?.let { Instant.now().plusMillis(it).toString() },
                    organizationId = if (clear) null else editor.organizationId,
                )
                mutableStatusEditor.value = null
            } catch (cancelled: CancellationException) {
                mutableStatusEditor.value = null
                throw cancelled
            } catch (_: Exception) {
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

    private fun CachedDataState<ProfileDto>.toUiState(): ProfileUiState =
        data?.let { ProfileUiState.Content(it, loading, error != null) }
            ?: if (error != null) ProfileUiState.Error else ProfileUiState.Loading
}
