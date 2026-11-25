package moe.yumeyuka.yumehub.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.NotificationThreadAction as ThreadActionKind
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
    private var listGeneration = 0L
    private var loadedThroughPage = 0
    private var loadedFromPage = 1
    private var nextPage: Int? = null
    private var pagingPaused = false
    private var failedAction: ThreadAction? = null
    private val openedReadJobs = mutableMapOf<String, Job>()

    val uiState: StateFlow<NotificationsUiState> = mutableUiState.asStateFlow()

    init {
        // 首次进入由 GithubRefreshEffect 统一触发，避免 init 与首次激活各请求一次第一页。
        observeQuery(refresh = false)
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

    fun revalidate() {
        val state = mutableUiState.value
        if (state.isProcessing || state.isSelecting) return
        repository.refresh(account, state.query())
    }

    fun loadMore() {
        val state = mutableUiState.value
        if (
            state.isRefreshing ||
                state.isLoadingMore ||
                state.isProcessing ||
                pagingPaused ||
                listJob?.isActive == true
        )
            return
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
                state.isProcessing ||
                pagingPaused ||
                listJob?.isActive == true ||
                loadedFromPage <= 1
        )
            return
        val start = (loadedFromPage - 10).coerceAtLeast(1)
        loadPages(start, loadedFromPage - 1, replace = true)
    }

    fun selectFilter(filter: NotificationFilter) {
        val state = mutableUiState.value
        if (state.isProcessing || state.filter == filter) return
        loadedThroughPage = 0
        mutableUiState.value =
            state
                .clearSelectionRetry()
                .copy(
                    filter = filter,
                    isSelecting = false,
                    selectedThreadIds = emptySet(),
                )
        observeQuery()
    }

    fun selectRepository(repository: String?) {
        val state = mutableUiState.value
        if (
            state.isProcessing ||
                state.repository == repository ||
                repository != null && repository !in state.repositories
        ) {
            return
        }
        loadedThroughPage = 0
        mutableUiState.value =
            state
                .clearSelectionRetry()
                .copy(
                    repository = repository,
                    isSelecting = false,
                    selectedThreadIds = emptySet(),
                )
        observeQuery()
    }

    fun selectReason(reason: NotificationReasonFilter) {
        val state = mutableUiState.value
        if (state.isProcessing || state.reasonFilter == reason) return
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
        if (state.isProcessing || !state.isSelecting) return
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
        val state = mutableUiState.value
        if (
            !notification.unread ||
                state.isProcessing ||
                state.isSelecting ||
                threadId in openedReadJobs
        )
            return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    repository.markRead(account, threadId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (failedAction == null && !mutableUiState.value.isProcessing) {
                        failedAction = ThreadAction(setOf(threadId), ThreadActionKind.MarkRead)
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                actionError =
                                    GithubTxt.current.Notifications.ActionFailure(
                                        GithubTxt.current.Notifications.MarkRead,
                                        error.messageForUser(),
                                    ),
                            )
                    }
                } finally {
                    openedReadJobs.remove(threadId)
                }
            }
        openedReadJobs[threadId] = job
        job.start()
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

    private fun observeQuery(refresh: Boolean = true) {
        listGeneration++
        listJob?.cancel()
        observationJob?.cancel()
        appending = false
        val query = mutableUiState.value.query()
        val observed = repository.resource(account, query)
        resource = observed
        applySnapshot(observed.state.value)
        observationJob = viewModelScope.launch {
            observed.state.collect { if (resource === observed) applySnapshot(it) }
        }
        if (refresh) repository.refresh(account, query)
    }

    private fun applySnapshot(cached: CachedDataState<NotificationsSnapshot>) {
        val current = mutableUiState.value
        val snapshot = cached.data
        loadedFromPage = snapshot?.loadedFromPage ?: 1
        loadedThroughPage = snapshot?.loadedThroughPage ?: 0
        nextPage = snapshot?.takeIf { it.nextCursor != null }?.let { it.loadedThroughPage + 1 }
        // 失效的磁盘窗口必须先重读，不能继续使用操作前的游标。
        pagingPaused = snapshot?.pagingPaused == true || snapshot != null && cached.updatedAt == 0L
        val notifications = snapshot?.notifications.orEmpty()
        val selected =
            current.selectedThreadIds.intersect(
                notifications.mapNotNull { it.threadId }.toSet(),
            )
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
                hasMore = !pagingPaused && nextPage != null,
                hasPrevious = !pagingPaused && loadedFromPage > 1,
                loadError = error.takeUnless { appending },
                appendError = error.takeIf { appending },
                selectedThreadIds = selected,
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
        val replacesList = replace || fromPage == 1
        appending = !replacesList
        mutableUiState.value =
            state.copy(
                isRefreshing = replacesList,
                isLoadingMore = !replacesList,
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
                mutableUiState.value =
                    mutableUiState.value.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        loadError = error.messageForUser().takeIf { replacesList },
                        appendError = error.messageForUser().takeUnless { replacesList },
                    )
            }
        }
    }

    private fun process(action: ThreadAction) {
        val state = mutableUiState.value
        if (
            state.isProcessing ||
                action.threadIds.isEmpty() ||
                action.threadIds.any(String::isBlank)
        )
            return
        listGeneration++
        listJob?.cancel()
        appending = false
        failedAction = null
        mutableUiState.value =
            state.copy(
                isProcessing = true,
                isRefreshing = false,
                isLoadingMore = false,
                actionError = null,
            )
        viewModelScope.launch {
            val succeeded = mutableSetOf<String>()
            var failure: Exception? = null
            for (batch in action.threadIds.chunked(3)) {
                val results = coroutineScope {
                    batch
                        .map { threadId ->
                            async {
                                try {
                                    openedReadJobs[threadId]?.join()
                                    repository.markThread(account, threadId, action.kind)
                                    threadId to null
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    threadId to error
                                }
                            }
                        }
                        .awaitAll()
                }
                for ((threadId, error) in results) {
                    if (error == null) succeeded.add(threadId)
                    else if (failure == null || error.stopBatch()) failure = error
                }
                applySnapshot(resource.state.value)
                if (results.any { it.second?.stopBatch() == true }) break
            }
            val remaining = action.threadIds - succeeded
            if (remaining.isNotEmpty()) failedAction = action.copy(threadIds = remaining)
            val current = mutableUiState.value
            val selected = current.selectedThreadIds - succeeded
            val selectionFinished = state.selectedThreadIds.isNotEmpty() && selected.isEmpty()
            val pausePaging = failure?.stopBatch() == true
            if (pausePaging) {
                // 操作已停止，保留部分成功结果，等用户主动刷新后再恢复两个方向的分页。
                pagingPaused = true
                listGeneration++
                listJob?.cancel()
                nextPage = null
                repository.pausePaging(account, current.query())
            }
            val actionLabel = action.kind.label
            mutableUiState.value =
                current.copy(
                    isSelecting = current.isSelecting && !selectionFinished,
                    selectedThreadIds = selected,
                    isProcessing = false,
                    isRefreshing = current.isRefreshing && !pausePaging,
                    isLoadingMore = current.isLoadingMore && !pausePaging,
                    hasMore = current.hasMore && !pausePaging,
                    hasPrevious = current.hasPrevious && !pausePaging,
                    actionError =
                        failure?.let {
                            if (action.threadIds.size == 1)
                                GithubTxt.current.Notifications.ActionFailure(
                                    actionLabel,
                                    it.messageForUser(),
                                )
                            else
                                GithubTxt.current.Notifications.PartialFailure(
                                    actionLabel,
                                    succeeded.size,
                                    remaining.size,
                                    it.messageForUser(),
                                )
                        },
                )
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

    private fun Exception.stopBatch(): Boolean =
        this is IOException || this is HttpException && code() == 401 || mayBeRateLimited()

    // 二级限流的 403 不保证带有 Retry-After 等响应头。
    private fun Exception?.mayBeRateLimited(): Boolean =
        this is HttpException && (code() == 403 || code() == 429)

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
