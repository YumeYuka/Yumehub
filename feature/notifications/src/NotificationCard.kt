package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.NotificationDto
import moe.yumeyuka.yumehub.data.api.dto.NotificationPreviewDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubWorkStatusTokens
import moe.yumeyuka.yumehub.ui.component.GithubWorkflowStatusIcon
import moe.yumeyuka.yumehub.ui.component.githubRelativeTime
import moe.yumeyuka.yumehub.ui.component.githubTitleOpticalCenter
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.NotificationRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.NotificationRowTokens
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors
import moe.yumeyuka.yumehub.ui.theme.readableForeground

@Composable
internal fun NotificationCard(
    notification: NotificationDto?,
    preview: NotificationPreviewDto?,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    GithubLoadingCard(
        loading = notification == null,
        placeholder = { NotificationRowPlaceholder() },
    ) {
        if (notification != null) {
            NotificationCardContent(
                notification,
                preview,
                isSelecting,
                isSelected,
                onClick,
                onLongClick,
            )
        }
    }
}

@Composable
private fun NotificationCardContent(
    notification: NotificationDto,
    preview: NotificationPreviewDto?,
    isSelecting: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val strings = LocalGithubStrings.current.Notifications
    val colors = GlasmioTheme.colors
    val styles = GlasmioTheme.textStyles
    val metadataStyle =
        styles.footnote.copy(
            fontSize = NotificationRowTokens.MetadataSize,
            lineHeight = NotificationRowTokens.MetadataLineHeight,
        )
    val titleStyle =
        styles.body.copy(
            fontSize = NotificationRowTokens.TitleSize,
            lineHeight = NotificationRowTokens.TitleLineHeight,
            fontWeight = FontWeight.Medium,
        )
    val title = notification.subject?.title?.takeIf(String::isNotBlank) ?: strings.Untitled
    val statusSlotSize =
        maxOf(
            GithubWorkStatusTokens.IconSize,
            with(LocalDensity.current) { titleStyle.lineHeight.toDp() },
        )
    val statusAlignment = with(LocalDensity.current) { statusSlotSize.roundToPx() / 2 }
    val number =
        when (val target = notification.toTarget()) {
            is NotificationTarget.Issue -> target.number
            is NotificationTarget.PullRequest -> target.number
            else -> null
        }
    val repository = notification.repository?.fullName ?: strings.Repository
    val activityText = preview?.body?.takeIf(String::isNotBlank)
    val hasActivity = preview?.author != null || activityText != null
    Column(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                        onLongClickLabel = strings.ThreadActions,
                    )
                } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NotificationRowTokens.ContentGap),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier.width(statusSlotSize).alignBy { statusAlignment },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.height(statusSlotSize), contentAlignment = Alignment.Center) {
                    NotificationSubjectIcon(
                        notification.subject?.type,
                        preview,
                        GithubWorkStatusTokens.IconSize,
                    )
                }
                if (!isSelecting && notification.unread) {
                    Box(
                        Modifier.size(10.dp)
                            .background(
                                readableForeground(
                                    colors.accent,
                                    colors.elevatedBackground,
                                    colors.content,
                                ),
                                CircleShape,
                            )
                            .semantics { contentDescription = strings.Unread },
                    )
                }
            }
            Column(
                Modifier.weight(1f).alignBy(githubTitleOpticalCenter(title, titleStyle)),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    style = titleStyle,
                    color = colors.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (number == null) repository else "$repository #$number",
                        Modifier.weight(1f),
                        style = metadataStyle,
                        color = colors.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    githubRelativeTime(notification.updatedAt, strings)?.let { time ->
                        Text(
                            time,
                            style = metadataStyle,
                            color = colors.secondaryContent,
                            maxLines = 1,
                        )
                    }
                    if (isSelecting && isSelected) {
                        GithubIcon(Lucide.Check, contentDescription = null, size = 20.dp)
                    }
                }
                if (hasActivity) {
                    NotificationPreviewContent(
                        preview = preview,
                        activityText = activityText,
                        metadataStyle = metadataStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationPreviewContent(
    preview: NotificationPreviewDto?,
    activityText: String?,
    metadataStyle: TextStyle,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview?.author?.let { actor ->
            GithubAvatar(
                url = actor.avatarUrl,
                login = actor.login,
                type = actor.type,
                size = NotificationRowTokens.AvatarSize,
                modifier =
                    Modifier.clearAndSetSemantics { contentDescription = actor.login.orEmpty() },
            )
        }
        activityText?.let { text ->
            Text(
                text,
                style = metadataStyle,
                color = GlasmioTheme.colors.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationSubjectIcon(
    type: String?,
    preview: NotificationPreviewDto?,
    iconSize: Dp,
) {
    if (type == "CheckSuite" || type == "WorkflowRun") {
        GithubWorkflowStatusIcon(preview?.state, contentDescription = null, size = iconSize)
        return
    }
    val pullRequest = type == "PullRequest"
    val closed = preview?.state == "CLOSED"
    val completed = closed && preview.stateReason == "COMPLETED"
    val icon =
        when {
            pullRequest && preview?.merged == true -> Lucide.GitMerge
            pullRequest && closed -> Lucide.GitPullRequestClosed
            pullRequest && preview?.draft == true -> Lucide.GitPullRequestDraft
            pullRequest -> Lucide.GitPullRequest
            type == "Issue" && completed -> Lucide.CircleCheck
            type == "Issue" && closed -> Lucide.CircleSlash
            type == "Issue" -> Lucide.CircleDot
            type == "Release" -> Lucide.Tag
            type == "Discussion" -> Lucide.MessageSquare
            type == "Commit" -> Lucide.GitCommitHorizontal
            type == "Gist" -> Lucide.Code
            type == "RepositoryInvitation" || type == "MemberFeatureRequestNotification" ->
                Lucide.UserPlus
            type == "RepositoryVulnerabilityAlert" || type == "RepositoryAdvisory" ||
                type == "RepositoryDependabotAlertsThread" || type == "SecurityAdvisory" ->
                Lucide.ShieldAlert
            else -> Lucide.Bell
        }
    val color =
        when {
            pullRequest && preview?.merged == true || completed -> GithubWorkColors.merged
            pullRequest && closed -> GithubWorkColors.closed
            closed || preview?.draft == true -> GithubWorkColors.draft
            type in listOf("Issue", "PullRequest") && preview?.state == "OPEN" ->
                GithubWorkColors.open
            else -> GlasmioTheme.colors.secondaryContent
        }
    GithubIcon(icon, contentDescription = null, tint = color, size = iconSize)
}
