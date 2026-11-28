package moe.yumeyuka.yumehub.feature.explore

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.data.auth.AuthRepository
import moe.yumeyuka.yumehub.data.auth.AuthSession
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.DashboardFeedRepository
import moe.yumeyuka.yumehub.data.repository.PagingRepository
import moe.yumeyuka.yumehub.data.repository.RepositoryContentRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.locale.CurrentAppStrings
import moe.yumeyuka.yumehub.ui.component.FeedCardState
import moe.yumeyuka.yumehub.ui.component.initialCard

internal fun FeedEventDto.cardKey(): String? =
    if (workItem() != null || payload.release != null) "event:$id"
    else
        payload.target
            ?.login
            ?.takeIf { type == "FollowEvent" || type == "FollowRecommendationEvent" }
            ?.let { "user:$it" } ?: repo?.name?.let { "repo:$it" }

internal class FeedViewModel(
    auth: AuthRepository,
    private val pagingRepository: PagingRepository,
    private val cache: CachedDataRepository,
    private val repositoryContent: RepositoryContentRepository,
    private val settings: SettingsStore,
    private val dashboard: DashboardFeedRepository,
) : ViewModel() {
    private var account: String? = null
    private var coordinatorAccount: String? = null
    private var coordinator: MutationCoordinator? = null
    private val jobs = mutableMapOf<String, Job>()
    private val cardObservers = mutableMapOf<String, Job>()
    private val cardRequests = Semaphore(2)
    private var visible = emptyMap<String, FeedEventDto>()
    private val automaticReads = mutableMapOf<String, Job>()
    private val mutableCards = mutableStateMapOf<String, MutableState<FeedCardState>>()
    private val mutableOptions = MutableStateFlow<FeedOptions?>(null)
    private var settingsWrite: Job? = null
    val settingsFailed = MutableStateFlow(false)
    private val remoteFilters = MutableStateFlow<Set<FeedFilter>?>(null)
    private val filterPhase = MutableStateFlow(WritePhase.IDLE)
    private var applying: FeedOptions? = null
    val cards: SnapshotStateMap<String, MutableState<FeedCardState>> = mutableCards
    val options = mutableOptions.asStateFlow()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    val session = mutableSession.asStateFlow()

    fun eventsFor(login: String?, options: FeedOptions?): Flow<PagingData<FeedEventDto>> =
        if (login == null || options == null || options.filters.isEmpty())
            flowOf(PagingData.empty())
        else feedPager(dashboard, login, options.filters, options.sort, pagingRepository)

    fun revalidation(login: String, key: String) = pagingRepository.revalidation(login, key)

    fun retryRevalidation(login: String, key: String) =
        pagingRepository.retryRevalidation(login, key)

    init {
        restoreOptions()
        viewModelScope.launch {
            auth.session.collectLatest { session ->
                jobs.values.forEach(Job::cancel)
                jobs.clear()
                automaticReads.clear()
                cardObservers.values.forEach(Job::cancel)
                cardObservers.clear()
                mutableCards.clear()
                visible = emptyMap()
                applying = null
                remoteFilters.value = null
                filterPhase.value = WritePhase.IDLE
                coordinator = null
                coordinatorAccount = null
                account = session?.login
                mutableSession.value = session
                if (session == null) return@collectLatest
                bind(session)
                kotlinx.coroutines.coroutineScope {
                    launch {
                        dashboard.effectiveFilters(session.login).collect { values ->
                            remoteFilters.value = values?.let {
                                FeedFilter.entries.filterTo(linkedSetOf()) { filter ->
                                    filter.serverGroup in it
                                }
                            }
                        }
                    }
                    launch {
                        dashboard.filterCommand(session.login).collect { command ->
                            filterPhase.value = command.phase
                            if (command.phase == WritePhase.IDLE && command.result != null)
                                applying?.let { choice ->
                                    applying = null
                                    val same = mutableOptions.value == choice
                                    saveOptions(choice)
                                    if (same)
                                        pagingRepository.invalidate(
                                            session.login,
                                            feedPagingKey(choice.filters, choice.sort),
                                        )
                                }
                        }
                    }
                    // 切换账号后账号的服务器筛选仍是旧状态；本地选择与它不一致时同步一次，
                    // 否则请求会带着本地筛选却拿到服务器默认分类（筛选状态永远不会自愈）。
                    launch {
                        val options = mutableOptions.filterNotNull().first()
                        val remote = remoteFilters.filterNotNull().first()
                        if (account == session.login && remote != options.filters)
                            applyOptions(options)
                    }
                }
            }
        }
    }

    private fun bind(session: AuthSession) {
        account = session.login
        coordinator = dashboard.mutations(session.login)
        coordinatorAccount = session.login
    }

    fun restoreOptions() {
        settingsFailed.value = false
        viewModelScope.launch {
            try {
                mutableOptions.value =
                    withContext(Dispatchers.IO) { FeedOptions(loadFilters(), loadSort()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                settingsFailed.value = true
            }
        }
    }

    fun applyOptions(options: FeedOptions) {
        val login = account ?: return
        if (
            options.filters.isEmpty() ||
                options.filters == remoteFilters.value ||
                (options.filters == mutableOptions.value?.filters && remoteFilters.value == null)
        ) {
            saveOptions(options)
            return
        }
        if (filterPhase.value == WritePhase.PENDING) return
        applying = options
        if (!dashboard.applyFilters(login, options.filters.mapTo(linkedSetOf()) { it.serverGroup }))
            applying = null
    }

    private fun saveOptions(options: FeedOptions) {
        val current = mutableOptions.value ?: return
        if (options == current) return
        mutableOptions.value = options
        val previous = settingsWrite
        settingsWrite = viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) {
                if (options.filters != current.filters) {
                    settings.exploreFeedFilters.set(
                        options.filters.mapTo(linkedSetOf()) { it.name },
                    )
                }
                if (options.sort != current.sort) settings.exploreFeedSort.set(options.sort.name)
            }
        }
    }

    private fun loadFilters(): Set<FeedFilter> {
        val stored = settings.exploreFeedFilters.state.value
        return FeedFilter.entries.filterTo(linkedSetOf()) { it.name in stored }
    }

    private fun loadSort(): FeedSort {
        val stored = settings.exploreFeedSort.state.value
        return FeedSort.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            ?: FeedSort.Newest
    }

    fun setVisible(events: List<FeedEventDto>) {
        val next = events.mapNotNull { event -> event.cardKey()?.let { it to event } }.toMap()
        for (key in visible.keys - next.keys) {
            cardObservers.remove(key)?.cancel()
            val read = automaticReads.remove(key) ?: continue
            if (jobs[key] === read) {
                jobs.remove(key)
                read.cancel()
                mutableCards[key]?.let { it.value = it.value.copy(busy = false) }
            }
        }
        for ((key, event) in next) {
            if (event != visible[key]) load(event)
            account?.let { ensureSlotObserver(it, key, event) }
        }
        visible = next
    }

    fun retry(event: FeedEventDto) = load(event, retry = true)

    // Read memory only. The row should not wait for the debounced viewport fetch to show cached
    // fields.
    fun initialDisplayCard(event: FeedEventDto): FeedCardState? =
        account?.let { cachedCard(it, event) } ?: event.initialCard()

    private fun load(event: FeedEventDto, retry: Boolean = false) {
        val key = event.cardKey() ?: return
        val account = account ?: return
        if (jobs[key]?.isActive == true) return
        val existing = mutableCards[key]?.value
        val current =
            if (
                existing != null &&
                    (retry || (event.viewerSelected == null && existing.selected != null))
            )
                projectCardWithSlot(account, key, existing)
            else cachedCard(account, event) ?: existing
        val display = current ?: event.initialCard()
        if (
            !retry &&
                current != null &&
                (current.selected != null ||
                    event.workItem() != null ||
                    event.payload.release != null)
        ) {
            if (current != (existing ?: event.initialCard())) update(key, current)
            ensureSlotObserver(account, key, event)
            return
        }
        val coord = coordinator?.takeIf { coordinatorAccount == account }
        val mutationKey = mutationKeyFor(key)
        val stamp = if (coord != null && mutationKey != null) coord.beginRead(mutationKey) else null
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                update(key, (display ?: FeedCardState()).copy(busy = true))
                try {
                    val refreshed = cardRequests.withPermit {
                        dashboard.refreshCard(account, event)
                    }
                    currentCoroutineContext().ensureActive()
                    if (this@FeedViewModel.account != account) return@launch
                    val card =
                        refreshed.initialCard() ?: throw IllegalStateException("Missing feed card")
                    val confirmedSelected = card.selected
                    if (
                        coord != null &&
                            mutationKey != null &&
                            stamp != null &&
                            confirmedSelected != null
                    ) {
                        coord.publishConfirmed(mutationKey, stamp, confirmedSelected)
                    }
                    val projected = projectCardWithSlot(account, key, card)
                    update(key, projected)
                    ensureSlotObserver(account, key, refreshed)
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    update(key, (display ?: FeedCardState()).copy(busy = false))
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
        val activeAccount = account ?: return
        val state = mutableCards[key]?.value ?: cachedCard(activeAccount, event) ?: return
        val mutationKey = mutationKeyFor(key) ?: return
        val coord = coordinator?.takeIf { coordinatorAccount == activeAccount } ?: return
        val slot = coord.current(mutationKey)
        if (slot.phase == WritePhase.UNCERTAIN) {
            coord.reconcile(mutationKey) {
                requireNotNull(dashboard.refreshCard(activeAccount, event).viewerSelected)
            }
            return
        }
        val target = !(slot.visible ?: state.selected ?: return retry(event))
        ensureSlotObserver(activeAccount, key, event)
        val userLogin = key.takeIf { it.startsWith("user:") }?.removePrefix("user:")
        val tags =
            if (userLogin != null) {
                setOf(
                    CacheTag(DataDomain.Profile, userLogin.lowercase()),
                    relationshipCacheTag(userLogin),
                )
            } else {
                val (owner, name) = repositoryParts(key)
                dashboard.trackStar(activeAccount, "$owner/$name", state.repository)
                setOf(
                    repositoryCacheTag(owner, name),
                    relationshipCacheTag("${owner.lowercase()}/${name.lowercase()}"),
                    relationshipCacheTag("viewer:starred"),
                )
            }
        val accepted =
            coord.submit(
                mutationKey,
                confirmed = state.selected,
                desired = target,
                tags = tags,
                send = { nextSelected ->
                    dashboard.setSelected(
                        activeAccount,
                        event.copy(subjectId = state.subjectId),
                        nextSelected,
                    )
                },
                readBack = {
                    val refreshed = dashboard.refreshCard(activeAccount, event)
                    requireNotNull(refreshed.viewerSelected)
                },
            )
        if (!accepted) {
            val current = mutableCards[key]?.value ?: state
            update(key, current.copy(actionError = CurrentAppStrings.current.Common.MutationUncertain))
        }
    }

    private fun ensureSlotObserver(activeAccount: String, key: String, event: FeedEventDto) {
        val mutationKey = mutationKeyFor(key) ?: return
        val coord = coordinator?.takeIf { coordinatorAccount == activeAccount } ?: return
        if (cardObservers[key]?.isActive == true) return
        cardObservers[key] = viewModelScope.launch {
            launch {
                val stored =
                    if (key.startsWith("user:"))
                        followingResource(activeAccount, mutationKey.target)
                    else
                        repositoryParts(key).let { (owner, name) ->
                            starredResource(activeAccount, owner, name)
                        }
                stored.restore()?.let { coord.seedFromCache(mutationKey, it) }
            }
            coord.observe(mutationKey).collect { write ->
                if (account != activeAccount) return@collect
                val current =
                    mutableCards[key]?.value ?: cachedCard(activeAccount, event) ?: return@collect
                val projected =
                    applyWriteState(
                        activeAccount,
                        key,
                        current,
                        write.visible,
                        write.phase,
                        write.message,
                    )
                update(key, projected)
            }
        }
    }

    private fun projectCardWithSlot(
        activeAccount: String,
        key: String,
        base: FeedCardState,
    ): FeedCardState {
        val mutationKey = mutationKeyFor(key) ?: return base
        val coord = coordinator?.takeIf { coordinatorAccount == activeAccount } ?: return base
        val write = coord.current(mutationKey)
        return applyWriteState(
            activeAccount,
            key,
            base,
            write.visible,
            write.phase,
            write.message,
        )
    }

    private fun applyWriteState(
        activeAccount: String,
        key: String,
        current: FeedCardState,
        visible: Boolean?,
        phase: WritePhase,
        message: String?,
    ): FeedCardState {
        val nextSelected = visible ?: current.selected
        val oldSelected = current.selected
        val isRepo = key.startsWith("repo:")
        val currentRepo = current.repository
        val nextRepo =
            if (
                isRepo &&
                    currentRepo != null &&
                    nextSelected != null &&
                    oldSelected != null &&
                    nextSelected != oldSelected
            ) {
                val delta = if (nextSelected) 1 else -1
                val baseCount =
                    currentRepo.stargazersCount
                        ?: repositoryParts(key).let { (owner, name) ->
                            repositoryResource(activeAccount, owner, name).cached?.stargazersCount
                        }
                if (baseCount != null)
                    currentRepo.copy(stargazersCount = (baseCount + delta).coerceAtLeast(0))
                else currentRepo
            } else currentRepo
        val actionError =
            when (phase) {
                WritePhase.UNCERTAIN -> CurrentAppStrings.current.Common.MutationUncertain
                WritePhase.REJECTED -> message ?: CurrentAppStrings.current.Common.MutationNotSubmitted
                WritePhase.IDLE,
                WritePhase.PENDING -> null
            }
        return current.copy(
            repository = nextRepo,
            selected = nextSelected,
            phase = phase,
            actionError = actionError,
        )
    }

    private fun mutationKeyFor(key: String): MutationKey<Boolean>? =
        when {
            key.startsWith("user:") ->
                MutationKey(
                    MutationKind.FOLLOW,
                    key.removePrefix("user:").lowercase(java.util.Locale.ROOT),
                    Boolean.serializer(),
                )
            key.startsWith("repo:") ->
                repositoryParts(key).let { (owner, name) ->
                    MutationKey(
                        MutationKind.STAR,
                        "${owner.lowercase(java.util.Locale.ROOT)}/${name.lowercase(java.util.Locale.ROOT)}",
                        Boolean.serializer(),
                    )
                }
            else -> null
        }

    private fun repositoryResource(account: String, owner: String, name: String) =
        repositoryContent.details(account, owner, name)

    private fun userResource(account: String, login: String) =
        cache.resource(
            account,
            "user:${login.lowercase(java.util.Locale.ROOT)}",
            UserDto.serializer(),
            tags = setOf(CacheTag(DataDomain.Profile, login.lowercase(java.util.Locale.ROOT))),
        )

    private fun starredResource(account: String, owner: String, name: String) =
        cache.resource(
            account,
            "starred-state:${owner.lowercase(java.util.Locale.ROOT)}/${name.lowercase(java.util.Locale.ROOT)}",
            Boolean.serializer(),
            tags = setOf(relationshipCacheTag("${owner.lowercase()}/${name.lowercase()}")),
        )

    private fun followingResource(account: String, login: String) =
        cache.resource(
            account,
            "following-state:${login.lowercase(java.util.Locale.ROOT)}",
            Boolean.serializer(),
            tags = setOf(relationshipCacheTag(login)),
        )

    private fun contributorsResource(account: String, owner: String, name: String) =
        cache.resource(
            account,
            "contributors-count:${owner.lowercase(java.util.Locale.ROOT)}/${name.lowercase(java.util.Locale.ROOT)}",
            Int.serializer(),
            tags = setOf(repositoryCacheTag(owner, name)),
        )

    private fun cachedCard(account: String, event: FeedEventDto): FeedCardState? {
        val key = event.cardKey() ?: return null
        if (key.startsWith("user:")) {
            val login = key.removePrefix("user:")
            val user = userResource(account, login).cached ?: event.payload.target ?: return null
            return projectCardWithSlot(
                account,
                key,
                FeedCardState(
                    subjectId = event.subjectId,
                    user = user,
                    selected = followingResource(account, login).cached ?: event.viewerSelected,
                ),
            )
        }
        val fullName = event.repo?.name ?: return null
        val owner = fullName.substringBefore('/')
        val name = fullName.substringAfter('/')
        val repository =
            event.repositoryDetails
                ?: repositoryResource(account, owner, name).cached
                ?: return null
        return projectCardWithSlot(
            account,
            key,
            FeedCardState(
                repository = repository,
                subjectId = event.subjectId,
                work = event.workItem(),
                selected = starredResource(account, owner, name).cached ?: event.viewerSelected,
                contributorsCount =
                    contributorsResource(account, owner, name).cached?.takeIf { it >= 0 }
                        ?: event.contributorsCount,
            ),
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
