package moe.yumeyuka.yumehub.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.NotificationThreadAction as ThreadActionKind
import moe.yumeyuka.yumehub.data.repository.NotificationThreadState
import moe.yumeyuka.yumehub.data.repository.NotificationsQuery
import moe.yumeyuka.yumehub.data.repository.NotificationsRepository
import moe.yumeyuka.yumehub.data.repository.NotificationsSnapshot
import moe.yumeyuka.yumehub.locale.GithubTxt
import retrofit2.HttpException

enum class NotificationFilter(val includeRead: Boolean, val done: Boolean) {
    Inbox(true, false),
    Unread(false, false),
    Done(true, true),
}

enum class NotificationReasonFilter(val apiValue: String?) {
    All(null),
    Mention("mention"),
    TeamMention("team_mention"),
    ReviewRequested("review_requested"),
    Assigned("assign"),
    Comment("comment"),
    Author("author"),
    Subscribed("subscribed"),
    StateChange("state_change"),
    CiActivity("ci_activity"),
    ApprovalRequested("approval_requested"),
    SecurityAlert("security_alert"),
    Invitation("invitation"),
    Manual("manual"),
}

data class NotificationsUiState(
    val notifications: List<NotificationDto> = emptyList(),
    val previews: Map<NotificationDto, NotificationPreviewDto> = emptyMap(),
    val filter: NotificationFilter = NotificationFilter.Inbox,
    val reasonFilter: NotificationReasonFilter = NotificationReasonFilter.All,
    val repository: String? = null,
    val repositories: List<String> = emptyList(),
    val hasCachedContent: Boolean = false,
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val hasPrevious: Boolean = false,
    val loadError: String? = null,
    val appendError: String? = null,
    val isProcessing: Boolean = false,
    val actionError: String? = null,
    val isSelecting: Boolean = false,
    val selectedThreadIds: Set<String> = emptySet(),
    val pendingThreadIds: Set<String> = emptySet(),
    val uncertainThreadIds: Set<String> = emptySet(),
) {
    val visibleNotifications: List<NotificationDto>
        get() =
            reasonFilter.apiValue?.let { reason -> notifications.filter { it.reason == reason } }
                ?: notifications
}

internal val NotificationDto.threadId: String?
    get() = id?.takeIf(String::isNotBlank)

class NotificationsViewModel(private val repository: NotificationsRepository) : ViewModel() {
    private val account = repository.account
    private val mutableUiState = MutableStateFlow(NotificationsUiState())
    private var resource = repository.resource(account, mutableUiState.value.query())
    private var listJob: Job? = null
    private var observationJob: Job? = null
    private var appending = false
    private var failedWindow: (() -> Unit)? = null
    private var listGeneration = 0L
    private var loadedThroughPage = 0
    private var loadedFromPage = 1
    private var nextPage: Int? = null
    private var pagingPaused = false
    private var failedAction: ThreadAction? = null
    private val coordinator = repository.mutations(account)
    private val threadObservers = mutableMapOf<String, Job>()
    private val overlayStates = mutableMapOf<String, NotificationThreadState>()
    private var pendingUncertainTargets: Set<String> = emptySet()

    val uiState: StateFlow<NotificationsUiState> = mutableUiState.asStateFlow()

    init {
        // 首次进入由 GithubRefreshEffect 统一触发，避免 init 与首次激活各请求一次第一页。
        observeQuery(refresh = false)
        viewModelScope.launch {
            val coord = coordinator
            launch {
                coord.pending.collect { summaries ->
                    pendingUncertainTargets =
                        summaries
                            .filter { it.kind == MutationKind.NOTIFICATION }
                            .mapTo(mutableSetOf()) { it.target }
                    pendingUncertainTargets.forEach { ensureThreadObserver(coord, it) }
                    applySnapshot(resource.state.value)
                }
            }
        }
        viewModelScope.launch {
            repository.changes(account).versions
                .map { it[DataDomain.Work] }
                .distinctUntilChanged()
                .collect {
                    val state = mutableUiState.value
                    if (state.previews.isNotEmpty()) {
                        mutableUiState.value = state.copy(
                            previews = state.previews.mapValues { (notification, preview) ->
                                repository.confirmedPreview(account, notification, preview)
                            },
                        )
                    }
                }
        }
    }

