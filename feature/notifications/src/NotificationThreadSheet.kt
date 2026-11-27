package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.GithubBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.AtSign
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Clock
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.githubActivityTime

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
    GithubBottomSheet(
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
                actions = {
                    if (id != null) {
                        GlasmioIconButton(
                            onClick = { if (notification != null) onSetDone(id, !isDone) },
                            enabled = canChangeDoneState,
                        ) {
                            GithubIcon(
                                Lucide.Check,
                                contentDescription = if (isDone) strings.MoveToInbox else strings.MarkDone,
                            )
                        }
                    }
                },
            )
        },
    ) {
        if (item != null) {
            val author = (preview ?: item.preview)?.author
            val login = author?.login?.takeIf(String::isNotBlank)
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlasmioGroupedList {
                    if (login != null) {
                        GlasmioListRow(
                            title = work.Author,
                            leading = {
                                GithubIcon(
                                    Lucide.AtSign,
                                    contentDescription = null,
                                    size = 20.dp,
                                    tint = GlasmioTheme.colors.secondaryContent,
                                )
                            },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    GithubAvatar(author.avatarUrl, login, size = 20.dp)
                                    Text(
                                        text = login,
                                        style = GlasmioTheme.textStyles.body,
                                        color = GlasmioTheme.colors.secondaryContent,
                                    )
                                }
                            },
                            showTopSeparator = false,
                            showDisclosure = false,
                        )
                    }
                    GlasmioListRow(
                        title = strings.Updated,
                        detail = item.updatedAt?.let(::githubActivityTime),
                        leading = {
                            GithubIcon(
                                Lucide.Clock,
                                contentDescription = null,
                                size = 20.dp,
                                tint = GlasmioTheme.colors.secondaryContent,
                            )
                        },
                        showTopSeparator = login != null,
                        showDisclosure = false,
                    )
                }
                GithubSection(title = strings.Subject) {
                    GlasmioListRow(
                        title =
                            item.subject?.title?.takeIf(String::isNotBlank)
                                ?: strings.Untitled,
                        systemName = null,
                        showTopSeparator = false,
                        showDisclosure = false,
                    )
                }
                GithubSection(title = strings.Repository) {
                    GlasmioListRow(
                        title = item.repository?.fullName ?: strings.Repository,
                        systemName = null,
                        showTopSeparator = false,
                        showDisclosure = false,
                    )
                }
                if (id != null) {
                    GithubSection(title = strings.MoreActions) {
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
                            showTopSeparator = true,
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
