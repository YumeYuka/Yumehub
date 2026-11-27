package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import moe.yumeyuka.yumehub.data.api.dto.workTarget
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder

/** 议题和拉取请求卡片尺寸固定：标题两行槽、统计行等高，骨架与正文等高交接。 */
@Composable
fun GithubWorkItemCard(
    item: CollaborationItemDto?,
    kind: CollaborationKind,
    onClick: (() -> Unit)? = null,
) {
    GithubLoadingCard(
        loading = item == null,
        placeholder = { WorkItemSkeleton(showBodyPreview = false) },
    ) {
        if (item != null) WorkItemContent(item, kind, onClick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkItemContent(
    item: CollaborationItemDto,
    kind: CollaborationKind,
    onClick: (() -> Unit)?,
) {
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
            Text(
                shown.title,
                style = githubWorkItemTitleStyle(),
                color = GlasmioTheme.colors.content,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        metadata = {
            val comments = strings.WorkList.CommentsCount(item.commentsCount)
            val participants = item.participantsCount?.let(strings.WorkList.ParticipantsCount)
            Row(
                Modifier.fillMaxWidth().heightIn(min = GithubWorkItemMetaHeight),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    GithubWorkItemMeta(Lucide.MessageSquare, comments)
                    GithubCardField(participants) { GithubWorkItemMeta(Lucide.UsersRound, it) }
                    GithubCardField(shown.subIssuesTotal?.takeIf { it > 0 }) { total ->
                        GithubWorkItemMeta(
                            Lucide.ListChecks,
                            "${shown.subIssuesCompleted ?: 0}/$total",
                        )
                    }
                    GithubCardField(workMergeProgress(write.mergePhase)) {
                        Text(
                            it,
                            style = GlasmioTheme.textStyles.caption1,
                            color = GlasmioTheme.colors.secondaryContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                GithubCardField(
                    item.participantAvatars.takeIf {
                        (item.participantsCount ?: it.size) > 1 && it.isNotEmpty()
                    },
                ) {
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
internal fun WorkItemSkeleton(showBodyPreview: Boolean = true) {
    val styles = GlasmioTheme.textStyles
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(GithubActivityAuthorAvatarSize)
            // 真实头部两行文本直接按 lineHeight 堆叠，没有额外间距；这里同样只占行槽。
            Column(Modifier.weight(1f)) {
                TextLinePlaceholder(styles.subheadline, fraction = 0.34f)
                TextLinePlaceholder(styles.caption1, fraction = 0.5f)
            }
            TextPlaceholder(
                Modifier.width(20.dp),
                height = 20.dp,
                fraction = 1f,
                corner = PlaceholderTokens.BlockCorner,
            )
        }
        Column {
            // 标题固定两行槽，骨架与正文等高。
            TextLinePlaceholder(githubWorkItemTitleStyle(), fraction = 0.76f)
            TextLinePlaceholder(githubWorkItemTitleStyle(), fraction = 0.52f)
            if (showBodyPreview) {
                // 摘要正文与真实正文一样只在标题下留 4dp，行与行之间由 lineHeight 堆叠。
                Spacer(Modifier.height(4.dp))
                TextLinePlaceholder(styles.footnote, fraction = 0.92f)
                TextLinePlaceholder(styles.footnote, fraction = 0.78f)
                TextLinePlaceholder(styles.footnote, fraction = 0.58f)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = GithubWorkItemMetaHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextLinePlaceholder(styles.footnote, Modifier.width(112.dp))
            AvatarPlaceholder(GithubWorkItemMetaHeight)
        }
    }
}
