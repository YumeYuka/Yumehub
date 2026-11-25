package moe.yumeyuka.yumehub.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.repository.RepositoryFavoritesLimitException
import moe.yumeyuka.yumehub.data.repository.RepositoryOverview
import moe.yumeyuka.yumehub.data.repository.RepositoryOverviewRepository
import moe.yumeyuka.yumehub.data.repository.updatedDashboardPins
import moe.yumeyuka.yumehub.locale.GithubTxt

internal const val RepositoryDescriptionMaxLength = 350

internal enum class RepositorySheet {
    Description,
    Notifications,
}

@Serializable
internal data class RepoSubscriptionPayload(val value: String, val types: List<String>)

internal data class RepositoryManagementState(
    val sheet: RepositorySheet,
    val description: String = "",
    val originalDescription: String = description,
    val subscription: RepositorySubscriptionSelection? = null,
    val originalSubscription: RepositorySubscriptionSelection? = subscription,
    val pinned: Boolean? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal data class RepositorySubscriptionSelection(val value: String, val types: Set<String>)

internal data class RepositoryOverviewState(
    val overview: RepositoryOverview? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    /** 首页收藏状态；进入页面时按需查询，未查询完成前为 null。 */
    val pinned: Boolean? = null,
    val management: RepositoryManagementState? = null,
    val pendingSubscription: RepositorySubscriptionSelection? = null,
    val starPhase: WritePhase = WritePhase.IDLE,
    val pinPhase: WritePhase = WritePhase.IDLE,
    val subscriptionPhase: WritePhase = WritePhase.IDLE,
    val descriptionPhase: WritePhase = WritePhase.IDLE,
    val descriptionOverride: String? = null,
    val coordinatorReady: Boolean = false,
)

/** 订阅等轻量操作失败后经一次性事件提示，不阻塞浮层交互。 */
internal sealed interface RepositoryOverviewEvent {
    data class Failed(val message: String) : RepositoryOverviewEvent
}

internal class RepositoryOverviewViewModel(
    private val source: RepositoryOverviewRepository,
    private val owner: String,
    private val name: String,
) : ViewModel() {
    val account = source.account
    private val resource = source.resource(account, owner, name)
    private var confirmedOverview: RepositoryOverview? = resource.cached
    private var confirmedPinned: Boolean? = null
    private val mutableState = MutableStateFlow(RepositoryOverviewState(overview = resource.cached))
    val uiState = mutableState.asStateFlow()
    private val mutableEvents = Channel<RepositoryOverviewEvent>(Channel.BUFFERED)
    val events = mutableEvents.receiveAsFlow()
    private var pinnedJob: Job? = null
    private var sheetJob: Job? = null
    private var coordinator: MutationCoordinator? = null
    private val repoSlug = "${owner.lowercase()}/${name.lowercase()}"
    private val starKey = MutationKey(MutationKind.STAR, repoSlug, Boolean.serializer())
    private val pinsKey =
        MutationKey(MutationKind.DASHBOARD_PINS, "viewer", ListSerializer(String.serializer()))
    private val subscriptionKey =
        MutationKey(MutationKind.REPO_SUBSCRIPTION, repoSlug, RepoSubscriptionPayload.serializer())
    private val descriptionKey =
        MutationKey(MutationKind.REPO_DESCRIPTION, repoSlug, String.serializer())

    init {
        viewModelScope.launch {
            resource.state.collect {
                confirmedOverview = it.data
                mutableState.value =
                    mutableState.value.copy(
                        overview = projectOverview(it.data),
                        loading = it.loading,
                        error = it.error?.let { GithubTxt.current.Repository.OperationFailed },
                    )
            }
        }
        viewModelScope.launch {
            val loaded =
                try {
                    source.mutations(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@launch
                }
            if (!active()) return@launch
            coordinator = loaded
            mutableState.value = mutableState.value.copy(coordinatorReady = true)
            launch {
                loaded.observe(starKey).collect { write ->
                    if (!active()) return@collect
                    mutableState.value =
                        mutableState.value.copy(
                            overview = projectOverview(confirmedOverview),
                            starPhase = write.phase,
                        )
                    if (write.phase == WritePhase.REJECTED) {
                        mutableEvents.trySend(
                            RepositoryOverviewEvent.Failed(
                                write.message ?: GithubTxt.current.Repository.OperationFailed,
                            ),
                        )
                    }
                }
            }
            launch {
                loaded.observe(pinsKey).collect { write ->
                    if (!active()) return@collect
                    val id = confirmedOverview?.id
                    val ids = write.desired ?: write.confirmed
                    val projectedPinned =
                        if (id != null && ids != null) id in ids else confirmedPinned
                    mutableState.value =
                        mutableState.value.copy(
                            pinned = projectedPinned,
                            pinPhase = write.phase,
                        )
                    if (write.phase == WritePhase.REJECTED) {
                        mutableEvents.trySend(
                            RepositoryOverviewEvent.Failed(
                                write.message ?: GithubTxt.current.Repository.OperationFailed,
                            ),
                        )
                    }
                }
            }
            launch {
                loaded.observe(subscriptionKey).collect { write ->
                    if (!active()) return@collect
                    val selection =
                        (write.desired ?: write.confirmed)?.let {
                            RepositorySubscriptionSelection(it.value, it.types.toSet())
                        }
                    mutableState.value =
                        mutableState.value.copy(
                            overview = projectOverview(confirmedOverview),
                            pendingSubscription =
                                selection.takeIf { write.phase == WritePhase.PENDING },
                            subscriptionPhase = write.phase,
                        )
                    if (write.phase == WritePhase.REJECTED) {
                        mutableEvents.trySend(
                            RepositoryOverviewEvent.Failed(
                                write.message ?: GithubTxt.current.Repository.OperationFailed,
                            ),
                        )
                    }
                }
            }
            launch {
                loaded.observe(descriptionKey).collect { write ->
                    if (!active()) return@collect
                    mutableState.value =
                        mutableState.value.copy(
                            descriptionPhase = write.phase,
                            descriptionOverride = write.desired ?: write.confirmed,
                        )
                    if (write.phase == WritePhase.REJECTED) {
                        mutableEvents.trySend(
                            RepositoryOverviewEvent.Failed(
                                write.message ?: GithubTxt.current.Repository.OperationFailed,
                            ),
                        )
                    }
                }
            }
        }
        refresh()
    }

    private fun projectOverview(base: RepositoryOverview?): RepositoryOverview? {
        base ?: return null
        val coord = coordinator ?: return base
        val starWrite = coord.current(starKey)
        val subWrite = coord.current(subscriptionKey)
        val desiredStar = starWrite.desired ?: starWrite.confirmed
        val baseStarred = starWrite.confirmed ?: base.starred
        val starred = desiredStar ?: base.starred
        val stars =
            if (desiredStar != null) {
                (base.stars + (if (desiredStar) 1 else 0) - (if (baseStarred) 1 else 0))
                    .coerceAtLeast(0)
            } else base.stars
        val subPayload = subWrite.desired ?: subWrite.confirmed
        return base.copy(
            starred = starred,
            stars = stars,
            subscription = subPayload?.value ?: base.subscription,
            subscriptionTypes = subPayload?.types?.toSet() ?: base.subscriptionTypes,
        )
    }

    private fun active() =
        source.account.equals(account, true) && source.resource(account, owner, name) === resource

    fun refresh(force: Boolean = false) {
        if (!active()) return
        val coord = coordinator
        val stamp = coord?.beginRead(starKey)
        resource.refresh(force) {
            source.load(account, owner, name).also { loaded ->
                if (coord != null && stamp != null) {
                    coord.publishConfirmed(starKey, stamp, loaded.starred)
                }
            }
        }
    }

    /** 读取首页 pin 状态；已获取或进行中时不重复请求。 */
    fun loadPinned() {
        if (
            !active() ||
                mutableState.value.pinned != null ||
                pinnedJob?.isActive == true
        ) return
        val overview = confirmedOverview ?: mutableState.value.overview ?: return
        pinnedJob = viewModelScope.launch {
            try {
                val coord = coordinator
                val stamp = coord?.beginRead(pinsKey)
                val ids = source.dashboardFavoriteIds(account)
                if (coord != null && stamp != null) {
                    coord.publishConfirmed(pinsKey, stamp, ids)
                }
                val pinned = ids.contains(overview.id)
                confirmedPinned = pinned
                if (active()) mutableState.value = mutableState.value.copy(pinned = pinned)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 查询失败时保持未知，菜单不显示固定项，不阻塞其他入口。
            }
        }
    }

    fun open(sheet: RepositorySheet, repository: RepositoryDto) {
        if (!active()) return
        val overview = mutableState.value.overview ?: return
        if (sheet == RepositorySheet.Description && !overview.canEditDescription) return
        sheetJob?.cancel()
        val currentDescription =
            mutableState.value.descriptionOverride ?: repository.description.orEmpty()
        mutableState.value =
            mutableState.value.copy(
                management =
                    RepositoryManagementState(
                        sheet = sheet,
                        description = currentDescription,
                        originalDescription = currentDescription,
                        subscription =
                            if (sheet == RepositorySheet.Notifications)
                                mutableState.value.pendingSubscription
                                    ?: overview.subscription?.let { value ->
                                        RepositorySubscriptionSelection(
                                            value,
                                            overview.subscriptionTypes.toSet(),
                                        )
                                    }
                            else null,
                    ),
            )
    }

    private fun updateSheet(
        sheet: RepositorySheet,
        update: (RepositoryManagementState) -> RepositoryManagementState,
    ) {
        if (!active()) return
        val editor = mutableState.value.management?.takeIf { it.sheet == sheet } ?: return
        mutableState.value = mutableState.value.copy(management = update(editor))
    }

    fun dismiss() {
        sheetJob?.cancel()
        mutableState.value = mutableState.value.copy(management = null)
    }

    fun description(value: String) {
        if (value.length <= RepositoryDescriptionMaxLength)
            updateSheet(RepositorySheet.Description) { it.copy(description = value, error = null) }
    }

    fun changeSubscription(value: String, types: Set<String>) {
        updateSheet(RepositorySheet.Notifications) {
            it.copy(subscription = RepositorySubscriptionSelection(value, types.toSet()))
        }
    }

    fun star() {
        val state = mutableState.value.overview ?: return
        val coord = coordinator ?: return
        val desired = !state.starred
        val accepted =
            coord.submit(
                starKey,
                confirmed = confirmedOverview?.starred ?: state.starred,
                desired = desired,
                tags =
                    setOf(
                        repositoryCacheTag(owner, name),
                        relationshipCacheTag(repoSlug),
                        CacheTag(DataDomain.Repositories, null),
                    ),
                send = { value -> source.setStarred(account, owner, name, value).starred },
                readBack = { source.load(account, owner, name).starred },
            )
        if (!accepted && active()) {
            mutableEvents.trySend(
                RepositoryOverviewEvent.Failed(GithubTxt.current.Common.MutationUncertain),
            )
        }
    }

    fun pin(repository: RepositoryDto, selected: Boolean) {
        val state = confirmedOverview ?: mutableState.value.overview ?: return
        val coord = coordinator ?: return
        viewModelScope.launch {
            val currentIds =
                coord.current(pinsKey).let { it.desired ?: it.confirmed }
                    ?: try {
                        val stamp = coord.beginRead(pinsKey)
                        source.dashboardFavoriteIds(account).also {
                            coord.publishConfirmed(pinsKey, stamp, it)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (active()) {
                            mutableEvents.trySend(
                                RepositoryOverviewEvent.Failed(
                                    GithubTxt.current.Repository.OperationFailed,
                                ),
                            )
                        }
                        return@launch
                    }
            val nextIds =
                try {
                    updatedDashboardPins(currentIds, state.id, selected)
                } catch (_: RepositoryFavoritesLimitException) {
                    if (active()) {
                        mutableEvents.trySend(
                            RepositoryOverviewEvent.Failed(
                                GithubTxt.current.Repository.FavoriteLimitReached,
                            ),
                        )
                    }
                    return@launch
                }
            val accepted =
                coord.submit(
                    pinsKey,
                    confirmed = currentIds,
                    desired = nextIds,
                    tags = setOf(CacheTag(DataDomain.Repositories, null)),
                    send = { ids ->
                        source.writePinIds(
                            account,
                            ids,
                            listOf(repository.copy(nodeId = state.id)),
                        )
                    },
                    readBack = { source.dashboardFavoriteIds(account) },
                )
            if (!accepted && active()) {
                mutableEvents.trySend(
                    RepositoryOverviewEvent.Failed(GithubTxt.current.Common.MutationUncertain),
                )
            }
        }
    }

    private fun saveSubscription(selection: RepositorySubscriptionSelection) {
        if (!active() || mutableState.value.management?.sheet != RepositorySheet.Notifications)
            return
        val coord = coordinator ?: return
        sheetJob?.cancel()
        val current = confirmedOverview ?: mutableState.value.overview
        val confirmedPayload =
            current?.subscription?.let {
                RepoSubscriptionPayload(it, current.subscriptionTypes.sorted())
            }
        val desiredPayload = RepoSubscriptionPayload(selection.value, selection.types.sorted())
        mutableState.value =
            mutableState.value.copy(management = null, pendingSubscription = selection)
        val accepted =
            coord.submit(
                subscriptionKey,
                confirmed = confirmedPayload,
                desired = desiredPayload,
                tags =
                    setOf(
                        repositoryCacheTag(owner, name),
                        CacheTag(DataDomain.Work, null),
                        CacheTag(DataDomain.Notifications, null),
                    ),
                send = { payload ->
                    source.setSubscription(
                        account,
                        owner,
                        name,
                        payload.value,
                        payload.types.toSet(),
                    )
                    payload
                },
                readBack = {
                    val reloaded = source.load(account, owner, name)
                    RepoSubscriptionPayload(
                        reloaded.subscription ?: "UNSUBSCRIBED",
                        reloaded.subscriptionTypes.sorted(),
                    )
                },
            )
        if (!accepted && active()) {
            mutableEvents.trySend(
                RepositoryOverviewEvent.Failed(GithubTxt.current.Common.MutationUncertain),
            )
        }
    }

    fun save() {
        if (!active()) return
        val editor = mutableState.value.management ?: return
        when (editor.sheet) {
            RepositorySheet.Description -> {
                val coord = coordinator ?: return
                val nextDescription = editor.description.trim()
                mutableState.value = mutableState.value.copy(management = null)
                val accepted =
                    coord.submit(
                        descriptionKey,
                        confirmed = editor.originalDescription,
                        desired = nextDescription,
                        tags = setOf(repositoryCacheTag(owner, name)),
                        send = { text ->
                            source.saveDescription(account, owner, name, text)
                            text
                        },
                        readBack = { editor.originalDescription },
                    )
                if (!accepted && active()) {
                    mutableEvents.trySend(
                        RepositoryOverviewEvent.Failed(GithubTxt.current.Common.MutationUncertain),
                    )
                }
            }
            RepositorySheet.Notifications -> {
                val selection = editor.subscription
                if (selection == null || selection == editor.originalSubscription) dismiss()
                else saveSubscription(selection)
            }
        }
    }

    fun reconcileStar() {
        coordinator?.reconcile(starKey) { source.load(account, owner, name).starred }
    }
}
