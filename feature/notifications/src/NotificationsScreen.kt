package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import moe.yumeyuka.yumehub.ui.component.GithubEmptyState
import moe.yumeyuka.yumehub.ui.component.GithubIcon

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
    onToggleSelection: (String) -> Unit,
    rowKeys: List<String>,
) {
    state.actionError?.let { message ->
        notificationStatus(
            "notifications-action-error",
            strings.ActionFailed,
            message,
            onRetryAction,
        )
    }
    if (
        rowKeys.isEmpty() &&
            notifications.isEmpty() &&
            !state.isRefreshing &&
            state.loadError == null &&
            state.appendError == null
    ) {
        val hasActiveFilters =
            hasActiveSearch ||
                state.repository != null ||
                state.reasonFilter != NotificationReasonFilter.All
        item(key = "notifications-empty", contentType = "notifications-empty") {
            GithubEmptyState(
                icon = Lucide.Bell,
                title = if (hasActiveFilters) strings.NoMatches else strings.Empty,
                description = strings.EmptyHint.takeUnless { hasActiveFilters },
                iconContainerSize = 50.dp,
                iconSize = 26.dp,
                iconContainerColor = GlasmioTheme.colors.systemGray5,
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
        Box(
            Modifier.animateItem(
                fadeInSpec = tween(260),
                fadeOutSpec = tween(180),
                placementSpec = spring(dampingRatio = 1f, stiffness = 400f),
            ),
        ) {
            NotificationRow(
                notification,
                notification?.let { state.previews[it] },
                !state.isProcessing,
                state.isSelecting,
                notification?.threadId in state.selectedThreadIds,
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
                    !state.isRefreshing && !state.isLoadingMore && !state.isProcessing
                },
        )
    }
    if (state.hasMore && !state.isLoadingMore) {
        notificationStatus(
            "notifications-next-page",
            strings.More,
            onClick = onLoadMore.takeIf { !state.isLoadingMore && !state.isProcessing },
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
            enabled = !state.isProcessing,
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
    enabled: Boolean,
    isSelecting: Boolean,
    isSelected: Boolean,
    onToggleSelection: (String) -> Unit,
    onOpenTarget: (NotificationTarget) -> Unit,
    onOpenNotification: (NotificationDto) -> Unit,
    onThreadActions: (NotificationDto) -> Unit,
) {
    val target = notification?.toTarget()
    val threadId = notification?.threadId
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
                    !enabled -> null
                    isSelecting -> threadId?.let { id -> { onToggleSelection(id) } }
                    notification != null && target != null -> ({
                            onOpenNotification(notification)
                            onOpenTarget(target)
                        })
                    else -> null
                },
        )
    }
}
