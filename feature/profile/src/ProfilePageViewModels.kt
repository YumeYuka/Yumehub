package moe.yumeyuka.yumehub.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
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
    val following: Boolean? = null,
    val followSubmitting: Boolean = false,
    val followFailed: Boolean = false,
    val followPhase: WritePhase = WritePhase.IDLE,
    val followUncertain: Boolean = false,
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
    private var confirmedProfile: ProfileDto? = null
    private val following =
        repository.followingResource(account, login.ifBlank { nodeId.orEmpty() })
    private var coordinator: MutationCoordinator? = null
    private var followObserverJob: Job? = null
    private var observedTargetLogin: String? = null
    private var loadJob: Job? = null
    private var readmeJob: Job? = null
    val isSelf: Boolean
        get() = (state.value.profile?.login ?: login).equals(account, ignoreCase = true)

    init {
        viewModelScope.launch {
            following.state.collect { relationship ->
                val coord = coordinator
                val key = observedTargetLogin?.let(::followKeyFor)
                val activeWrite = key?.let { coord?.current(it) }
                if (activeWrite == null || (activeWrite.desired == null && activeWrite.phase == WritePhase.IDLE)) {
                    mutableState.update { it.copy(following = relationship.data) }
                }
            }
        }
        viewModelScope.launch {
            val loaded =
                try {
                    repository.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            if (repository.account == account && loaded != null) {
                coordinator = loaded
                (confirmedProfile?.login ?: login.takeIf(String::isNotBlank))?.let {
                    ensureFollowObserver(it)
                }
            }
        }
        load()
    }

    private fun followKeyFor(targetLogin: String): MutationKey<Boolean> =
        MutationKey(
            MutationKind.FOLLOW,
            targetLogin.lowercase(Locale.ROOT),
            Boolean.serializer(),
        )

    private fun ensureFollowObserver(targetLogin: String) {
        val coord = coordinator ?: return
        val normalized = targetLogin.lowercase(Locale.ROOT)
        if (observedTargetLogin == normalized && followObserverJob?.isActive == true) return
        observedTargetLogin = normalized
        followObserverJob?.cancel()
        val key = followKeyFor(normalized)
        followObserverJob =
            viewModelScope.launch {
                coord.observe(key).collect { write ->
                    if (repository.account != account) return@collect
                    val base = confirmedProfile
                    val projectedFollowing =
                        write.visible ?: mutableState.value.following
                    val baseFollowing =
                        base?.viewerIsFollowing ?: following.cached
                    val projectedProfile =
                        base?.let { profile ->
                            if (
                                projectedFollowing != null &&
                                    baseFollowing != null &&
                                    !profile.isOrganization
                            ) {
                                val delta =
                                    (if (projectedFollowing) 1 else 0) -
                                        (if (baseFollowing) 1 else 0)
                                profile.copy(
                                    followers = (profile.followers + delta).coerceAtLeast(0),
                                    viewerIsFollowing = projectedFollowing,
                                )
                            } else profile.copy(viewerIsFollowing = projectedFollowing ?: profile.viewerIsFollowing)
                        }
                    mutableState.update {
                        it.copy(
                            profile = projectedProfile ?: it.profile,
                            following = projectedFollowing,
                            followSubmitting = write.phase == WritePhase.PENDING,
                            followFailed = write.phase == WritePhase.REJECTED,
                            followPhase = write.phase,
                            followUncertain = write.phase == WritePhase.UNCERTAIN,
                        )
                    }
                }
            }
    }

    fun load() {
        if (loadJob?.isActive == true) return
        readmeJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(failed = false) }
            try {
                if (repository.account != account) throw CancellationException("Account changed")
                if (login.isBlank() && nodeId == null) throw ProfileUnavailableException()
                val coord =
                    coordinator
                        ?: try {
                            repository.mutations(account).also {
                                if (repository.account == account) coordinator = it
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                val initialLogin = login.takeIf(String::isNotBlank)
                val initialStamp =
                    if (coord != null && initialLogin != null) {
                        coord.beginRead(followKeyFor(initialLogin))
                    } else null
                val profile = repository.loadUserForAccount(account, login, nodeId)
                if (repository.account != account) throw CancellationException("Account changed")
                confirmedProfile = profile
                mutableState.update { it.copy(profile = profile, unavailable = false) }
                ensureFollowObserver(profile.login)
                val key = followKeyFor(profile.login)
                val resolvedFollowing =
                    if (profile.isOrganization) {
                        val orgStamp = initialStamp ?: coord?.beginRead(key)
                        val orgFollowing =
                            following.await(force = true) {
                                dashboard.organizationFollowing(account, profile.login)
                            }
                        if (coord != null && orgStamp != null) {
                            coord.publishConfirmed(key, orgStamp, orgFollowing)
                        }
                        orgFollowing
                    } else {
                        profile.viewerIsFollowing?.also { selected ->
                            if (!isSelf && coord != null && initialStamp != null && coord.publishConfirmed(key, initialStamp, selected)) {
                                repository.followingResource(account, profile.login).update(selected)
                            }
                        }
                    }
                val slotState = coord?.current(key)
                val activeDesired = slotState?.visible ?: resolvedFollowing
                val baseFollowing = resolvedFollowing
                val projected =
                    if (
                        activeDesired != null &&
                            baseFollowing != null &&
                            !profile.isOrganization
                    ) {
                        val delta =
                            (if (activeDesired) 1 else 0) - (if (baseFollowing) 1 else 0)
                        profile.copy(
                            followers = (profile.followers + delta).coerceAtLeast(0),
                            viewerIsFollowing = activeDesired,
                        )
                    } else profile
                mutableState.update {
                    it.copy(
                        profile = projected,
                        following = activeDesired ?: it.following,
                        unavailable = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProfileUnavailableException) {
                confirmedProfile = null
                mutableState.update { UserProfileState(unavailable = true) }
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            }
        }
    }

    fun retryReadme() {
        val profile = confirmedProfile ?: return
        if (loadJob?.isActive == true || readmeJob?.isActive == true || profile.readmeResolved) return
        readmeJob = viewModelScope.launch {
            val result = try {
                repository.loadProfileReadme(account, profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            if (repository.account != account || confirmedProfile !== profile || !result.readmeResolved) return@launch
            confirmedProfile = profile.withReadme(result)
            mutableState.update { it.copy(profile = it.profile?.withReadme(result)) }
        }
    }

    fun toggleFollowing() {
        val current = state.value
        val selected = current.following ?: return
        val profile = current.profile ?: return
        val userId = profile.nodeId ?: return
        if (
            isSelf ||
                current.unavailable ||
                repository.account != account
        )
            return
        val initial = coordinator?.current(followKeyFor(profile.login))
        if (initial?.phase == WritePhase.UNCERTAIN) { reconcileFollowing(); return }
        val target = !(initial?.visible ?: selected)
        viewModelScope.launch {
            val coord =
                coordinator
                    ?: try {
                        repository.mutations(account).also { coordinator = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        mutableState.update { it.copy(followFailed = true) }
                        return@launch
                    }
            ensureFollowObserver(profile.login)
            val key = followKeyFor(profile.login)
            val accepted =
                coord.submit(
                    key,
                    confirmed = confirmedProfile?.viewerIsFollowing ?: selected,
                    desired = target,
                    tags =
                        setOf(
                            CacheTag(DataDomain.Profile, profile.login.lowercase(Locale.ROOT)),
                            relationshipCacheTag(profile.login),
                        ),
                    send = { nextFollowing ->
                        val updated =
                            if (profile.isOrganization) {
                                dashboard.setOrganizationFollowing(
                                    account,
                                    profile.login,
                                    nextFollowing,
                                )
                            } else {
                                repository.setFollowing(account, userId, nextFollowing)
                            }
                        repository.followingResource(account, profile.login).update(updated)
                        updated
                    },
                    readBack = {
                        if (profile.isOrganization) {
                            dashboard.organizationFollowing(account, profile.login)
                        } else {
                            requireNotNull(repository.loadUserForAccount(account, profile.login, userId).viewerIsFollowing)
                        }
                    },
                )
            if (!accepted) {
                mutableState.update { it.copy(followUncertain = true) }
            }
        }
    }

    fun reconcileFollowing() {
        val profile = state.value.profile ?: return
        val coord = coordinator ?: return
        coord.reconcile(followKeyFor(profile.login)) {
            if (profile.isOrganization) {
                dashboard.organizationFollowing(account, profile.login)
            } else {
                requireNotNull(repository.loadUserForAccount(account, profile.login, profile.nodeId).viewerIsFollowing)
            }
        }
    }

    fun dismissFollowError() {
        mutableState.update { it.copy(followFailed = false) }
    }
}

internal fun ProfileDto.withReadme(document: ProfileDto): ProfileDto = copy(
    readmeHtml = document.readmeHtml,
    readmePath = document.readmePath,
    readmeResolved = document.readmeResolved,
)