    private fun threadKey(threadId: String) =
        MutationKey(MutationKind.NOTIFICATION, threadId, NotificationThreadState.serializer())

    private fun ensureThreadObserver(coord: MutationCoordinator, threadId: String) {
        if (threadObservers[threadId]?.isActive == true) return
        threadObservers[threadId] =
            viewModelScope.launch {
                coord.observe(threadKey(threadId)).collect { write ->
                    val projected = write.desired.takeIf { write.phase == WritePhase.PENDING }
                        ?: write.confirmed.takeIf { write.phase == WritePhase.UNCERTAIN }
                    if (projected != null) overlayStates[threadId] = projected else overlayStates.remove(threadId)
                    if (write.phase == WritePhase.UNCERTAIN) {
                        pendingUncertainTargets = pendingUncertainTargets + threadId
                    } else if (write.phase == WritePhase.IDLE || write.phase == WritePhase.REJECTED) {
                        pendingUncertainTargets = pendingUncertainTargets - threadId
                    }
                    applySnapshot(resource.state.value)
                }
            }
    }

    fun revalidate() {
        failedWindow = null
        val state = mutableUiState.value
        repository.refresh(account, state.query())
    }

    fun reconcileUncertain() {
        revalidate()
        pendingUncertainTargets.forEach { id ->
            coordinator.reconcile(threadKey(id)) { repository.readThreadState(account, id) }
        }
    }

    fun acknowledgeUncertain() {
        val coord = coordinator
        coord.pending.value
            .filter { it.kind == MutationKind.NOTIFICATION }
            .forEach { coord.acknowledge(it.operationId) }
    }

    fun loadMore() {
        val state = mutableUiState.value
        if (
            state.isRefreshing ||
                state.isLoadingMore ||
                listJob?.isActive == true
        )
            return
        if (pagingPaused) { revalidate(); return }
        val next = nextPage ?: return
        if (loadedThroughPage - loadedFromPage >= 9) {
            // 保持最近 10 页窗口；只加载下一页再整体替换会把已显示的上下文塌缩成单页。
            loadPages((next - 9).coerceAtLeast(1), next, replace = true)
        } else {
            loadPages(next)
        }
    }

    fun loadPrevious() {
        val state = mutableUiState.value
        if (
            state.isRefreshing ||
                state.isLoadingMore ||
                listJob?.isActive == true ||
                loadedFromPage <= 1
        )
            return
        if (pagingPaused) { revalidate(); return }
        val start = (loadedFromPage - 10).coerceAtLeast(1)
        loadPages(start, loadedFromPage - 1, replace = true)
    }

    fun retryWindow() {
        failedWindow?.invoke() ?: revalidate()
    }

    fun selectFilter(filter: NotificationFilter) {
        val state = mutableUiState.value
        if (state.filter == filter) return
        observeQuery(state.clearSelectionRetry().copy(filter = filter, isSelecting = false, selectedThreadIds = emptySet()))
    }

    fun selectRepository(repository: String?) {
        val state = mutableUiState.value
        if (
                state.repository == repository ||
                repository != null && repository !in state.repositories
        ) {
            return
        }
        observeQuery(state.clearSelectionRetry().copy(repository = repository, isSelecting = false, selectedThreadIds = emptySet()))
    }

    fun selectReason(reason: NotificationReasonFilter) {
        val state = mutableUiState.value
        if (state.reasonFilter == reason) return
        mutableUiState.value =
            state
                .clearSelectionRetry()
                .copy(reasonFilter = reason, isSelecting = false, selectedThreadIds = emptySet())
    }

    fun startSelection() {
        val state = mutableUiState.value
        if (state.isProcessing || state.filter.done || state.isSelecting) return
        mutableUiState.value = state.copy(isSelecting = true)
    }

