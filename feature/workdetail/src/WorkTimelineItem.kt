package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEntry
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEventKind
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubDiscussionStatusIcon
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.GithubWorkStatusIcon
import moe.yumeyuka.yumehub.ui.component.githubLabelColor
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors
import moe.yumeyuka.yumehub.ui.theme.readableForeground

private val TimelineRailInset = 16.dp
private val TimelineMarkerCenter = 26.dp
private val TimelineMarkerSize = 24.dp
private val TimelineConnectorBleed = 12.dp
private val TimelinePageIconSize = 16.dp

@Composable
internal fun WorkTimelineItem(
    item: WorkTimelineEntry,
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
    showTop: Boolean,
    showBottom: Boolean,
) {
  TimelineRow(
      showTop = showTop,
      showBottom = showBottom,
      compact = item is WorkTimelineEntry.Commit,
      marker =
          when (item) {
            is WorkTimelineEntry.Event -> ({ EventMarker(item) })
            is WorkTimelineEntry.Commit -> ({
                  Box(Modifier.size(TimelineMarkerSize), contentAlignment = Alignment.Center) {
                    GithubIcon(
                        Lucide.GitCommitHorizontal,
                        contentDescription = null,
                        size = 16.dp,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                  }
                })
            else -> null
          },
  ) {
    val strings = LocalGithubStrings.current
    when (item) {
      is WorkTimelineEntry.Comment -> CommentDiscussionEntry(item.value, state, viewModel)
      is WorkTimelineEntry.Review -> {
        val review = item.value
        GithubReadingGroup {
          DisableSelection {
            GlasmioListRow(
                title = review.user?.login ?: strings.Workspaces.UnknownAuthor,
                detail = strings.WorkDetail.ReviewState(review.state),
                leading = {
                  GithubAvatar(
                      review.user?.avatarUrl,
                      review.user?.login,
                      size = 28.dp,
                  )
                },
                systemName = null,
                showTopSeparator = false,
                trailing = {
                  TimelineBadge(reviewIcon(review.state), reviewTint(review.state))
                },
            )
          }
          if (!review.body.isNullOrBlank() || !review.bodyHtml.isNullOrBlank()) {
            CollaborationText(
                review.body,
                strings.WorkDetail.NoBody,
                state.browserUrl,
                html = review.bodyHtml,
                nodeId = review.nodeId,
            )
          }
        }
      }
      is WorkTimelineEntry.Commit -> {
        val commit = item.value
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 36.dp)
                .then(timelineLinkModifier(commit.url))
                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          GithubAvatar(commit.avatarUrl, commit.login, size = 18.dp)
          Text(
              text = commit.title,
              style = GlasmioTheme.textStyles.footnote,
              fontFamily = FontFamily.Monospace,
              letterSpacing = 0.sp,
              color = GlasmioTheme.colors.secondaryContent,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
      is WorkTimelineEntry.Event -> TimelineEventRow(item, strings.WorkDetail)
    }
  }
}

@Composable
internal fun WorkTimelinePlaceholder(showTop: Boolean, showBottom: Boolean) {
  TimelineRow(
      showTop = showTop,
      showBottom = showBottom,
  ) {
    GithubReadingGroup {
      GithubMarkdownPlaceholder(
          Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
          compact = true,
      )
    }
  }
}

@Composable
internal fun WorkTimelinePageControl(
    loading: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
  Box(
      Modifier.size(width = 48.dp, height = 36.dp)
          .clickable(
              interactionSource = null,
              indication = null,
              enabled = enabled,
              role = Role.Button,
              onClick = onClick,
          ),
  ) {
    val tint = GlasmioTheme.colors.secondaryContent
    GithubIcon(
        Lucide.UnfoldVertical,
        modifier =
            Modifier.align(Alignment.CenterStart)
                .padding(start = TimelineRailInset - TimelinePageIconSize / 2),
        contentDescription = label,
        size = TimelinePageIconSize,
        tint = if (loading || !enabled) tint.copy(alpha = 0.45f) else tint,
    )
  }
}

@Composable
private fun TimelineRow(
    showTop: Boolean,
    showBottom: Boolean,
    compact: Boolean = false,
    marker: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
  val lineColor = GlasmioTheme.colors.secondaryContent.copy(alpha = 0.18f)
  val markerHalfSize = TimelineMarkerSize / 2
  Box(
      Modifier.fillMaxWidth().drawBehind {
        val connectorEnd = size.height + TimelineConnectorBleed.roundToPx()
        if (marker != null) {
          val center = if (compact) size.height / 2 else TimelineMarkerCenter.toPx()
          val clearance = (markerHalfSize + 2.dp).toPx()
          if (showTop) rail(lineColor, 0f, center - clearance)
          if (showBottom) rail(lineColor, center + clearance, connectorEnd)
        } else if (showBottom) {
          rail(lineColor, size.height, connectorEnd)
        }
      },
  ) {
    if (marker == null) {
      content()
    } else {
      Box(Modifier.padding(start = 36.dp)) { content() }
      Box(
          Modifier.align(if (compact) Alignment.CenterStart else Alignment.TopStart)
              .padding(
                  start = TimelineRailInset - markerHalfSize,
                  top = if (compact) 0.dp else TimelineMarkerCenter - markerHalfSize,
              ),
      ) {
        marker()
      }
    }
  }
}

private fun DrawScope.rail(color: Color, from: Float, to: Float) {
  if (from >= to) return
  val inset = TimelineRailInset.roundToPx().toFloat()
  val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
  drawLine(color, Offset(x, from), Offset(x, to), 2.dp.toPx(), cap = StrokeCap.Butt)
}

@Composable
private fun EventMarker(item: WorkTimelineEntry.Event) {
  val colors = GlasmioTheme.colors
  val tint = eventTint(item)
  val neutral = tint == colors.secondaryContent
  TimelineBadge(
      icon = eventIcon(item.kind),
      tint = if (neutral) tint else Color.White,
      background =
          if (neutral) colors.groupedBackground else readableForeground(tint, Color.White, Color.Black),
      border = false,
  )
}

@Composable
private fun TimelineEventRow(
    item: WorkTimelineEntry.Event,
    strings: GithubStrings_WorkDetail,
) {
  val author = item.actor?.takeIf(String::isNotBlank)
  val text =
      remember(item, strings, author) {
        buildAnnotatedString {
          if (author != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(author) }
            append(" ")
          }
          val previousTitle = item.subject?.takeIf(String::isNotBlank)
          when {
            item.kind == WorkTimelineEventKind.RenamedTitle && previousTitle != null -> {
              val (before, after) = strings.TimelineTitleChange(item.detail)
              append(before)
              withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(previousTitle)
              }
              append(after)
            }
            item.kind == WorkTimelineEventKind.HeadRefForcePushed ||
                item.kind == WorkTimelineEventKind.BaseRefForcePushed ->
                append(
                    strings.TimelineForcePush(
                        item.kind == WorkTimelineEventKind.BaseRefForcePushed,
                        item.forcePushedRefName,
                        item.subject,
                        item.detail,
                    )
                )
            else -> append(strings.TimelineEvent(item.kind.name, item.subject, item.detail))
          }
        }
      }
  Column(
      Modifier.fillMaxWidth()
          .heightIn(min = 52.dp)
          .padding(top = 17.dp, bottom = if (item.target == null) 12.dp else 0.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        text = text,
        style = GlasmioTheme.textStyles.footnote,
        letterSpacing = 0.sp,
        color = GlasmioTheme.colors.content,
    )
    item.target?.let { TimelineTargetCard(it) }
  }
}

