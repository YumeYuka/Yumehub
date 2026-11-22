package moe.yumeyuka.yumehub.feature.workdetail

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationReactionDto
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.locale.GithubStrings_Common
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommentAuthorHeader
import moe.yumeyuka.yumehub.ui.component.GithubReactionBar
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.githubRelativeTime
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler

@Composable
internal fun WorkDescriptionCard(
    entry: CollaborationItemDto,
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
) {
  val strings = LocalGithubStrings.current
  val work = strings.WorkDetail
  val navigationEnabled = LocalGithubPageHost.current?.navigationEnabled == true
  val enabled = navigationEnabled && (!state.submitting || state.sendingInBackground)
  val copy = rememberCopyText()
  val url = entry.htmlUrl ?: state.browserUrl
  val menuItems = buildList {
    if (!url.isNullOrBlank()) {
      add(GlasmioCascadingMenuItem(title = work.CopyLink, onClick = { copy(work.CopyLink, url) }))
    }
    if (!entry.body.isNullOrBlank()) {
      add(
          GlasmioCascadingMenuItem(
              title = work.CopyMarkdown,
              onClick = { copy(work.CopyMarkdown, entry.body.orEmpty()) },
          ),
      )
      if (state.canComment && !state.submissionUncertain && !state.submitting) {
        add(
            GlasmioCascadingMenuItem(
                title = work.QuoteReply,
                onClick = { viewModel.quoteBody(entry.user?.login, entry.body) },
            ),
        )
      }
    }
    if (entry.viewerCanEdit && !state.submissionUncertain && !state.submitting) {
      add(GlasmioCascadingMenuItem(title = strings.Common.Edit, onClick = viewModel::openBodyEditor))
    }
  }
  GithubReadingGroup {
    GithubCommentAuthorHeader(
        entry.user?.login,
        entry.user?.avatarUrl,
        githubRelativeTime(entry.createdAt, strings.Notifications),
        menuItems = menuItems,
        menuEnabled = enabled,
    )
    CollaborationText(
        entry.body,
        work.NoDescription,
        url,
        html = entry.bodyHtml,
        nodeId = entry.nodeId,
        inCommentCard = true,
    )
    GithubReactionBar(
        subjectKey = entry.nodeId ?: entry.number.toString(),
        sourceReactions = remember(entry.reactions) { entry.reactions.toReactionGroups() },
        canReact = entry.viewerCanReact && !entry.nodeId.isNullOrBlank(),
        enabled = enabled,
        updates = viewModel.reactionUpdates,
        onToggle = viewModel::toggleBodyReaction,
    )
  }
}

@Composable
internal fun CommentDiscussionEntry(
    comment: CollaborationCommentDto,
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
) {
  val strings = LocalGithubStrings.current.WorkDetail
  val canEdit = viewModel.canManageComment(comment)
  val canHide = viewModel.canHideComment(comment)
  val copy = rememberCopyText()
  val open = rememberGithubUriHandler()
  val navigationEnabled = LocalGithubPageHost.current?.navigationEnabled == true
  val enabled = navigationEnabled && (!state.submitting || state.sendingInBackground)
  val referenceUrl =
      remember(comment.url, comment.body, comment.user?.login, state.browserUrl) {
        referenceInNewIssueUrl(comment, state.browserUrl)
      }
  val menuItems =
      commentActionItems(
          strings = strings,
          common = LocalGithubStrings.current.Common,
          canEdit = canEdit,
          canHide = canHide,
          canCopyLink = !comment.url.isNullOrBlank(),
          canCopyMarkdown = !comment.body.isNullOrBlank(),
          canQuote = state.canComment && !state.submitting,
          canReference = referenceUrl != null,
          onCopyLink = { comment.url?.let { copy(strings.CopyLink, it) } },
          onCopyMarkdown = { copy(strings.CopyMarkdown, comment.body.orEmpty()) },
          onQuoteReply = { viewModel.quoteComment(comment) },
          onReference = { referenceUrl?.let(open) },
          onEdit = { viewModel.editComment(comment) },
          onHide = { reason -> viewModel.hideComment(comment, reason) },
          onDelete = { viewModel.requestDeleteComment(comment) },
      )
  GithubReadingGroup {
    GithubCommentAuthorHeader(
        comment.user?.login,
        comment.user?.avatarUrl,
        githubRelativeTime(comment.createdAt, LocalGithubStrings.current.Notifications),
        role =
            if (comment.isMinimized) {
              comment.minimizedReason?.let { "${strings.HiddenComment} · ${it.label(strings)}" }
                  ?: strings.HiddenComment
            } else null,
        menuItems = menuItems,
        menuEnabled = enabled,
    )
    if (
        !comment.isMinimized &&
            (!comment.body.isNullOrBlank() || !comment.bodyHtml.isNullOrBlank())
    ) {
      CollaborationText(
          comment.body,
          strings.NoBody,
          state.browserUrl,
          html = comment.bodyHtml,
          nodeId = comment.nodeId,
          inCommentCard = true,
      )
    }
    GithubReactionBar(
        subjectKey = comment.nodeId ?: "comment-${comment.id}",
        sourceReactions = remember(comment.reactions) { comment.reactions.toReactionGroups() },
        canReact = comment.viewerCanReact && !comment.nodeId.isNullOrBlank(),
        enabled = enabled,
        updates = viewModel.reactionUpdates,
    ) { content, reacted ->
      viewModel.toggleCommentReaction(comment, content, reacted)
    }
  }
}

