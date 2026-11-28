package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioSearchToolbar
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import org.koin.androidx.compose.koinViewModel

@Composable
fun NotificationsPage(
    showSelectionActions: Boolean,
    onOpenWork:
        (
            owner: String,
            name: String,
            kind: CollaborationKind,
            number: Int,
            onEntered: () -> Unit,
        ) -> Unit,
) {
    val strings = LocalGithubStrings.current.Notifications
    val common = LocalGithubStrings.current.Common
    val viewModel: NotificationsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Notifications,
        pollWhileVisible = true,
        onRefresh = viewModel::revalidate,
    )
    val openLink = rememberGithubUriHandler()
    val host = requireNotNull(LocalGithubPageHost.current)
    val bottomPadding = host.bottomPadding
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var searchInput by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue())
        }
    var filtersVisible by remember { mutableStateOf(false) }
    val hideKeyboard: () -> Unit = {
        focus.clearFocus()
        keyboard?.hide()
    }
    val closeSearch = {
        searching = false
        hideKeyboard()
    }
    var completionThreads by remember(viewModel) { mutableStateOf(emptySet<String>()) }
    var acknowledgeUncertainConfirm by remember(viewModel) { mutableStateOf(false) }
    var threadActions by remember(viewModel) { mutableStateOf<NotificationDto?>(null) }
    var pendingOpened by remember(viewModel) { mutableStateOf<NotificationDto?>(null) }
    val completeSelection = { completionThreads = state.selectedThreadIds.toSet() }
    val query = searchInput.text.trim()
    val source = "${state.filter}:${state.repository}:${state.reasonFilter}:$query"
    val listState =
        LocalGithubPageState.current?.listState("notifications:$source")
            ?: remember(source) { LazyListState() }
    val allocator = remember(source) { GithubPagingSlotKeys("notifications:$source") }
    val visibleNotifications =
        remember(state.notifications, state.reasonFilter) { state.visibleNotifications }
    val notifications =
        remember(visibleNotifications, query) {
            if (query.isEmpty()) visibleNotifications
            else
                visibleNotifications.filter { notification ->
                    val repository = notification.repository
                    val repositoryName = repository?.fullName ?: repository?.name
                    notification.subject?.title?.contains(query, ignoreCase = true) == true ||
                        repositoryName?.contains(query, ignoreCase = true) == true
                }
        }
    val initialLoading = !state.hasCachedContent && state.isRefreshing && state.loadError == null
    val rowKeys =
        remember(allocator, notifications, initialLoading, state.isLoadingMore) {
            allocator.update(
                notifications.mapIndexed { index, item -> item.id ?: "missing:$index" },
                loading = state.isLoadingMore,
                initialLoading = initialLoading,
            )
        }

    GithubBackHandler(
        enabled = searching && !filtersVisible && !state.isSelecting,
        onBack = closeSearch,
    )
    GithubBackHandler(enabled = state.isSelecting, onBack = viewModel::endSelection)

    GithubPage(
        title = strings.Title,
        listState = listState,
        itemSpacing = 12.dp,
        back = false,
        contentHeader = {
            NotificationsControls(
                state,
                viewModel::selectFilter,
                viewModel::endSelection,
                viewModel::markSelectedRead,
                completeSelection,
                showSelectionActions,
            )
        },
        actions = {
            val selectionMenu = state.isSelecting && !showSelectionActions
            var expanded by remember(selectionMenu) { mutableStateOf(false) }
            val anchor = rememberGlasmioMenuAnchorState()
            Box {
                GithubToolbarActions(
                    if (!state.isSelecting)
                        toolbarIcon(Lucide.Search, common.Search) {
                            if (searching) {
                                searchFocus.requestFocus()
                                keyboard?.show()
                            } else {
                                searching = true
                            }
                        }
                    else null,
                    if (!state.isSelecting)
                        toolbarIcon(Lucide.ListFilter, strings.FilterTitle) {
                            hideKeyboard()
                            filtersVisible = true
                        }
                    else null,
                    if (selectionMenu) {
                        val label =
                            if (state.isProcessing) strings.Processing
                            else strings.SelectedCount(state.selectedThreadIds.size)
                        GlasmioHeaderAction(
                            "selection",
                            "selection",
                            label,
                            { expanded = true },
                            !state.isProcessing,
                            anchor,
                        ) {
                            Text(label)
                        }
                    } else null,
                )
                if (selectionMenu)
                    NotificationsSelectionMenu(
                        state,
                        anchor,
                        expanded,
                        { expanded = it },
                        viewModel::endSelection,
                        viewModel::markSelectedRead,
                        completeSelection,
                    )
            }
        },
        bottomContent =
            if (searching && !state.isSelecting) {
                {
                    GlasmioSearchToolbar(
                        query = searchInput,
                        onQueryChange = { searchInput = it },
                        onSearch = { hideKeyboard() },
                        onCancel = closeSearch,
                        cancelLabel = common.Close,
                        placeholder = common.Search,
                        focusRequester = searchFocus,
                        modifier =
                            Modifier.consumeWindowInsets(PaddingValues(bottom = bottomPadding))
                                .imePadding()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            } else null,
    ) {
        NotificationsScreen(
            strings = strings,
            state = state,
            notifications = notifications,
            hasActiveSearch = query.isNotEmpty(),
            onLoadMore = viewModel::loadMore,
            onLoadPrevious = viewModel::loadPrevious,
            onOpenTarget = { target ->
                val opened = pendingOpened ?: return@NotificationsScreen
                when (target) {
                    is NotificationTarget.Issue ->
                        onOpenWork(
                            target.owner,
                            target.repository,
                            CollaborationKind.Issue,
                            target.number,
                        ) {
                            viewModel.markOpened(opened)
                            pendingOpened = null
                        }
                    is NotificationTarget.PullRequest ->
                        onOpenWork(
                            target.owner,
                            target.repository,
                            CollaborationKind.PullRequest,
                            target.number,
                        ) {
                            viewModel.markOpened(opened)
                            pendingOpened = null
                        }
                    is NotificationTarget.Web -> {
                        openLink(target.url)
                        viewModel.markOpened(opened)
                        pendingOpened = null
                    }
                }
            },
            onOpenNotification = { notification ->
                if (host.navigationEnabled) pendingOpened = notification
            },
            onThreadActions = { threadActions = it },
            onRetryAction = viewModel::retryAction,
            onRetryLoad = viewModel::revalidate,
            onRetryWindow = viewModel::retryWindow,
            retryLabel = common.Retry,
            onToggleSelection = viewModel::toggleSelection,
            rowKeys = rowKeys,
            onReconcileUncertain = viewModel::reconcileUncertain,
            onAcknowledgeUncertain = { acknowledgeUncertainConfirm = true },
        )
    }

    NotificationFiltersSheet(
        visible = filtersVisible,
        repositories = state.repositories,
        selectedRepository = state.repository,
        selectedReason = state.reasonFilter,
        enabled = true,
        onDismiss = { filtersVisible = false },
        onApply = { repository, reason ->
            viewModel.selectRepository(repository)
            viewModel.selectReason(reason)
        },
    )

    NotificationThreadSheet(
        notification = threadActions,
        preview = threadActions?.let(state.previews::get),
        isDone = state.filter == NotificationFilter.Done,
        canChangeReadState =
            threadActions?.threadId !in state.pendingThreadIds &&
                threadActions?.threadId !in state.uncertainThreadIds,
        canChangeDoneState =
            threadActions?.threadId !in state.pendingThreadIds &&
                threadActions?.threadId !in state.uncertainThreadIds,
        onDismiss = { threadActions = null },
        onSetUnread = { id, unread ->
            threadActions = null
            viewModel.setUnread(id, unread)
        },
        onSetDone = { id, done ->
            threadActions = null
            viewModel.setDone(id, done)
        },
    )

    GlasmioAlertDialog(
        visible = completionThreads.isNotEmpty(),
        onDismissRequest = { completionThreads = emptySet() },
        title = strings.ConfirmDone,
        message = strings.ConfirmDoneMessage(completionThreads.size),
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    common.Cancel,
                    { completionThreads = emptySet() },
                    GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    strings.MarkDone,
                    {
                        viewModel.markSelectedDone(completionThreads)
                        completionThreads = emptySet()
                    },
                    enabled = !state.isProcessing,
                ),
            ),
    )

    GlasmioAlertDialog(
        visible = acknowledgeUncertainConfirm && state.uncertainThreadIds.isNotEmpty(),
        onDismissRequest = { acknowledgeUncertainConfirm = false },
        title = common.MutationAcknowledged,
        message = common.MutationAcknowledgeWarning,
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    common.Cancel,
                    { acknowledgeUncertainConfirm = false },
                    GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    common.MutationAcknowledged,
                    {
                        viewModel.acknowledgeUncertain()
                        acknowledgeUncertainConfirm = false
                    },
                ),
            ),
    )
}
