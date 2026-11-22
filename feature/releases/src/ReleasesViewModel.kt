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
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdate
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdates
import moe.yumeyuka.yumehub.data.repository.GithubReleaseResult
import moe.yumeyuka.yumehub.data.repository.ReleaseRepository
import moe.yumeyuka.yumehub.data.repository.withToggledReaction
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal data class ReleaseState(
    val result: GithubReleaseResult? = null,
    val loading: Boolean = true,
    val failure: Exception? = null,
    val selectedVersionId: String? = null,
)

internal class ReleasesViewModel(
    private val repository: ReleaseRepository,
    private val route: ReleasesRoute,
    private val account: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReleaseState())
    val state = mutableState.asStateFlow()
    val reactionUpdates = GithubReactionUpdates()
    private val mutableReactionFailure = MutableStateFlow<Exception?>(null)
    val reactionFailure = mutableReactionFailure.asStateFlow()
    private val reactionPending: Boolean
        get() =
            state.value.result?.detail?.let {
                reactionUpdates.current(it.version.id, it.reactions)?.pending
            } == true

    val timeline by lazy {
        repository.versions(account, route.owner, route.name).cachedIn(viewModelScope)
    }

    private var request: Job? = null

    init {
        load()
    }

    fun select(id: String) {
        if (reactionPending) return
        if (id == state.value.result?.detail?.version?.id) {
            request?.cancel()
            mutableState.value =
                state.value.copy(selectedVersionId = id, loading = false, failure = null)
            return
        }
        if (state.value.selectedVersionId == id && state.value.loading) return
        load(id = id)
    }

    fun retry() {
        val current = state.value
        if (!reactionPending && current.failure != null) {
            load(id = current.selectedVersionId)
        }
    }

    fun assets(id: String) = repository.assets(account, id)

    fun toggleReaction(id: String, content: GithubReactionContent, reacted: Boolean) {
        val current = state.value
        val release = current.result?.detail ?: return
        if (release.version.id != id || !release.canReact || current.loading) return
        val previous = reactionUpdates.current(id, release.reactions)
        if (previous?.pending == true) return
        val reactions = previous?.reactions ?: release.reactions
        if (reactions.any { it.content == content && it.viewerReacted } != reacted) return
        val pending =
            GithubReactionUpdate(
                release.reactions,
                reactions.withToggledReaction(content),
                pending = true,
            )
        reactionUpdates.set(id, pending)
        mutableReactionFailure.value = null
        viewModelScope.launch {
            try {
                val confirmed = repository.toggleReaction(
                    account,
                    route.owner,
                    route.name,
                    release,
                    content,
                    reacted,
                )
                reactionUpdates.set(id, pending.copy(reactions = confirmed, pending = false))
            } catch (error: CancellationException) {
                reactionUpdates.set(id, previous)
                throw error
            } catch (error: Exception) {
                reactionUpdates.set(id, previous)
                mutableReactionFailure.value = error
            }
        }
    }

    private fun load(id: String? = null) {
        request?.cancel()
        mutableReactionFailure.value = null
        mutableState.value =
            state.value.copy(
                selectedVersionId = id,
                loading = true,
                failure = null,
            )
        request = viewModelScope.launch {
            try {
                val result =
                    if (id == null) {
                        repository.load(account, route.owner, route.name, route.tag)
                    } else {
                        repository.loadVersion(account, id)
                    }
                ensureActive()
                val current = state.value
                mutableState.value = current.copy(result = result, loading = false)
                val previousId = current.result?.detail?.version?.id
                if (previousId != null && previousId != result.detail?.version?.id) {
                    reactionUpdates.set(previousId, null)
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
