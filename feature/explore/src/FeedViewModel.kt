package moe.yumeyuka.yumehub.feature.explore

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.auth.AuthSession
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.repository.DashboardFeedRepository
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.repository.StarredRepositoriesPagingKey
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.ui.component.FeedCardState
import moe.yumeyuka.yumehub.ui.component.collaborationErrorMessage
import moe.yumeyuka.yumehub.ui.component.initialCard

internal fun FeedEventDto.cardKey(): String? =
    if (workItem() != null || payload.release != null) "event:$id"
    else
        payload.target
            ?.login
            ?.takeIf { type == "FollowEvent" || type == "FollowRecommendationEvent" }
            ?.let { "user:$it" } ?: repo?.name?.let { "repo:$it" }

private data class FeedQuery(val session: AuthSession?, val options: FeedOptions)

@OptIn(ExperimentalCoroutinesApi::class)
internal class FeedViewModel(
    auth: AuthRepository,
    private val pagingRepository: PagingRepository,
    private val cache: CachedDataRepository,
    private val repositoryContent: RepositoryContentRepository,
    private val settings: SettingsStore,
    private val dashboard: DashboardFeedRepository,
) : ViewModel() {
    private var account: String? = null
    private val jobs = mutableMapOf<String, Job>()
    private val cardRequests = Semaphore(2)
    private var visible = emptyMap<String, FeedEventDto>()
    private val automaticReads = mutableMapOf<String, Job>()
    private val mutableCards = mutableStateMapOf<String, MutableState<FeedCardState>>()
    private val mutableOptions = MutableStateFlow(FeedOptions(loadFilters(), loadSort()))
    val cards: SnapshotStateMap<String, MutableState<FeedCardState>> = mutableCards
    val options = mutableOptions.asStateFlow()
    val events =
        combine(auth.session.distinctUntilChanged(), mutableOptions) { session, options ->
                FeedQuery(session, options)
            }
            .flatMapLatest { query ->
                val (session, options) = query
                if (account != session?.login) {
                    jobs.values.toList().forEach { it.cancel() }
                    jobs.clear()
                    automaticReads.clear()
                    visible = emptyMap()
                    mutableCards.clear()
                    account = session?.login
                }
                if (session == null || options.filters.isEmpty()) flowOf(PagingData.empty())
                else
                    feedPager(
                        dashboard,
                        session.login,
                        options.filters,
                        options.sort,
                        pagingRepository,
                    )
            }
            .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                if (session == null) return@collectLatest
                cache.changes(session.login).versions.collect {
                    mutableCards.entries.toList().forEach { (key, holder) ->
                        if (!holder.value.busy) {
                            val selected =
                                when {
                                    key.startsWith("user:") ->
                                        followingResource(session.login, key.removePrefix("user:"))
                                            .cached
                                    key.startsWith("repo:") ->
                                        repositoryParts(key).let { (owner, name) ->
                                            starredResource(session.login, owner, name).cached
                                        }
                                    else -> null
                                }
                            if (selected != null)
                                holder.value = holder.value.copy(selected = selected)
                        }
                    }
                }
            }
        }
    }

    fun applyOptions(options: FeedOptions) {
        val current = mutableOptions.value
        if (options.filters != current.filters) {
            settings.exploreFeedFilters.set(options.filters.mapTo(linkedSetOf()) { it.name })
        }
        if (options.sort != current.sort) settings.exploreFeedSort.set(options.sort.name)
        mutableOptions.value = options
    }

    private fun loadFilters(): Set<FeedFilter> {
        val stored = settings.exploreFeedFilters.value
        return FeedFilter.entries.filterTo(linkedSetOf()) { it.name in stored }
    }

    private fun loadSort(): FeedSort =
        FeedSort.entries.firstOrNull {
            it.name.equals(settings.exploreFeedSort.value, ignoreCase = true)
        } ?: FeedSort.Newest

    fun setVisible(events: List<FeedEventDto>) {
        val next = events.mapNotNull { event -> event.cardKey()?.let { it to event } }.toMap()
        for (key in visible.keys - next.keys) {
            val read = automaticReads.remove(key) ?: continue
            if (jobs[key] === read) {
                jobs.remove(key)
                read.cancel()
                mutableCards[key]?.let { it.value = it.value.copy(busy = false) }
            }
        }
        for ((key, event) in next) {
            if (event != visible[key]) load(event)
        }
        visible = next
    }

    fun retry(event: FeedEventDto) = load(event, retry = true)

    private fun load(event: FeedEventDto, retry: Boolean = false) {
        val key = event.cardKey() ?: return
        val account = account ?: return
        if (jobs[key]?.isActive == true) return
        val existing = mutableCards[key]?.value
        val current =
            if (retry) existing ?: cachedCard(account, event)
            else cachedCard(account, event) ?: existing
        if (
            !retry &&
                current != null &&
                (current.selected != null ||
                    event.workItem() != null ||
                    event.payload.release != null)
        ) {
            if (current != (existing ?: event.initialCard())) update(key, current)
            return
        }
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                update(key, (current ?: FeedCardState()).copy(busy = true))
                try {
                    val refreshed = cardRequests.withPermit {
                        dashboard.refreshCard(account, event)
                    }
                    currentCoroutineContext().ensureActive()
                    if (this@FeedViewModel.account != account) return@launch
                    update(
                        key,
                        refreshed.initialCard() ?: throw IllegalStateException("Missing feed card"),
                    )
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(key, (current ?: FeedCardState()).copy(busy = false))
                } finally {
                    if (jobs[key] === currentCoroutineContext()[Job]) jobs.remove(key)
                    if (automaticReads[key] === currentCoroutineContext()[Job])
                        automaticReads.remove(key)
                }
            }
        jobs[key] = job
        if (!retry) automaticReads[key] = job
        job.start()
    }

    fun toggle(event: FeedEventDto) {
        val key = event.cardKey() ?: return
        val account = account ?: return
        val state = mutableCards[key]?.value ?: cachedCard(account, event) ?: return
        val selected = state.selected ?: return
        if (state.busy) return
        jobs.remove(key)?.cancel()
        val repository =
            state.repository?.let {
                it.copy(
                    stargazersCount =
                        ((it.stargazersCount ?: 0) + if (selected) -1 else 1).coerceAtLeast(0),
                )
            }
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val userLogin = key.takeIf { it.startsWith("user:") }?.removePrefix("user:")
                val selectedResource =
                    if (userLogin != null) followingResource(account, userLogin)
                    else
                        repositoryParts(key).let { (owner, name) ->
                            starredResource(account, owner, name)
                        }
                val repoResource =
                    if (userLogin == null)
                        repositoryParts(key).let { (owner, name) ->
                            repositoryResource(account, owner, name)
                        }
                    else null
                selectedResource.update(!selected)
                repository?.let { repoResource?.update(it) }
                update(
                    key,
                    state.copy(
                        repository = repository,
                        selected = !selected,
                        busy = true,
                        actionError = null,
                    ),
                )
                try {
                    dashboard.setSelected(
                        account,
                        event.copy(subjectId = state.subjectId),
                        !selected,
                    )
                    currentCoroutineContext().ensureActive()
                    selectedResource.update(!selected)
                    repository?.let { repoResource?.update(it) }
                    if (userLogin == null) {
                        pagingRepository.invalidate(account, StarredRepositoriesPagingKey)
                    }
                    update(
                        key,
                        state.copy(
                            repository = repository,
                            selected = !selected,
                            actionError = null,
                        ),
                    )
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    selectedResource.update(selected)
                    state.repository?.let { repoResource?.update(it) }
                    update(key, state.copy(actionError = collaborationErrorMessage(error)))
                } finally {
                    if (jobs[key] === currentCoroutineContext()[Job]) jobs.remove(key)
                }
            }
        jobs[key] = job
        job.start()
    }

    private fun repositoryResource(account: String, owner: String, name: String) =
        repositoryContent.details(account, owner, name)

    private fun userResource(account: String, login: String) =
        cache.resource(
            account,
            "user:${login.lowercase(java.util.Locale.ROOT)}",
            UserDto.serializer(),
        )

    private fun starredResource(account: String, owner: String, name: String) =
        cache.resource(
            account,
            "starred-state:${owner.lowercase(java.util.Locale.ROOT)}/${name.lowercase(java.util.Locale.ROOT)}",
            Boolean.serializer(),
        )

    private fun followingResource(account: String, login: String) =
        cache.resource(
            account,
            "following-state:${login.lowercase(java.util.Locale.ROOT)}",
            Boolean.serializer(),
        )

    private fun contributorsResource(account: String, owner: String, name: String) =
        cache.resource(
            account,
            "contributors-count:${owner.lowercase(java.util.Locale.ROOT)}/${name.lowercase(java.util.Locale.ROOT)}",
            Int.serializer(),
        )

    private fun cachedCard(account: String, event: FeedEventDto): FeedCardState? {
        val key = event.cardKey() ?: return null
        if (key.startsWith("user:")) {
            val login = key.removePrefix("user:")
            val user = userResource(account, login).cached ?: event.payload.target ?: return null
            return FeedCardState(
                subjectId = event.subjectId,
                user = user,
                selected = followingResource(account, login).cached ?: event.viewerSelected,
            )
        }
        val fullName = event.repo?.name ?: return null
        val owner = fullName.substringBefore('/')
        val name = fullName.substringAfter('/')
        val repository =
            event.repositoryDetails
                ?: repositoryResource(account, owner, name).cached
                ?: return null
        return FeedCardState(
            repository = repository,
            subjectId = event.subjectId,
            work = event.workItem(),
            selected = starredResource(account, owner, name).cached ?: event.viewerSelected,
            contributorsCount =
                contributorsResource(account, owner, name).cached?.takeIf { it >= 0 }
                    ?: event.contributorsCount,
        )
    }

    private fun update(key: String, state: FeedCardState) {
        if (key !in mutableCards && mutableCards.size >= 256) {
            mutableCards.keys.firstOrNull { jobs[it]?.isActive != true }?.let(mutableCards::remove)
        }
        val current = mutableCards[key]
        if (current != null) current.value = state else mutableCards[key] = mutableStateOf(state)
    }

    private fun repositoryParts(key: String): Pair<String, String> {
        val fullName = key.removePrefix("repo:")
        return fullName.substringBefore('/') to fullName.substringAfter('/')
    }
}
