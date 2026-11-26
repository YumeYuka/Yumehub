package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.ListChecks
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessageSquare
import io.github.yumeyucca.lucide.UsersRound
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import androidx.compose.runtime.remember
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder

/** 议题和拉取请求沿用讨论列表的作者、摘要与底部统计布局。 */
@Composable
fun GithubWorkItemCard(
    item: CollaborationItemDto?,
    kind: CollaborationKind,
    onClick: (() -> Unit)? = null,
) {
    GithubLoadingCard(loading = item == null, deferUntilEntry = false, placeholder = { WorkItemSkeleton() }) {
        if (item != null) WorkItemContent(item, kind, onClick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkItemContent(item: CollaborationItemDto, kind: CollaborationKind, onClick: (() -> Unit)?) {
    val target = remember(item.htmlUrl, item.number) { item.workTarget() }
    val write = rememberWorkRowProjection(target)
    val shown = remember(item, write) { write.apply(item) }
    val strings = LocalGithubStrings.current
    val repository = item.repositoryName?.substringAfterLast('/')?.takeIf(String::isNotBlank)
    val meta = listOfNotNull(repository, "#${item.number}").joinToString(" · ")
    val relativeTime = githubRelativeTime(item.updatedAt, strings.Notifications)
    GithubActivityCardContent(
        onClick = onClick,
        header = {
            GithubWorkItemHeader(item.user, meta, relativeTime) {
                GithubWorkItemStatusIcon(
                    kind,
                    shown.state,
                    shown.draft,
                    shown.merged,
                    shown.stateReason,
                )
            }
        },
        summary = {
            Column {
                GithubActivityCardSummary(shown.title, shown.bodyPreview, titleMaxLines = 2)
                GithubCardField(workMergeProgress(write.mergePhase)) {
                    Text(it, Modifier.padding(top = 10.dp), style = GlasmioTheme.textStyles.caption1)
                }
                GithubCardField(shown.subIssuesTotal?.takeIf { it > 0 }) { total ->
                    GithubWorkItemMeta(
                        Lucide.ListChecks,
                        "${shown.subIssuesCompleted ?: 0}/$total",
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        metadata = {
            val comments = strings.WorkList.CommentsCount(item.commentsCount)
            val participants = item.participantsCount?.let(strings.WorkList.ParticipantsCount)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GithubWorkItemMeta(Lucide.MessageSquare, comments)
                    GithubCardField(participants) { GithubWorkItemMeta(Lucide.UsersRound, it) }
                }
                GithubCardField(item.participantAvatars.takeIf { (item.participantsCount ?: it.size) > 1 && it.isNotEmpty() }) {
                    GithubActivityAvatarStack(it)
                }
            }
        },
    )
}

@Composable
internal fun GithubWorkItemHeader(
    user: UserDto?,
    meta: String,
    relativeTime: String? = null,
    trailing: @Composable () -> Unit,
) {
    val styles = GlasmioTheme.textStyles
    val colors = GlasmioTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GithubAvatar(
            user?.avatarUrl,
            user?.login,
            user?.type,
            size = GithubActivityAuthorAvatarSize,
        )
        Column(Modifier.weight(1f)) {
            Text(
                user?.login ?: LocalGithubStrings.current.Activity.Unavailable,
                style = styles.subheadline,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(relativeTime, meta.takeIf(String::isNotBlank)).joinToString(" · "),
                style = styles.caption1,
                color = colors.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
internal fun GithubWorkItemStatusIcon(
    kind: CollaborationKind,
    state: String?,
    draft: Boolean,
    merged: Boolean,
    stateReason: String? = null,
) {
    val status = githubWorkStatusVisual(kind, state, draft, merged, stateReason)
    GithubIcon(
        status.icon,
        contentDescription = githubWorkStatusLabel(kind, state, draft, merged),
        size = 20.dp,
        tint = status.color,
    )
}

@Composable
private fun GithubWorkItemMeta(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val color = GlasmioTheme.colors.secondaryContent
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GithubIcon(icon, contentDescription = null, size = 16.dp, tint = color)
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = GlasmioTheme.textStyles.footnote,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
internal fun WorkItemSkeleton() {
    val styles = GlasmioTheme.textStyles
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarPlaceholder(GithubActivityAuthorAvatarSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextLinePlaceholder(styles.subheadline, fraction = 0.34f)
                TextLinePlaceholder(styles.caption1, fraction = 0.5f)
            }
            TextPlaceholder(Modifier.width(20.dp), height = 20.dp, fraction = 1f, corner = PlaceholderTokens.BlockCorner)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextLinePlaceholder(githubWorkItemTitleStyle(), fraction = 0.76f)
            TextLinePlaceholder(styles.footnote, fraction = 0.92f)
            TextLinePlaceholder(styles.footnote, fraction = 0.58f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextLinePlaceholder(styles.footnote, Modifier.width(112.dp))
            AvatarPlaceholder(24.dp)
        }
    }
}
