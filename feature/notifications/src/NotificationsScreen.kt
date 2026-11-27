package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.layout.Box
import io.github.neoruaa.glasmio.glass.GlasmioMenuAnchorState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.systemGray5
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.locale.GithubStrings_Notifications
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.GithubEmptyState
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.githubCardItemMotion
import moe.yumeyuka.yumehub.ui.component.isEmptyStateNetworkFailure

internal fun LazyListScope.NotificationsScreen(
    strings: GithubStrings_Notifications,
    state: NotificationsUiState,
    notifications: List<NotificationDto>,
    hasActiveSearch: Boolean,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    onOpenTarget: (NotificationTarget) -> Unit,
    onOpenNotification: (NotificationDto) -> Unit,
    onThreadActions: (NotificationDto) -> Unit,
    onRetryAction: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetryWindow: () -> Unit,
    retryLabel: String,
    onToggleSelection: (String) -> Unit,
    rowKeys: List<String>,
    onReconcileUncertain: () -> Unit = {},
    onAcknowledgeUncertain: () -> Unit = {},
) {
    if (state.uncertainThreadIds.isNotEmpty()) {
        item(key = "notifications-uncertain-banner") {
            val common = LocalGithubStrings.current.Common
            GlasmioGroupedList {
                GlasmioListRow(
                    title = common.MutationUncertain,
                    subtitle = "${state.uncertainThreadIds.size}",
                    systemName = null,
                )
                GlasmioListRow(
                    title = common.MutationReconcile,
                    systemName = null,
                    onClick = onReconcileUncertain,
                )
                GlasmioListRow(
                    title = common.MutationAcknowledged,
                    systemName = null,
                    onClick = onAcknowledgeUncertain,
                )
            }
        }
    }
    state.actionError?.let { message ->
        notificationStatus(
            "notifications-action-error",
            strings.ActionFailed,
            message,
            onRetryAction,
        )
    }
    state.loadError?.let { error ->
        val offline = !state.hasCachedContent && error.isEmptyStateNetworkFailure()
        notificationStatus(
            "notifications-load-error",
            retryLabel,
            if (offline) null else error.messageForUser(),
            onRetryLoad,
        )
        if (offline) {
            item(key = "notifications-offline") {
                val labels = LocalGithubStrings.current
                GithubEmptyState(
                    scene = EmptyScene.Offline,
                    title = labels.Common.NoOfflineData,
                    description = labels.Repository.NetworkFailed,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
        }
    }
    if (
        rowKeys.isEmpty() &&
            notifications.isEmpty() &&
            state.hasCachedContent && state.loadError == null && state.appendError == null
    ) {
        val hasActiveFilters =
            hasActiveSearch ||
                state.repository != null ||
                state.reasonFilter != NotificationReasonFilter.All
        item(key = "notifications-empty", contentType = "notifications-empty") {
            GithubEmptyState(
                scene = if (hasActiveFilters) EmptyScene.Search else EmptyScene.Done,
                title = if (hasActiveFilters) strings.NoMatches else strings.Empty,
                description = strings.EmptyHint.takeUnless { hasActiveFilters },
                modifier =
                    if (hasActiveSearch && (state.hasMore || state.hasPrevious)) Modifier
                    else Modifier.fillParentMaxHeight(),
            )
        }
    }
    items(
        count = rowKeys.size,
        key = { rowKeys[it] },
        contentType = { "notification-card" },
    ) { index ->
        val notification = notifications.getOrNull(index)
        Box(githubCardItemMotion()) {
            NotificationRow(
                notification,
                notification?.let { state.previews[it] },
                notification?.threadId in state.pendingThreadIds,
                state.isSelecting,
                notification?.threadId in state.selectedThreadIds,
                notification?.threadId in state.uncertainThreadIds,
                onToggleSelection,
                onOpenTarget,
                onOpenNotification,
                onThreadActions,
            )
        }
    }
    if (state.hasPrevious) {
        notificationStatus(
            "notifications-previous-page",
            strings.Previous,
            onClick =
                onLoadPrevious.takeIf {
                    !state.isRefreshing && !state.isLoadingMore
                },
        )
    }
    val appendError = state.appendError
    if (appendError != null) {
        notificationStatus(
            "notifications-next-page-error",
            retryLabel,
            appendError,
            onRetryWindow.takeUnless { state.isLoadingMore },
        )
    } else if (state.hasMore && !state.isLoadingMore) {
        notificationStatus(
            "notifications-next-page",
            strings.More,
            onClick = onLoadMore.takeUnless { state.isRefreshing },
        )
    }
}

@Composable
internal fun NotificationsControls(
    state: NotificationsUiState,
    onFilterChange: (NotificationFilter) -> Unit,
    onEndSelection: () -> Unit,
    onMarkSelectedRead: () -> Unit,
    onMarkSelectedDone: () -> Unit,
    showSelectionActions: Boolean,
) {
    val strings = LocalGithubStrings.current.Notifications
    if (state.isSelecting) {
        GlasmioGroupedList {
            GlasmioListRow(
                title = strings.SelectedCount(state.selectedThreadIds.size),
                subtitle = if (state.isProcessing) strings.Processing else strings.SelectionHint,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                systemName = null,
            )
            if (showSelectionActions) {
                SelectionActions(state, onEndSelection, onMarkSelectedRead, onMarkSelectedDone)
            }
        }
    } else {
        GlasmioSegmentedControl(
            items = NotificationFilter.entries.map { it to it.label(strings) },
            selected = state.filter,
            onSelected = onFilterChange,
            modifier = Modifier.fillMaxWidth(),
            height = 36.dp,
        )
    }
}

private fun LazyListScope.notificationStatus(
    key: String,
    title: String,
    message: String? = null,
    onClick: (() -> Unit)? = null,
) {
    item(key = key) {
        GlasmioGroupedList {
            GlasmioListRow(title = title, subtitle = message, systemName = null, onClick = onClick)
        }
    }
}

@Composable
internal fun NotificationsSelectionMenu(
    state: NotificationsUiState,
    anchor: GlasmioMenuAnchorState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEndSelection: () -> Unit,
    onMarkSelectedRead: () -> Unit,
    onMarkSelectedDone: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Notifications
    val canProcess = state.selectedThreadIds.isNotEmpty() && !state.isProcessing
    GlasmioMenu(
        expanded = expanded && !state.isProcessing,
        onExpandedChange = onExpandedChange,
        itemCount = 3,
        forceBelowAnchor = true,
        menuAnchorState = anchor,
        externalAnchorBounds = anchor.bounds,
        anchor = {},
    ) { backdrop, close ->
        GlasmioMenuItem(
            title = strings.CancelSelection,
            enabled = !state.isProcessing,
            backdrop = backdrop,
            onClick = {
                onExpandedChange(false)
                close()
                onEndSelection()
            },
        )
        GlasmioMenuItem(
            title = strings.BulkRead,
            enabled = canProcess,
            backdrop = backdrop,
            onClick = {
                onExpandedChange(false)
                close()
                onMarkSelectedRead()
            },
        )
        GlasmioMenuItem(
            title = strings.BulkDone,
            enabled = canProcess,
            backdrop = backdrop,
            onClick = {
                onExpandedChange(false)
                close()
                onMarkSelectedDone()
            },
        )
    }
}

@Composable
private fun SelectionActions(
    state: NotificationsUiState,
    onEndSelection: () -> Unit,
    onMarkSelectedRead: () -> Unit,
    onMarkSelectedDone: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Notifications
    val canProcess = state.selectedThreadIds.isNotEmpty() && !state.isProcessing
    GlasmioListRow(
        title = strings.CancelSelection,
        enabled = !state.isProcessing,
        onClick = onEndSelection,
    )
    GlasmioListRow(title = strings.BulkRead, enabled = canProcess, onClick = onMarkSelectedRead)
    GlasmioListRow(title = strings.BulkDone, enabled = canProcess, onClick = onMarkSelectedDone)
}

@Composable
private fun NotificationRow(
    notification: NotificationDto?,
    preview: NotificationPreviewDto?,
    pending: Boolean,
    isSelecting: Boolean,
    isSelected: Boolean,
    uncertain: Boolean,
    onToggleSelection: (String) -> Unit,
    onOpenTarget: (NotificationTarget) -> Unit,
    onOpenNotification: (NotificationDto) -> Unit,
    onThreadActions: (NotificationDto) -> Unit,
) {
    val target = remember(notification) { notification?.toTarget() }
    val threadId = notification?.threadId
    val enabled = !pending && !uncertain
    val selectable = isSelecting && threadId != null
    Box(if (selectable) Modifier.semantics { selected = isSelected } else Modifier) {
        NotificationCard(
            notification = notification,
            preview = preview,
            isSelecting = isSelecting,
            isSelected = isSelected,
            onLongClick =
                if (enabled && !isSelecting && threadId != null) {
                    { onThreadActions(notification) }
                } else null,
            onClick =
                when {
                    isSelecting ->
                        threadId?.takeIf { enabled }?.let { id -> { onToggleSelection(id) } }
                    notification != null && target != null -> ({
                            onOpenNotification(notification)
                            onOpenTarget(target)
                        })
                    else -> null
                },
        )
    }
}
