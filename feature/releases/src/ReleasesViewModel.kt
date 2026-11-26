package moe.yumeyuka.yumehub.feature.releases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdate
import moe.yumeyuka.yumehub.data.repository.GithubReleaseResult
import moe.yumeyuka.yumehub.data.repository.ReleaseRepository
import moe.yumeyuka.yumehub.data.repository.convergeGithubReactions
import moe.yumeyuka.yumehub.data.repository.withToggledReaction
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal data class ReleaseState(
    val result: GithubReleaseResult? = null,
    val loading: Boolean = true,
    val failure: Throwable? = null,
    val selectedVersionId: String? = null,
)

internal class ReleasesViewModel(
    private val repository: ReleaseRepository,
    private val route: ReleasesRoute,
    private val account: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReleaseState())
    val state = mutableState.asStateFlow()
    val reactionUpdates = repository.reactionUpdates(account)
    private val mutableReactionFailure = MutableStateFlow<Exception?>(null)
    val reactionFailure = mutableReactionFailure.asStateFlow()
    private var coordinator: MutationCoordinator? = null
    private val reactionObservers = mutableMapOf<String, Job>()

    val timeline by lazy {
        repository.versions(account, route.owner, route.name).cachedIn(viewModelScope)
    }

    val versionsRevalidation by lazy {
        repository.revalidation(account, repository.versionsKey(route.owner, route.name))
    }

    fun retryVersionsRevalidation() {
        repository.retryRevalidation(account, repository.versionsKey(route.owner, route.name))
    }

    fun assetsRevalidation(id: String) =
        repository.revalidation(account, repository.assetsKey(id))

    fun retryAssetsRevalidation(id: String) {
        repository.retryRevalidation(account, repository.assetsKey(id))
    }

    private var request: Job? = null

    init {
        viewModelScope.launch {
            coordinator =
                try {
                    repository.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
        }
        load()
    }

    private fun reactionKey(id: String) =
        MutationKey(MutationKind.REACTION, id, ListSerializer(GithubReactionGroup.serializer()))

    fun select(id: String) {
        if (id == state.value.selectedVersionId && state.value.failure == null) return
        load(id = id)
    }

    fun retry() {
        val current = state.value
        if (current.failure != null) {
            load(id = current.selectedVersionId)
        }
    }

    fun assets(id: String) = repository.assets(account, id)

    fun toggleReaction(id: String, content: GithubReactionContent, reacted: Boolean) {
        val current = state.value
        val release = current.result?.detail ?: return
        if (release.version.id != id || !release.canReact || current.loading) return
        val coord = coordinator
        val slotReactions = coord?.current(reactionKey(id))?.visible
        val previous = reactionUpdates.current(id, release.reactions)
        val reactions = slotReactions ?: previous?.reactions ?: release.reactions
        if (reactions.any { it.content == content && it.viewerReacted } != reacted) return
        val desired = reactions.withToggledReaction(content)
        val pending = GithubReactionUpdate(release.reactions, desired, pending = true)
        reactionUpdates.set(id, pending)
        mutableReactionFailure.value = null
        viewModelScope.launch {
            val activeCoord =
                coord
                    ?: try {
                        repository.mutations(account).also { coordinator = it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        reactionUpdates.set(id, previous)
                        mutableReactionFailure.value = error
                        return@launch
                    }
            ensureReactionObserver(activeCoord, id, release.reactions)
            val accepted =
                activeCoord.submit(
                    reactionKey(id),
                    confirmed = release.reactions,
                    desired = desired,
                    tags =
                        setOf(
                            repositoryCacheTag(route.owner, route.name),
                            CacheTag(DataDomain.Content, null),
                        ),
                    send = { nextDesired ->
                        val baseline = activeCoord.current(reactionKey(id)).confirmed ?: release.reactions
                        convergeGithubReactions(baseline, nextDesired, { activeCoord.confirmStep(reactionKey(id), it) }) { targetContent, currentReacted ->
                            repository.toggleReaction(
                                account,
                                route.owner,
                                route.name,
                                release,
                                targetContent,
                                currentReacted,
                            )
                        }
                    },
                    readBack = {
                        requireNotNull(repository.readVersion(account, id).detail).reactions
                    },
                )
            if (!accepted) {
                reactionUpdates.set(id, previous)
                mutableReactionFailure.value = IllegalStateException("Result unconfirmed")
            }
        }
    }

    private fun ensureReactionObserver(
        coord: MutationCoordinator,
        id: String,
        baseline: List<GithubReactionGroup>,
    ) {
        if (reactionObservers[id]?.isActive == true) return
        reactionObservers[id] =
            viewModelScope.launch {
                coord.observe(reactionKey(id)).collect { write ->
                    val source = state.value.result?.detail?.takeIf { it.version.id == id }?.reactions
                        ?: baseline
                    val reactions = write.visible
                    if (reactions != null) {
                        reactionUpdates.set(
                            id,
                            GithubReactionUpdate(
                                source = source,
                                reactions = reactions,
                                pending = write.phase == WritePhase.PENDING,
                            ),
                        )
                    }
                    if (write.phase == WritePhase.REJECTED || write.phase == WritePhase.UNCERTAIN) {
                        mutableReactionFailure.value =
                            IllegalStateException(write.message ?: "Reaction failed")
                    }
                }
            }
    }

    private fun load(id: String? = null) {
        request?.cancel()
        mutableReactionFailure.value = null
        val cached = state.value.result?.takeIf { state.value.selectedVersionId == id || it.detail?.version?.id == id }
        mutableState.value = ReleaseState(result = cached, selectedVersionId = id, loading = cached == null)
        request = viewModelScope.launch {
            try {
                repository.observe(account, route.owner, route.name, route.tag, id).collect { frame ->
                    val content = frame.data ?: cached
                    mutableState.value = ReleaseState(
                        result = content,
                        loading = content == null && frame.error == null,
                        failure = frame.error,
                        selectedVersionId = id,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                mutableState.value = state.value.copy(loading = false, failure = error)
            }
        }
    }
}

val releasesFeatureModule = module {
    viewModel { (route: ReleasesRoute, account: String) ->
        ReleasesViewModel(get(), route, account)
    }
}