internal fun List<CollaborationReactionDto>.toReactionGroups(): List<GithubReactionGroup> =
    mapNotNull { reaction ->
      GithubReactionContent.entries.firstOrNull { it.name == reaction.content }?.let {
        GithubReactionGroup(it, reaction.count, reaction.viewerReacted)
      }
    }

private fun commentActionItems(
    strings: GithubStrings_WorkDetail,
    common: GithubStrings_Common,
    canEdit: Boolean,
    canHide: Boolean,
    canCopyLink: Boolean,
    canCopyMarkdown: Boolean,
    canQuote: Boolean,
    canReference: Boolean,
    onCopyLink: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onQuoteReply: () -> Unit,
    onReference: () -> Unit,
    onEdit: () -> Unit,
    onHide: (CommentHideReason) -> Unit,
    onDelete: () -> Unit,
): List<GlasmioCascadingMenuItem> {
  return buildList {
    add(
        GlasmioCascadingMenuItem(
            title = strings.CopyLink,
            enabled = canCopyLink,
            onClick = onCopyLink,
        ),
    )
    add(
        GlasmioCascadingMenuItem(
            title = strings.CopyMarkdown,
            enabled = canCopyMarkdown,
            onClick = onCopyMarkdown,
        ),
    )
    add(
        GlasmioCascadingMenuItem(
            title = strings.QuoteReply,
            enabled = canQuote,
            onClick = onQuoteReply,
        ),
    )
    add(
        GlasmioCascadingMenuItem(
            title = strings.ReferenceInNewIssue,
            enabled = canReference,
            onClick = onReference,
        ),
    )
    if (canEdit) {
      add(
          GlasmioCascadingMenuItem(
              title = common.Edit,
              separatorBefore = true,
              onClick = onEdit,
          ),
      )
    }
    if (canHide) {
      add(
          GlasmioCascadingMenuItem(
              title = strings.HideComment,
              children =
                  CommentHideReason.entries.map { reason ->
                    GlasmioCascadingMenuItem(
                        title = reason.label(strings),
                        onClick = { onHide(reason) },
                    )
                  },
          ),
      )
    }
    if (canEdit) {
      add(
          GlasmioCascadingMenuItem(
              title = strings.DeleteComment,
              destructive = true,
              separatorBefore = true,
              onClick = onDelete,
          ),
      )
    }
  }
}

private fun CommentHideReason.label(strings: GithubStrings_WorkDetail): String =
    when (this) {
      CommentHideReason.OffTopic -> strings.HideReasonOffTopic
      CommentHideReason.Outdated -> strings.HideReasonOutdated
      CommentHideReason.Resolved -> strings.HideReasonResolved
      CommentHideReason.Spam -> strings.HideReasonSpam
      CommentHideReason.Abuse -> strings.HideReasonAbuse
    }

private fun String.label(strings: GithubStrings_WorkDetail): String =
    CommentHideReason.entries.firstOrNull { it.apiValue == this }?.label(strings) ?: this

private fun referenceInNewIssueUrl(
    comment: CollaborationCommentDto,
    browserUrl: String?,
): String? {
  val base =
      when {
        browserUrl == null -> return null
        "/issues/" in browserUrl -> browserUrl.substringBefore("/issues/")
        "/pull/" in browserUrl -> browserUrl.substringBefore("/pull/")
        else -> return null
      }
  val url = comment.url?.takeIf(String::isNotBlank) ?: return null
  val login = comment.user?.login?.takeIf(String::isNotBlank) ?: return null
  val quote =
      comment.body.orEmpty().lineSequence().joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
      }
  val body = buildString {
    if (quote.isNotBlank()) append(quote).append("\n\n")
    append("_Originally posted by @").append(login).append(" in ").append(url).append("_")
  }
  return "$base/issues/new?body=${Uri.encode(body)}"
}

@Composable
internal fun CollaborationDeleteCommentDialog(
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
) {
  val strings = LocalGithubStrings.current.WorkDetail
  val common = LocalGithubStrings.current.Common
  GlasmioAlertDialog(
      visible = state.deleteComment != null,
      onDismissRequest = viewModel::dismissDeleteComment,
      title = strings.DeleteCommentTitle,
      message = strings.DeleteCommentMessage,
      buttons =
          listOf(
              GlasmioAlertButtonSpec(
                  common.Cancel,
                  viewModel::dismissDeleteComment,
                  GlasmioAlertButtonRole.Cancel,
              ),
              GlasmioAlertButtonSpec(
                  strings.DeleteComment,
                  viewModel::confirmDeleteComment,
                  GlasmioAlertButtonRole.Destructive,
              ),
          ),
  )
}
