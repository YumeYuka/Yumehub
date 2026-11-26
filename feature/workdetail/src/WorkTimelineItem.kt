package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEntry
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEventKind
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEventKind.*
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

private val TimelineIconSize = 20.dp
private val TimelineIconGap = 12.dp
private val TimelineTextOffset = TimelineIconSize + TimelineIconGap

private fun WorkTimelineEventKind.isCoreTimelineEvent(): Boolean =
    when (this) {
      Locked,
      Unlocked,
      CommentDeleted,
      UserBlocked -> true
      else -> false
    }

internal fun groupWorkActivities(entries: List<WorkTimelineEntry>): List<List<WorkTimelineEntry>> =
    buildList {
      var start = 0
      while (start < entries.size) {
        val first = entries[start]
        var end = start + 1
        val family = first.activityFamily()
        if (family != null) {
          // 评论与评审正文限制分组长度；连续事件或提交使用同一张紧凑卡片。
          while (
              end < entries.size &&
                  (first is WorkTimelineEntry.Commit ||
                      first is WorkTimelineEntry.Event ||
                      end - start < 6) &&
                  entries[end].activityFamily() == family
          ) {
            end++
          }
        }
        // 先按原始顺序确定边界，避免隐藏事件后将两组提交合成一组。
        val group = entries.subList(start, end)
        if (first is WorkTimelineEntry.Event) {
          val visible = group.filter { (it as WorkTimelineEntry.Event).kind.isCoreTimelineEvent() }
          if (visible.isNotEmpty()) add(visible)
        } else {
          add(group)
        }
        start = end
      }
    }

private fun WorkTimelineEntry.activityFamily() =
    when (this) {
      is WorkTimelineEntry.Commit -> WorkTimelineEntry.Commit::class
      is WorkTimelineEntry.Comment -> null
      is WorkTimelineEntry.Review -> WorkTimelineEntry.Review::class
      is WorkTimelineEntry.Event -> WorkTimelineEntry.Event::class
    }


@Composable
internal fun WorkActivityCard(
    entries: List<WorkTimelineEntry>,
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
    onOpenCommitGroup: ((List<RepositoryActivityEntry>) -> Unit)? = null,
    onOpenCommit: ((String) -> Unit)? = null,
) {
  val first = entries.first()

  val rows: @Composable () -> Unit = {
    entries.forEachIndexed { index, item ->
      key(item.key) {
        val showSeparator = index > 0
        when (item) {
          is WorkTimelineEntry.Event ->
              TimelineEventRow(item, LocalGithubStrings.current.WorkDetail, showSeparator)
          is WorkTimelineEntry.Commit -> CommitActivityRow(item.value, showSeparator, onOpenCommit)
          is WorkTimelineEntry.Comment -> CommentDiscussionEntry(item.value, state, viewModel)
          is WorkTimelineEntry.Review ->
              ReviewActivityRow(
                  item.value,
                  showTopSeparator = showSeparator,
                  pageUrl = state.browserUrl,
                  onOpenCommit = onOpenCommit,
              )
        }
      }
    }
  }
  when (first) {
    is WorkTimelineEntry.Comment -> rows()
    is WorkTimelineEntry.Review -> GithubReadingGroup { rows() }
    else -> GlasmioGroupedList {
      if (first is WorkTimelineEntry.Commit && entries.size > 1 && onOpenCommitGroup != null) {
        val strings = LocalGithubStrings.current
        val author = first.value.login?.takeIf(String::isNotBlank) ?: strings.Workspaces.UnknownAuthor
        TimelineCompactActivityRow(
            title = strings.WorkDetail.TimelineCommitsPushed(author, entries.size),
            showTopSeparator = false,
            leading = { ActivityIcon(Lucide.GitCommitHorizontal) },
            onClick = {
              onOpenCommitGroup(entries.mapNotNull { (it as? WorkTimelineEntry.Commit)?.value })
            },
        )
      } else {
        rows()
      }
    }
  }
}


