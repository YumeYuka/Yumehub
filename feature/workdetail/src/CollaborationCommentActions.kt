package moe.yumeyuka.yumehub.feature.workdetail

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.locale.GithubStrings_Common
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommentAuthorHeader
import moe.yumeyuka.yumehub.ui.component.GithubMutationRecoveryRow
import moe.yumeyuka.yumehub.ui.component.GithubReactionBar
import moe.yumeyuka.yumehub.ui.component.GithubReadingFooter
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.githubRelativeTime
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler

@Composable
internal fun WorkDescriptionContent(
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
            add(
                GlasmioCascadingMenuItem(
                    title = work.CopyLink,
                    onClick = { copy(work.CopyLink, url) },
                ),
            )
        }
        if (!entry.body.isNullOrBlank()) {
            add(
                GlasmioCascadingMenuItem(
                    title = work.CopyMarkdown,
                    onClick = { copy(work.CopyMarkdown, entry.body.orEmpty()) },
                ),
            )
            if (state.canComment && !state.commentUncertain && !state.submitting) {
                add(
                    GlasmioCascadingMenuItem(
                        title = work.QuoteReply,
                        onClick = { viewModel.quoteBody(entry.user?.login, entry.body) },
                    ),
                )
            }
        }
        if (entry.viewerCanEdit && !state.unknownField("body") && !state.submitting) {
            add(
                GlasmioCascadingMenuItem(
                    title = strings.Common.Edit,
                    onClick = viewModel::openBodyEditor,
                ),
            )
        }
    }
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
    val subjectId = entry.nodeId
    if (subjectId != null)
        GithubReadingFooter {
            GithubReactionBar(
                subjectKey = subjectId,
                sourceReactions = entry.reactions,
                canReact = entry.viewerCanReact,
                enabled = enabled,
                updates = viewModel.reactionUpdates,
            ) { content, reacted ->
                if (navigationEnabled) viewModel.toggleBodyReaction(content, reacted)
            }
        }
}

@Composable
internal fun CommentDiscussionEntry(
    comment: CollaborationCommentDto,
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
) {
    val write =
        comment.nodeId?.let { id ->
            key(viewModel, id) {
                remember { viewModel.commentWrites(id) }.collectAsStateWithLifecycle(null).value
            }
        }
    if (write?.deletion?.result != null) return
    val shownComment = remember(write, comment) { write?.apply(comment) ?: comment }
    val strings = LocalGithubStrings.current.WorkDetail
    val mutable = write?.busy != true && write?.uncertain != true
    val canEdit = mutable && viewModel.canManageComment(shownComment)
    val canHide = mutable && viewModel.canHideComment(shownComment)
    val copy = rememberCopyText()
    val open = rememberGithubUriHandler()
    val navigationEnabled = LocalGithubPageHost.current?.navigationEnabled == true
    val enabled = navigationEnabled && (!state.submitting || state.sendingInBackground)
    val referenceUrl =
        remember(shownComment.url, shownComment.body, shownComment.user?.login, state.browserUrl) {
            referenceInNewIssueUrl(shownComment, state.browserUrl)
        }
    val menuItems =
        commentActionItems(
            strings = strings,
            common = LocalGithubStrings.current.Common,
            canEdit = canEdit,
            canHide = canHide,
            canCopyLink = !shownComment.url.isNullOrBlank(),
            canCopyMarkdown = !shownComment.body.isNullOrBlank(),
            canQuote = state.canComment && !state.submitting,
            canReference = referenceUrl != null,
            onCopyLink = { shownComment.url?.let { copy(strings.CopyLink, it) } },
            onCopyMarkdown = { copy(strings.CopyMarkdown, shownComment.body.orEmpty()) },
            onQuoteReply = { viewModel.quoteComment(shownComment) },
            onReference = { referenceUrl?.let(open) },
            onEdit = { viewModel.editComment(shownComment) },
            onHide = { reason -> viewModel.hideComment(shownComment, reason) },
            onDelete = { viewModel.requestDeleteComment(shownComment) },
        )
    val revision =
        if (shownComment.isMinimized) null
        else
            shownComment.bodyHtml?.takeIf(String::isNotBlank)
                ?: shownComment.body?.takeIf(String::isNotBlank)
    GithubReadingGroup(documentKey = revision) {
        val common = LocalGithubStrings.current.Common
        val editMarker = write?.editOperationId
        when {
            write?.busy == true -> GlasmioListRow(title = common.MutationPending)
            editMarker != null ->
                GithubMutationRecoveryRow(
                    editMarker,
                    common.MutationUncertain,
                    viewModel::acknowledgeOperation,
                )
            write?.uncertain == true -> GlasmioListRow(title = common.MutationUncertain)
            write?.edit?.phase == WritePhase.REJECTED ->
                GlasmioListRow(title = common.MutationNotSubmitted)
        }
        GithubCommentAuthorHeader(
            shownComment.user?.login,
            shownComment.user?.avatarUrl,
            githubRelativeTime(shownComment.createdAt, LocalGithubStrings.current.Notifications),
            role =
                if (shownComment.isMinimized) {
                    shownComment.minimizedReason?.let {
                        "${strings.HiddenComment} · ${it.label(strings)}"
                    } ?: strings.HiddenComment
                } else null,
            menuItems = menuItems,
            menuEnabled = enabled,
        )
        if (
            !shownComment.isMinimized &&
                (!shownComment.body.isNullOrBlank() || !shownComment.bodyHtml.isNullOrBlank())
        ) {
            CollaborationText(
                shownComment.body,
                strings.NoBody,
                state.browserUrl,
                html = shownComment.bodyHtml,
                nodeId = shownComment.nodeId,
                inCommentCard = true,
            )
        }
        val subjectId = shownComment.nodeId
        if (!shownComment.isMinimized && subjectId != null)
            GithubReadingFooter {
                GithubReactionBar(
                    subjectKey = subjectId,
                    sourceReactions = shownComment.reactions,
                    canReact = shownComment.viewerCanReact,
                    enabled = enabled,
                    updates = viewModel.reactionUpdates,
                ) { content, reacted ->
                    if (navigationEnabled) viewModel.toggleReaction(shownComment, content, reacted)
                }
            }
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