    fun endSelection() {
        val state = mutableUiState.value
        if (!state.isSelecting) return
        mutableUiState.value =
            state.clearSelectionRetry().copy(isSelecting = false, selectedThreadIds = emptySet())
    }

    fun toggleSelection(threadId: String) {
        val state = mutableUiState.value
        if (
            state.isProcessing ||
                !state.isSelecting ||
                state.visibleNotifications.none { it.threadId == threadId }
        )
            return
        val selected = state.selectedThreadIds
        mutableUiState.value =
            state
                .clearSelectionRetry()
                .copy(
                    selectedThreadIds =
                        if (threadId in selected) selected - threadId else selected + threadId,
                )
    }

    /** 点开即后台同步已读，不把整页锁住，也不等待写请求后才导航。 */
    fun markOpened(notification: NotificationDto) {
        repository.rememberOpenedWork(account, notification)
        val threadId = notification.threadId ?: return
        if (notification.unread && !uiState.value.isSelecting) process(ThreadAction(setOf(threadId), ThreadActionKind.MarkRead))
    }

    fun markSelectedRead() = processSelection(ThreadActionKind.MarkRead)

    fun setUnread(threadId: String, unread: Boolean) {
        if (mutableUiState.value.isSelecting) return
        val action = if (unread) ThreadActionKind.MarkUnread else ThreadActionKind.MarkRead
        process(ThreadAction(setOf(threadId), action))
    }

    fun setDone(threadId: String, done: Boolean) {
        if (mutableUiState.value.isSelecting) return
        val action = if (done) ThreadActionKind.MarkDone else ThreadActionKind.MarkUndone
        process(ThreadAction(setOf(threadId), action))
    }

    fun markSelectedDone(threadIds: Set<String>) {
        if (!mutableUiState.value.isSelecting) return
        process(ThreadAction(threadIds.toSet(), ThreadActionKind.MarkDone, fromSelection = true))
    }

    fun retryAction() {
        failedAction?.let(::process)
    }

    private fun processSelection(kind: ThreadActionKind) {
        val state = mutableUiState.value
        if (!state.isSelecting) return
        process(ThreadAction(state.selectedThreadIds, kind, fromSelection = true))
    }

    private fun NotificationsUiState.clearSelectionRetry(): NotificationsUiState {
        if (failedAction?.fromSelection != true) return this
        failedAction = null
        return copy(actionError = null)
    }

    private fun NotificationsUiState.query() =
        NotificationsQuery(filter.includeRead, filter.done, repository)

    private fun observeQuery(selection: NotificationsUiState = mutableUiState.value, refresh: Boolean = true) {
        listGeneration++
        listJob?.cancel()
        observationJob?.cancel()
        appending = false
        failedWindow = null
        val query = selection.query()
        val observed = repository.resource(account, query)
        resource = observed
        applySnapshot(observed.state.value, selection)
        observationJob = viewModelScope.launch {
            observed.state.collect { if (resource === observed) applySnapshot(it) }
        }
        if (refresh && (observed.state.value.data == null || observed.state.value.updatedAt == 0L)) {
            repository.refresh(account, query)
        }
    }