@Composable
private fun TimelineTargetCard(target: WorkTimelineEntry.Target) {
  val colors = GlasmioTheme.colors
  GithubReadingGroup(modifier = timelineLinkModifier(target.url)) {
    DisableSelection {
      GlasmioListRow(
          title = target.title,
          detail = target.identifier,
          leading = {
            when (target.kind) {
              WorkTimelineEntry.Target.Kind.Issue,
              WorkTimelineEntry.Target.Kind.PullRequest ->
                  GithubWorkStatusIcon(
                      kind =
                          if (target.kind == WorkTimelineEntry.Target.Kind.Issue) {
                            CollaborationKind.Issue
                          } else CollaborationKind.PullRequest,
                      state = if (target.closed) "CLOSED" else "OPEN",
                      draft = target.draft,
                      merged = target.merged,
                      stateReason = target.stateReason,
                      size = 20.dp,
                  )
              WorkTimelineEntry.Target.Kind.Discussion ->
                  GithubDiscussionStatusIcon(target.closed, size = 20.dp)
              WorkTimelineEntry.Target.Kind.Project ->
                  GithubIcon(
                      Lucide.FolderKanban,
                      contentDescription = null,
                      size = 20.dp,
                      tint = if (target.closed) colors.secondaryContent else colors.accent,
                  )
              WorkTimelineEntry.Target.Kind.Commit ->
                  GithubIcon(
                      Lucide.GitCommitHorizontal,
                      contentDescription = null,
                      size = 20.dp,
                      tint = colors.secondaryContent,
                  )
            }
          },
          showTopSeparator = false,
          showDisclosure = false,
      )
    }
  }
}

@Composable
private fun timelineLinkModifier(url: String?): Modifier {
  if (url.isNullOrBlank()) return Modifier
  val open = rememberGithubUriHandler()
  val host = requireNotNull(LocalGithubPageHost.current)
  return Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) {
    if (host.navigationEnabled) open(url)
  }
}

@Composable
private fun TimelineBadge(
    icon: ImageVector,
    tint: Color,
    background: Color? = null,
    border: Boolean = true,
) {
  Box(
      Modifier.size(TimelineMarkerSize)
          .clip(CircleShape)
          .background(background ?: GlasmioTheme.colors.elevatedBackground)
          .then(
              if (border) Modifier.border(1.dp, GlasmioTheme.colors.subtleStroke, CircleShape)
              else Modifier,
          ),
      contentAlignment = Alignment.Center,
  ) {
    GithubIcon(icon, contentDescription = null, size = 14.dp, tint = tint)
  }
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
    WorkTimelineEventKind.Unlabeled -> {
      val color = item.labelColor
      if (color == null) GlasmioTheme.colors.secondaryContent else githubLabelColor(color)
    }
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
