package moe.yumeyuka.yumehub.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.data.repository.DashboardFeedRepository
import moe.yumeyuka.yumehub.data.repository.ProfileRepository
import moe.yumeyuka.yumehub.data.repository.ProfileUnavailableException
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

internal class FollowViewModel(
    private val api: GitHubGraphQlApi,
    private val login: String,
    private val tokens: TokenStore,
) : ViewModel() {
    private fun pages(following: Boolean) =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 5, enablePlaceholders = false)) {
                FollowPagingSource(api, login, following, tokens)
            }
            .flow
            .cachedIn(viewModelScope)

    val following = pages(true)
    val followers = pages(false)
}

internal data class UserProfileState(
    val profile: ProfileDto? = null,
    val failed: Boolean = false,
    val unavailable: Boolean = false,
    val readmeFailed: Boolean = false,
    val following: Boolean? = null,
    val followSubmitting: Boolean = false,
    val followFailed: Boolean = false,
)

internal class UserProfileViewModel(
    private val repository: ProfileRepository,
    private val login: String,
    private val nodeId: String?,
    private val dashboard: DashboardFeedRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UserProfileState())
    val state = mutableState.asStateFlow()
    private val account = repository.account
    private val following =
        repository.followingResource(account, login.ifBlank { nodeId.orEmpty() })
    private var loadJob: Job? = null
    val isSelf: Boolean
        get() = (state.value.profile?.login ?: login).equals(account, ignoreCase = true)

    init {
        viewModelScope.launch {
            following.state.collect { relationship ->
                mutableState.update { it.copy(following = relationship.data) }
            }
        }
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(failed = false) }
            try {
                if (repository.account != account) throw CancellationException("Account changed")
                if (login.isBlank() && nodeId == null) throw ProfileUnavailableException()
                val profile = repository.loadUser(login, nodeId)
                if (repository.account != account) throw CancellationException("Account changed")
                mutableState.update {
                    it.copy(
                        profile = profile,
                        unavailable = false,
                        readmeFailed = !profile.readmeResolved,
                    )
                }
                if (profile.isOrganization) {
                    following.await(force = true) {
                        dashboard.organizationFollowing(account, profile.login)
                    }
                }
                profile.viewerIsFollowing?.let { selected ->
                    if (!isSelf) following.update(selected)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProfileUnavailableException) {
                // 保留路由带来的已知资料；不可用账号不伪装成空资料或触发循环请求。
                mutableState.update { it.copy(unavailable = true, failed = false) }
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            }
        }
    }

    fun retryReadme() = load()

    fun toggleFollowing() {
        val current = state.value
        val selected = current.following ?: return
        val userId = current.profile?.nodeId ?: return
        if (
            isSelf ||
                current.unavailable ||
                current.followSubmitting ||
                repository.account != account
        )
            return
        mutableState.update { it.copy(followSubmitting = true, followFailed = false) }
        viewModelScope.launch {
            try {
                val updated =
                    if (current.profile.isOrganization)
                        dashboard.setOrganizationFollowing(
                            account,
                            current.profile.login,
                            !selected,
                        )
                    else repository.setFollowing(account, userId, !selected)
                following.update(updated)
                repository.relationshipChanged(account)
                mutableState.update {
                    it.copy(
                        followSubmitting = false,
                        profile =
                            it.profile?.let { profile ->
                                val delta =
                                    if (profile.isOrganization || updated == selected) 0
                                    else if (updated) 1 else -1
                                profile.copy(
                                    followers = (profile.followers + delta).coerceAtLeast(0),
                                    viewerIsFollowing = updated,
                                )
                            },
                    )
                }
                repository.resource(account).invalidate()
                repository.refresh(account, force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(followSubmitting = false, followFailed = true) }
            }
        }
    }

    fun dismissFollowError() {
        mutableState.update { it.copy(followFailed = false) }
    }
}