    private fun applySnapshot(cached: CachedDataState<NotificationsSnapshot>, current: NotificationsUiState = mutableUiState.value) {
        val snapshot = cached.data
        loadedFromPage = snapshot?.loadedFromPage ?: 1
        loadedThroughPage = snapshot?.loadedThroughPage ?: 0
        nextPage = snapshot?.takeIf { it.nextCursor != null }?.let { it.loadedThroughPage + 1 }
        // 失效的磁盘窗口必须先重读，不能继续使用操作前的游标。
        pagingPaused = snapshot?.pagingPaused == true || snapshot != null && cached.updatedAt == 0L
        val baseNotifications = snapshot?.notifications.orEmpty()
        val notifications =
            baseNotifications.mapNotNull { notification ->
                val threadId = notification.threadId ?: return@mapNotNull notification
                val overlay = overlayStates[threadId] ?: return@mapNotNull notification
                val matchesDone = overlay.done == current.filter.done
                val matchesRead = current.filter.includeRead || overlay.unread
                if (!matchesDone || !matchesRead) null
                else notification.copy(unread = overlay.unread)
            }
        val selected =
            current.selectedThreadIds.intersect(baseNotifications.mapNotNullTo(mutableSetOf()) { it.threadId } + current.pendingThreadIds)
        val stateWithValidRetry =
            if (selected == current.selectedThreadIds) current else current.clearSelectionRetry()
        val error = (cached.error as? Exception)?.messageForUser()
        val previews =
            notifications
                .mapNotNull { notification ->
                    notification.preview?.let {
                        notification to repository.confirmedPreview(account, notification, it)
                    }
                }
                .toMap()
        mutableUiState.value =
            stateWithValidRetry.copy(
                notifications = notifications,
                previews = previews,
                repositories = snapshot?.repositories.orEmpty(),
                hasCachedContent = snapshot != null,
                isRefreshing = cached.loading && !appending || snapshot == null && error == null,
                isLoadingMore = cached.loading && appending,
                hasMore = nextPage != null,
                hasPrevious = loadedFromPage > 1,
                loadError = error.takeUnless { appending || failedWindow != null },
                appendError = if (failedWindow != null) current.appendError else error.takeIf { appending },
                selectedThreadIds = selected,
                uncertainThreadIds = pendingUncertainTargets.toSet(),
            )
    }