@Composable
private fun ReviewActivityRow(
    review: PullRequestReviewDto,
    showTopSeparator: Boolean,
    pageUrl: String?,
    onOpenCommit: ((String) -> Unit)?,
) {
  val strings = LocalGithubStrings.current
  val author = review.user?.login ?: strings.Workspaces.UnknownAuthor
  val summary =
      strings.WorkDetail.TimelineReviewAction(
          author,
          strings.WorkDetail.ReviewState(review.state),
      )
  val hasBody = !review.body.isNullOrBlank() || !review.bodyHtml.isNullOrBlank()
  TimelineCompactActivityRow(
      title = summary,
      showTopSeparator = showTopSeparator,
      leading = { ActivityIcon(reviewIcon(review.state), reviewTint(review.state)) },
      onClick =
          if (review.state == "APPROVED") commitActivityLink(review.commitUrl, onOpenCommit)
          else null,
  )
  if (hasBody) {
    CollaborationText(
        review.body,
        strings.WorkDetail.NoBody,
        pageUrl,
        html = review.bodyHtml,
        nodeId = review.nodeId,
        inCommentCard = true,
    )
  }
}



@Composable
private fun CommitActivityRow(
    commit: RepositoryActivityEntry,
    showTopSeparator: Boolean,
    onOpenCommit: ((String) -> Unit)?,
) {
  TimelineCompactActivityRow(
      title = commit.title,
      showTopSeparator = showTopSeparator,
      leading = { ActivityIcon(Lucide.GitCommitHorizontal) },
      onClick = commitActivityLink(commit.url, onOpenCommit),
  )
}

