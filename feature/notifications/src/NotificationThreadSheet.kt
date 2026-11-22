package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.githubWorkStatusLabel

@Composable
internal fun NotificationThreadSheet(
    notification: NotificationDto?,
    preview: NotificationPreviewDto?,
    isDone: Boolean,
    canChangeReadState: Boolean,
    canChangeDoneState: Boolean,
    onDismiss: () -> Unit,
    onSetUnread: (String, Boolean) -> Unit,
    onSetDone: (String, Boolean) -> Unit,
) {
    val strings = LocalGithubStrings.current.Notifications
    val work = LocalGithubStrings.current.WorkDetail
    val common = LocalGithubStrings.current.Common
    var lastNotification by remember { mutableStateOf(notification) }
    SideEffect { if (notification != null) lastNotification = notification }
    val item = notification ?: lastNotification
    val id = item?.threadId
    GlasmioBottomSheet(
        visible = notification != null,
        onDismissRequest = onDismiss,
        title = strings.ThreadActions,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = strings.ThreadActions,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
            )
        },
    ) {
        if (item != null) {
            val details = preview ?: item.preview
            val workKind =
                when (item.subject?.type) {
                    "PullRequest" -> CollaborationKind.PullRequest
                    "Issue" -> CollaborationKind.Issue
                    else -> null
                }
            val status =
                workKind?.let { kind ->
                    details?.state?.let { state ->
                        githubWorkStatusLabel(kind, state, details.draft, details.merged)
                    }
                }
            Column(
                Modifier.fillMaxWidth()
                    .background(GlasmioTheme.colors.groupedBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(LocalGlasmioSheetContentPadding.current)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GithubSectionHeader(strings.Subject)
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title =
                                item.subject?.title?.takeIf(String::isNotBlank)
                                    ?: strings.Untitled,
                            systemName = null,
                            showTopSeparator = false,
                        )
                    }
                }
                details?.author?.let { authorInfo ->
                    authorInfo.login?.takeIf(String::isNotBlank)?.let { author ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GithubSectionHeader(work.Author)
                            GlasmioGroupedList {
                                GlasmioListRow(
                                    title = author,
                                    leading = {
                                        GithubAvatar(
                                            url = authorInfo.avatarUrl,
                                            login = author,
                                            type = authorInfo.type,
                                            size = 24.dp,
                                        )
                                    },
                                    systemName = null,
                                    showTopSeparator = false,
                                )
                            }
                        }
                    }
                }
                item.repository?.fullName?.let { repository ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GithubSectionHeader(strings.Repository)
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = repository,
                                systemName = null,
                                showTopSeparator = false,
                            )
                        }
                    }
                }
                if (status != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GithubSectionHeader(work.Status)
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = status,
                                systemName = null,
                                showTopSeparator = false,
                            )
                        }
                    }
                }
                if (id != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GithubSectionHeader(strings.MoreActions)
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = if (item.unread) strings.MarkRead else strings.MarkUnread,
                                enabled = canChangeReadState,
                                systemName = null,
                                showTopSeparator = false,
                                onClick = {
                                    if (notification != null) onSetUnread(id, !item.unread)
                                },
                            )
                            GlasmioListRow(
                                title = if (isDone) strings.MoveToInbox else strings.MarkDone,
                                enabled = canChangeDoneState,
                                systemName = null,
                                onClick = {
                                    if (notification != null) onSetDone(id, !isDone)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