    private fun loadPages(
        fromPage: Int,
        throughPage: Int = fromPage,
        replace: Boolean = false,
    ) {
        val generation = ++listGeneration
        listJob?.cancel()
        val state = mutableUiState.value
        failedWindow = null
        appending = true
        mutableUiState.value =
            state.copy(
                isRefreshing = false,
                isLoadingMore = true,
                loadError = null,
                appendError = null,
            )
        listJob = viewModelScope.launch {
            try {
                repository.loadPages(account, state.query(), fromPage, throughPage, replace)
                if (generation == listGeneration) {
                    appending = false
                    applySnapshot(resource.state.value)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != listGeneration) return@launch
                appending = false
                failedWindow = { loadPages(fromPage, throughPage, replace) }
                mutableUiState.value =
                    mutableUiState.value.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        loadError = null,
                        appendError = error.messageForUser(),
                    )
            }
        }
    }

    private enum class BatchOutcome { Confirmed, Rejected, Uncertain, NotSent }

    private fun process(action: ThreadAction) {
        val state = mutableUiState.value
        if (
            (action.fromSelection && state.isProcessing) ||
                action.threadIds.isEmpty() ||
                action.threadIds.any(String::isBlank)
        )
            return
        val ids = action.threadIds - state.pendingThreadIds - state.uncertainThreadIds
        if (ids.isEmpty()) return
        val records = state.notifications.associateBy { it.threadId }
        val submitted = linkedMapOf<String, Boolean>()
        failedAction = null
        mutableUiState.value = state.copy(isProcessing = state.isProcessing || action.fromSelection,
            pendingThreadIds = state.pendingThreadIds + ids, actionError = null)
        val coord = coordinator
        for (id in ids) {
            val row = records[id] ?: continue
            val confirmed = NotificationThreadState(row.unread, state.filter.done)
            val key = threadKey(id)
            ensureThreadObserver(coord, id)
            val desired = action.kind.project(confirmed)
            submitted[id] = coord.submit(key, confirmed, desired,
                setOf(CacheTag(DataDomain.Notifications, row.repository?.fullName?.lowercase())),
                send = { repository.sendThreadAction(account, id, action.kind); it },
                readBack = { repository.readThreadState(account, id) })
        }
        viewModelScope.launch {
            val confirmedIds = mutableSetOf<String>()
            val rejectedIds = mutableSetOf<String>()
            val uncertainIds = mutableSetOf<String>()
            val notSentIds = mutableSetOf<String>()
            var lastFailureMessage: String? = null
            val outcomes = coroutineScope {
                ids.map { id -> async {
                    if (id !in submitted) return@async Triple(id, BatchOutcome.NotSent, null)
                    val settled = coord.observe(threadKey(id)).first { it.phase != WritePhase.PENDING }
                    Triple(id, when (settled.phase) {
                        WritePhase.IDLE -> if (submitted[id] == true) BatchOutcome.Confirmed else BatchOutcome.NotSent
                        WritePhase.REJECTED -> BatchOutcome.Rejected
                        else -> BatchOutcome.Uncertain
                    }, settled.message)
                } }.awaitAll()
            }
            for ((id, outcome, message) in outcomes) when (outcome) {
                BatchOutcome.Confirmed -> confirmedIds.add(id)
                BatchOutcome.Rejected -> { rejectedIds.add(id); lastFailureMessage = message }
                BatchOutcome.Uncertain -> uncertainIds.add(id)
                BatchOutcome.NotSent -> notSentIds.add(id)
            }
            // 未知项绝不进入可以直接重发的 failedAction；只允许重发未发送或明确拒绝的条目。
            val retryable = rejectedIds + notSentIds
            if (retryable.isNotEmpty() && state.query() == mutableUiState.value.query()) failedAction = action.copy(threadIds = retryable)
            val current = mutableUiState.value
            val selected = current.selectedThreadIds - confirmedIds - uncertainIds
            val selectionFinished = action.fromSelection && selected.isEmpty()
            val actionLabel = action.kind.label
            val errorText =
                when {
                    uncertainIds.isNotEmpty() && retryable.isEmpty() ->
                        GithubTxt.current.Common.MutationUncertain
                    retryable.isNotEmpty() ->
                        if (action.threadIds.size == 1)
                            GithubTxt.current.Notifications.ActionFailure(
                                actionLabel,
                                lastFailureMessage ?: GithubTxt.current.Notifications.RequestFailed,
                            )
                        else
                            GithubTxt.current.Notifications.PartialFailure(
                                actionLabel,
                                confirmedIds.size,
                                retryable.size + uncertainIds.size,
                                lastFailureMessage ?: GithubTxt.current.Notifications.RequestFailed,
                            )
                    else -> null
                }
            mutableUiState.value =
                current.copy(
                    isSelecting = current.isSelecting && !selectionFinished,
                    selectedThreadIds = selected,
                    isProcessing = current.isProcessing && !action.fromSelection,
                    pendingThreadIds = current.pendingThreadIds - ids,
                    actionError = errorText,
                )
            if (uncertainIds.isNotEmpty()) revalidate()
        }
    }

    private data class ThreadAction(
        val threadIds: Set<String>,
        val kind: ThreadActionKind,
        val fromSelection: Boolean = false,
    )

    private val ThreadActionKind.label: String
        get() =
            when (this) {
                ThreadActionKind.MarkRead -> GithubTxt.current.Notifications.MarkRead
                ThreadActionKind.MarkUnread -> GithubTxt.current.Notifications.MarkUnread
                ThreadActionKind.MarkDone -> GithubTxt.current.Notifications.MarkDone
                ThreadActionKind.MarkUndone -> GithubTxt.current.Notifications.MoveToInbox
            }

    private fun Exception.messageForUser(): String =
        when (this) {
            is HttpException ->
                when (code()) {
                    401 -> GithubTxt.current.Notifications.SessionExpired
                    403 -> GithubTxt.current.Notifications.RequestForbidden
                    404 -> GithubTxt.current.Notifications.NotFound
                    429 -> GithubTxt.current.Notifications.RateLimited
                    else -> GithubTxt.current.Repository.HttpFailed(code())
                }
            is IOException -> GithubTxt.current.Repository.NetworkFailed
            is SerializationException -> GithubTxt.current.Repository.ParseFailed
            else -> GithubTxt.current.Notifications.RequestFailed
        }
}
