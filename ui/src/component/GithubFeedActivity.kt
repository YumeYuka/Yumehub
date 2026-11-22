package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.theme.GithubActivityColors
import moe.yumeyuka.yumehub.ui.theme.readableForeground

@Composable
fun GithubFeedActivity(
    event: FeedEventDto,
    state: FeedCardState?,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val colors = GlasmioTheme.colors
    val styles = GlasmioTheme.textStyles
    val strings = LocalGithubStrings.current
    val activityLabel = event.activityLabel()
    val actor = event.actor
    val recommended =
        event.type == "FollowRecommendationEvent" || event.type == "RepositoryRecommendationEvent"
    val activityColor =
        when {
            recommended -> GithubActivityColors.neutral
            event.type == "FollowEvent" -> GithubActivityColors.accent
            event.type == "ReleaseEvent" ||
                event.type == "IssuesEvent" ||
                event.type == "DiscussionEvent" -> GithubActivityColors.success
            event.type.startsWith("PullRequest") || event.type == "ForkEvent" ->
                GithubActivityColors.done
            event.type == "WatchEvent" -> GithubActivityColors.starred
            event.type == "PublicEvent" || event.type == "MemberEvent" ->
                GithubActivityColors.severe
            event.type == "CreateEvent" -> GithubActivityColors.accent
            else -> GithubActivityColors.neutral
        }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val avatarSize = 30.dp
        // 活动行统一对齐，不随下方用户／仓库卡片的头像尺寸移动。
        val activityStartPadding = 16.dp + (18.dp - avatarSize) / 2
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = activityStartPadding, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val badgeSize = 16.dp
            val badgeOutsetX = 6.dp
            val badgeOutsetY = 2.dp
            Box(
                Modifier.size(
                    width = avatarSize + badgeOutsetX,
                    height = avatarSize + badgeOutsetY,
                ),
            ) {
                Box(
                    Modifier.size(avatarSize).drawWithCache {
                        val radius = badgeSize.toPx() / 2f
                        val cutout =
                            Path().apply {
                                addOval(
                                    Rect(
                                        center =
                                            Offset(
                                                size.width + badgeOutsetX.toPx() - radius,
                                                size.height + badgeOutsetY.toPx() - radius,
                                            ),
                                        radius = radius + 2.5.dp.toPx(),
                                    ),
                                )
                            }
                        // 仅从头像挖去缺口，间隙透出页面背景；角标自身不绘制边框。
                        onDrawWithContent {
                            clipPath(cutout, clipOp = ClipOp.Difference) {
                                this@onDrawWithContent.drawContent()
                            }
                        }
                    },
                ) {
                    GithubAvatar(
                        url =
                            if (recommended) "https://github.com/github.png" else actor?.avatarUrl,
                        login = if (recommended) "github" else actor?.login,
                        type = if (recommended) null else actor?.type,
                        size = avatarSize,
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .size(badgeSize)
                        .background(activityColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    GithubIcon(
                        event.activityIcon(),
                        contentDescription = null,
                        size = 11.dp,
                        tint = GithubActivityColors.onBadge,
                        filled = event.type == "WatchEvent",
                    )
                }
            }
            Text(
                buildAnnotatedString {
                    actor?.login?.takeIf(String::isNotBlank)?.let { login ->
                        withStyle(
                            SpanStyle(
                                color = colors.content,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        ) {
                            append(login)
                        }
                        append(" ")
                    }
                    append(activityLabel)
                },
                modifier = Modifier.weight(1f),
                style =
                    styles.body.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle =
                            LineHeightStyle(
                                LineHeightStyle.Alignment.Center,
                                LineHeightStyle.Trim.None,
                            ),
                    ),
                color = colors.secondaryContent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            githubRelativeTime(event.createdAt, strings.Notifications)?.let { time ->
                Text(
                    time,
                    style = styles.footnote.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = colors.tertiaryContent,
                    maxLines = 1,
                )
            }
        }
        when {
            state?.repository != null && event.workItem() != null ->
                FeedWorkCard(event, state, onOpen)
            state?.repository != null && event.payload.release != null ->
                FeedReleaseCard(event, state.repository, onOpen)
            state?.repository != null ->
                RepositoryRow(
                    repository = state.repository,
                    showCover = false,
                    onClick = onOpen,
                    action = { FeedAction(state, false, onRetry, onToggle) },
                    contributorsCount = state.contributorsCount,
                )
            state?.user != null -> {
                val user = state.user
                GithubActivityCard(
                    onClick = onOpen,
                    header = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            GithubAvatar(user.avatarUrl, user.login, type = user.type, size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    user.name?.takeIf { it.isNotBlank() } ?: user.login.orEmpty(),
                                    style =
                                        styles.body.copy(
                                            fontSize = 18.sp,
                                            lineHeight = 24.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    color = colors.content,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    user.login.orEmpty(),
                                    style =
                                        styles.footnote.copy(fontSize = 15.sp, lineHeight = 20.sp),
                                    color = colors.secondaryContent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    summary =
                        user.bio
                            ?.takeIf { it.isNotBlank() }
                            ?.let { bio ->
                                {
                                    bio.let {
                                        Text(
                                            it,
                                            style =
                                                styles.body.copy(
                                                    fontSize = 16.sp,
                                                    lineHeight = 24.sp,
                                                    fontWeight = FontWeight.Normal,
                                                ),
                                            color = colors.secondaryContent,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                    metadata =
                        if (user.publicRepos != null || user.followers != null)
                            ({
                                Text(
                                    listOfNotNull(
                                            user.publicRepos?.let(strings.Explore.RepositoryCount),
                                            user.followers?.let(strings.Profile.Followers),
                                        )
                                        .joinToString(" · "),
                                    style =
                                        styles.footnote.copy(fontSize = 15.sp, lineHeight = 20.sp),
                                    color = colors.secondaryContent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            })
                        else null,
                    action = { FeedAction(state, true, onRetry, onToggle) },
                )
            }
        }
    }
}

@Composable
private fun FeedAction(
    state: FeedCardState,
    following: Boolean,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    var confirm by remember { mutableStateOf(false) }
    val selected = state.selected == true
    val label =
        if (state.selected == null) {
            strings.Common.Retry
        } else if (following) {
            if (selected) strings.Explore.FollowingUser else strings.Profile.FollowUser
        } else {
            if (selected) strings.Home.Starred else strings.Repository.Star
        }
    val icon =
        if (following) {
            if (selected) Lucide.Check else Lucide.Plus
        } else Lucide.Star
    val filled = !following && selected
    val tint by
        animateColorAsState(
            targetValue =
                if (!following && selected)
                    readableForeground(
                        preferred = GlasmioTheme.colors.accent,
                        background = GlasmioTheme.colors.elevatedBackground,
                        fallback = GlasmioTheme.colors.content,
                    )
                else GlasmioTheme.colors.content,
            animationSpec = tween(180),
            label = "feed-action-tint",
        )
    GlasmioButton(
        onClick = {
            if (state.selected == null) onRetry()
            else if (following && selected) confirm = true else onToggle()
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy,
    ) {
        // 图文整体居中，文字按实际宽度测量，避免固定文字槽造成视觉偏移和间距漂移。
        Row(
            modifier = Modifier.heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GithubAnimatedIcon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                size = 20.dp,
                filled = filled,
                tint = tint,
            )
            val style =
                GlasmioTheme.textStyles.body.copy(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle =
                        LineHeightStyle(
                            LineHeightStyle.Alignment.Center,
                            LineHeightStyle.Trim.None,
                        ),
                )
            GithubLoadingContent(
                loading = state.selected == null && state.busy,
                placeholder = { TextLinePlaceholder(style, Modifier.width(60.dp), fraction = 1f) },
            ) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 })
                            .togetherWith(
                                fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 2 },
                            )
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> tween(220) }))
                    },
                    modifier = Modifier.clipToBounds(),
                    contentAlignment = Alignment.Center,
                    label = "feed-action-label",
                ) { text ->
                    Text(
                        text,
                        style = style,
                        color = GlasmioTheme.colors.content,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
    state.actionError?.let {
        Text(
            it,
            style = GlasmioTheme.textStyles.caption1,
            color = GlasmioTheme.colors.secondaryContent,
        )
    }
    GlasmioAlertDialog(
        visible = confirm,
        onDismissRequest = { confirm = false },
        title = strings.Explore.Unfollow,
        message = strings.Explore.UnfollowPrompt,
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    strings.Common.Cancel,
                    { confirm = false },
                    GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    strings.Explore.Unfollow,
                    {
                        confirm = false
                        onToggle()
                    },
                    GlasmioAlertButtonRole.Destructive,
                ),
            ),
    )
}

@Composable
private fun FeedEventDto.activityLabel(): String {
    val name = repo?.name.orEmpty()
    val strings = LocalGithubStrings.current.Explore
    return when (type) {
        "DiscussionEvent" -> strings.CreatedDiscussion(name)
        "FollowRecommendationEvent" -> strings.RecommendedUser(payload.target?.login.orEmpty())
        "RepositoryRecommendationEvent" -> strings.RecommendedRepository(name)
        else ->
            strings.FeedEvent(
                type,
                if (type == "FollowEvent") payload.target?.login.orEmpty() else name,
                if (payload.pullRequest?.merged == true || payload.pullRequest?.mergedAt != null)
                    "merged"
                else payload.action,
            )
    }
}

private fun FeedEventDto.activityIcon() =
    when {
        type == "DiscussionEvent" -> Lucide.MessagesSquare
        type == "FollowRecommendationEvent" || type == "RepositoryRecommendationEvent" ->
            Lucide.Feather
        type == "WatchEvent" -> Lucide.Star
        type == "FollowEvent" -> Lucide.UserStar
        type == "ForkEvent" -> Lucide.BookCopy
        type == "ReleaseEvent" -> Lucide.Tag
        type == "PublicEvent" -> Lucide.Megaphone
        type == "MemberEvent" -> Lucide.UserPlus
        type == "CreateEvent" -> Lucide.Package
        type == "PullRequestEvent" &&
            (payload.pullRequest?.merged == true || payload.pullRequest?.mergedAt != null) ->
            Lucide.GitMerge
        type.startsWith("PullRequest") -> Lucide.GitPullRequest
        type == "IssuesEvent" -> Lucide.CircleDot
        else -> Lucide.Activity
    }
