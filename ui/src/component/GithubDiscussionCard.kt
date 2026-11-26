package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.CircleCheck
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessageSquare
import io.github.yumeyucca.lucide.ThumbsUp
import moe.yumeyuka.yumehub.data.api.dto.DiscussionListItemDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

/** 仓库与个人讨论列表共用的卡片；分类、标签和回复数按需出现。 */
@Composable
fun GithubDiscussionCard(
    item: DiscussionListItemDto?,
    onClick: (() -> Unit)? = null,
) {
    GithubLoadingCard(item == null, deferUntilEntry = false, placeholder = { WorkItemSkeleton() }) {
        if (item != null) DiscussionCardContent(item, onClick)
    }
}

@Composable
private fun DiscussionCardContent(item: DiscussionListItemDto, onClick: (() -> Unit)?) {
    val strings = LocalGithubStrings.current
    val colors = GlasmioTheme.colors
    val styles = GlasmioTheme.textStyles
    val tag = item.labels.firstOrNull()?.name ?: item.category
    val meta =
        listOfNotNull(githubRelativeTime(item.createdAt, strings.Notifications), tag)
            .joinToString(" · ")
    GithubActivityCardContent(
        onClick = onClick,
        header = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GithubAvatar(
                    item.author?.avatarUrl,
                    item.author?.login,
                    item.author?.type,
                    size = GithubActivityAuthorAvatarSize,
                )
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            item.author?.login ?: strings.Activity.Unavailable,
                            modifier = Modifier.weight(1f),
                            style = styles.subheadline,
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        GithubCardField(item.authorAssociation?.let(strings.Activity.DiscussionAuthorRole)) { role ->
                            Text(
                                role,
                                style = styles.caption1,
                                color = colors.secondaryContent,
                                maxLines = 1,
                            )
                        }
                    }
                    GithubCardField(meta.takeIf(String::isNotEmpty)) { value ->
                        Text(
                            value,
                            style = styles.caption1,
                            color = colors.secondaryContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        summary = { GithubActivityCardSummary(item.title, item.bodyPreview) },
        metadata = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GithubIcon(
                            Lucide.ThumbsUp,
                            contentDescription = null,
                            size = 16.dp,
                            tint = colors.secondaryContent,
                        )
                        Text(
                            strings.Activity.Upvote(item.upvoteCount),
                            style = styles.footnote,
                            color = colors.secondaryContent,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GithubIcon(
                            Lucide.MessageSquare,
                            contentDescription = null,
                            size = 16.dp,
                            tint = colors.secondaryContent,
                        )
                        Text(
                            strings.WorkList.CommentsCount(item.commentsCount),
                            style = styles.footnote,
                            color = colors.secondaryContent,
                        )
                    }
                }
                if (item.answered) {
                    GithubIcon(
                        Lucide.CircleCheck,
                        contentDescription = strings.WorkList.Answered,
                        size = 16.dp,
                        tint = GithubWorkColors.open,
                    )
                }
                if (item.closed) {
                    GithubDiscussionStatusIcon(closed = true, size = 16.dp)
                }
                GithubCardField(item.commentAuthors.takeIf { it.isNotEmpty() }) { authors ->
                    GithubDiscussionParticipantAvatars(authors)
                }
            }
        },
    )
}

@Composable
fun GithubDiscussionParticipantAvatars(participants: List<UserDto>) {
    GithubActivityAvatarStack(participants)
}

@Composable
internal fun GithubActivityAvatarStack(people: List<UserDto>) {
    val authors = people.distinctBy { it.login ?: it.avatarUrl }.take(3)
    if (authors.isEmpty()) return
    val size = 24.dp
    val overlap = 12.dp
    Box(
        Modifier.width(size + overlap * (authors.size - 1)).height(size),
        contentAlignment = Alignment.CenterStart,
    ) {
        authors.forEachIndexed { index, author ->
            GithubAvatar(
                url = author.avatarUrl,
                login = author.login,
                size = size,
                modifier =
                    Modifier.offset(x = overlap * index)
                        .border(2.dp, GlasmioTheme.colors.elevatedBackground, CircleShape),
            )
        }
    }
}