@Composable
private fun TimelineCompactActivityRow(
    title: String,
    showTopSeparator: Boolean,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
  TimelineActivityRow(
      showTopSeparator = showTopSeparator,
      leading = leading,
      onClick = onClick,
      trailing = trailing,
  ) { modifier ->
    Text(
        title,
        modifier = modifier,
        textAlign = timelineTextAlignment(),
        style = GlasmioTheme.textStyles.footnote,
        color = GlasmioTheme.colors.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun TimelineActivityRow(
    showTopSeparator: Boolean,
    leading: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
  val colors = GlasmioTheme.colors
  val inset = GlasmioListRowDefaults.horizontalPadding
  val enabled = onClick != null && requireNotNull(LocalGithubPageHost.current).navigationEnabled
  DisableSelection {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .glasmioGroupedListSeparator(colors.separator, showTopSeparator, inset)
            .then(
                if (onClick != null) {
                  Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.width(TimelineTextOffset), contentAlignment = Alignment.CenterStart) {
        leading()
      }
      content(Modifier.weight(1f))
      if (trailing != null) Box(Modifier.padding(start = TimelineIconGap)) { trailing() }
    }
  }
}

@Composable
private fun timelineTextAlignment(): TextAlign =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left


@Composable
private fun ActivityIcon(
    icon: ImageVector,
    tint: Color = GlasmioTheme.colors.secondaryContent,
    size: Dp = TimelineIconSize,
) {
  GithubIcon(icon, contentDescription = null, size = size, tint = tint)
}

@Composable
internal fun WorkTimelinePlaceholder() {
  ListRowPlaceholder()
}

@Composable
private fun TimelineEventRow(
    item: WorkTimelineEntry.Event,
    strings: GithubStrings_WorkDetail,
    showTopSeparator: Boolean,
) {
  val author = item.actor?.takeIf(String::isNotBlank)
  val text =
      remember(item, strings, author) {
        buildAnnotatedString {
          if (author != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(author) }
            append(" ")
          }
          append(strings.TimelineEvent(item.kind.name, item.subject, null))
        }
      }
  TimelineActivityRow(
      showTopSeparator = showTopSeparator,
      leading = { ActivityIcon(eventIcon(item.kind), eventTint(item)) },
  ) { modifier ->
    Text(
        text = text,
        modifier = modifier,
        textAlign = timelineTextAlignment(),
        style = GlasmioTheme.textStyles.footnote,
        color = GlasmioTheme.colors.content,
    )
  }
}


@Composable
private fun activityLink(url: String?): (() -> Unit)? {
  if (url.isNullOrBlank()) return null
  val open = rememberGithubUriHandler()
  val host = requireNotNull(LocalGithubPageHost.current)
  return { if (host.navigationEnabled) open(url) }
}

@Composable
private fun commitActivityLink(url: String?, onOpenCommit: ((String) -> Unit)?): (() -> Unit)? {
  val oid = url?.substringAfterLast('/')?.takeIf(String::isNotBlank)
  if (onOpenCommit == null || oid == null) return activityLink(url)
  val host = requireNotNull(LocalGithubPageHost.current)
  return { if (host.navigationEnabled) onOpenCommit(oid) }
}

private fun reviewIcon(state: String?): ImageVector =
    when (state) {
      "APPROVED" -> Lucide.CircleCheck
      "CHANGES_REQUESTED" -> Lucide.CircleSlash
      else -> Lucide.MessageCircleCheck
    }

@Composable
private fun reviewTint(state: String?): Color =
    when (state) {
      "APPROVED" -> GithubWorkColors.open
      "CHANGES_REQUESTED" -> GithubWorkColors.closed
      else -> GlasmioTheme.colors.secondaryContent
    }

private fun eventIcon(kind: WorkTimelineEventKind): ImageVector =
    when (kind) {
      WorkTimelineEventKind.RenamedTitle -> Lucide.Pencil
      WorkTimelineEventKind.Closed -> Lucide.CircleCheck
      WorkTimelineEventKind.Reopened -> Lucide.CircleDot
      WorkTimelineEventKind.Merged -> Lucide.GitMerge
      WorkTimelineEventKind.Labeled,
      WorkTimelineEventKind.Unlabeled -> Lucide.Tag
      WorkTimelineEventKind.Assigned -> Lucide.UserRound
      WorkTimelineEventKind.Unassigned -> Lucide.UserRoundMinus
      WorkTimelineEventKind.Locked -> Lucide.Lock
      WorkTimelineEventKind.Unlocked -> Lucide.LockOpen
      WorkTimelineEventKind.Milestoned -> Lucide.Milestone
      WorkTimelineEventKind.Demilestoned -> Lucide.FlagOff
      WorkTimelineEventKind.CrossReferenced,
      WorkTimelineEventKind.Referenced -> Lucide.ExternalLink
      WorkTimelineEventKind.Connected -> Lucide.Link2
      WorkTimelineEventKind.HeadRefDeleted,
      WorkTimelineEventKind.HeadRefRestored,
      WorkTimelineEventKind.BaseRefChanged,
      WorkTimelineEventKind.AutomaticBaseChanged -> Lucide.GitBranch
      WorkTimelineEventKind.ReviewRequested -> Lucide.UsersRound
      WorkTimelineEventKind.ReviewRequestRemoved -> Lucide.UserMinus
      WorkTimelineEventKind.ReviewDismissed -> Lucide.CircleSlash
      WorkTimelineEventKind.ReadyForReview -> Lucide.Eye
      WorkTimelineEventKind.ConvertedToDraft -> Lucide.GitPullRequestDraft
      WorkTimelineEventKind.CommentDeleted -> Lucide.Trash2
      WorkTimelineEventKind.Deployed -> Lucide.Rocket
      WorkTimelineEventKind.HeadRefForcePushed,
      WorkTimelineEventKind.BaseRefForcePushed -> Lucide.GitCommitHorizontal
      WorkTimelineEventKind.MarkedAsDuplicate -> Lucide.Copy
      WorkTimelineEventKind.DeploymentEnvironmentChanged -> Lucide.Server
      WorkTimelineEventKind.AutoMergeEnabled,
      WorkTimelineEventKind.AutoMergeDisabled,
      WorkTimelineEventKind.AutoRebaseEnabled,
      WorkTimelineEventKind.AutoSquashEnabled -> Lucide.GitMerge
      WorkTimelineEventKind.UserBlocked -> Lucide.UserX
      WorkTimelineEventKind.AddedToMergeQueue -> Lucide.ListOrdered
      WorkTimelineEventKind.RemovedFromMergeQueue -> Lucide.ListX
      WorkTimelineEventKind.Disconnected -> Lucide.Link2Off
      WorkTimelineEventKind.AddedToProject,
      WorkTimelineEventKind.RemovedFromProject -> Lucide.FolderKanban
      WorkTimelineEventKind.MovedColumnsInProject -> Lucide.Columns3
      WorkTimelineEventKind.Pinned -> Lucide.Pin
      WorkTimelineEventKind.Unpinned -> Lucide.PinOff
      WorkTimelineEventKind.Transferred -> Lucide.ArrowRightLeft
      WorkTimelineEventKind.ConvertedToDiscussion -> Lucide.MessageSquare
      WorkTimelineEventKind.SubIssueAdded -> Lucide.ListPlus
      WorkTimelineEventKind.SubIssueRemoved -> Lucide.ListMinus
      WorkTimelineEventKind.CopilotWorkStarted,
      WorkTimelineEventKind.CopilotWorkFinished -> Lucide.Sparkles
      WorkTimelineEventKind.CopilotWorkFinishedFailure -> Lucide.CircleSlash
    }

@Composable
private fun eventTint(item: WorkTimelineEntry.Event): Color {
  val kind = item.kind
  return when (kind) {
    WorkTimelineEventKind.Labeled,
    WorkTimelineEventKind.Unlabeled -> GlasmioTheme.colors.secondaryContent
    WorkTimelineEventKind.Closed,
    WorkTimelineEventKind.ReviewRequestRemoved,
    WorkTimelineEventKind.ReviewDismissed,
    WorkTimelineEventKind.CommentDeleted,
    WorkTimelineEventKind.HeadRefDeleted,
    WorkTimelineEventKind.UserBlocked,
    WorkTimelineEventKind.CopilotWorkFinishedFailure -> GithubWorkColors.closed
    WorkTimelineEventKind.Reopened,
    WorkTimelineEventKind.HeadRefRestored,
    WorkTimelineEventKind.ReadyForReview -> GithubWorkColors.open
    WorkTimelineEventKind.Merged,
    WorkTimelineEventKind.AutoMergeEnabled,
    WorkTimelineEventKind.AutoRebaseEnabled,
    WorkTimelineEventKind.AutoSquashEnabled,
    WorkTimelineEventKind.AddedToMergeQueue,
    WorkTimelineEventKind.CopilotWorkFinished -> GithubWorkColors.merged
    WorkTimelineEventKind.ConvertedToDraft,
    WorkTimelineEventKind.HeadRefForcePushed,
    WorkTimelineEventKind.BaseRefForcePushed,
    WorkTimelineEventKind.AutoMergeDisabled -> GithubWorkColors.draft
    WorkTimelineEventKind.CrossReferenced,
    WorkTimelineEventKind.Referenced,
    WorkTimelineEventKind.Connected,
    WorkTimelineEventKind.Disconnected,
    WorkTimelineEventKind.BaseRefChanged,
    WorkTimelineEventKind.AutomaticBaseChanged,
    WorkTimelineEventKind.Deployed,
    WorkTimelineEventKind.ConvertedToDiscussion -> GithubWorkColors.branchForeground
    WorkTimelineEventKind.ReviewRequested,
    WorkTimelineEventKind.Milestoned,
    WorkTimelineEventKind.Demilestoned,
    WorkTimelineEventKind.DeploymentEnvironmentChanged,
    WorkTimelineEventKind.CopilotWorkStarted -> GlasmioTheme.colors.accent
    else -> GlasmioTheme.colors.secondaryContent
  }
}
