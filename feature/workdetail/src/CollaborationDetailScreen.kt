package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.ThreadWindowState
import moe.yumeyuka.yumehub.data.repository.WorkTimelineEntry
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubDiffFileCard
import moe.yumeyuka.yumehub.ui.component.GithubMarkdown
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.githubPagingItems
import moe.yumeyuka.yumehub.ui.component.GithubMutationRecoveryRow
import moe.yumeyuka.yumehub.ui.component.githubThreadGroupedItems
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.OrganizationRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.readingItem

internal fun LazyListScope.CollaborationDetailScreen(
    state: CollaborationDetailUiState,
    entries: LazyPagingItems<CollaborationEntry>?,
    entrySlots: GithubPagingSlots<CollaborationEntry>?,
    timeline: ThreadWindowState<WorkTimelineEntry>?,
    activityGroups: List<List<WorkTimelineEntry>>,
    viewModel: CollaborationDetailViewModel,
    onOpenInBrowser: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenCommits: () -> Unit,
    onOpenCommitGroup: (List<RepositoryActivityEntry>) -> Unit,
    onOpenCommit: (String) -> Unit,
    onStatus: (WorkStatusPanel) -> Unit,
) {
  val entry = state.item
  if (entry == null) {
    if (state.error != null) return
    if (state.section == CollaborationSection.Discussion) {
      readingItem(key = "collaboration-detail-description") {
        GithubReadingGroup {
          GithubMarkdownPlaceholder(
              Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              compact = true,
          )
        }
      }
    }
    return
  }
  if (state.submitting) item(key = "collaboration-submission-progress") {
    GlasmioListRow(title = LocalGithubStrings.current.Common.MutationPending)
  }
  if (state.uncertainOperations.isNotEmpty()) {
    item(key = "collaboration-submission-recovery") {
      val strings = LocalGithubStrings.current
      GithubSection(strings.WorkDetail.RecoveryTitle) {
        GlasmioListRow(
            title = strings.WorkDetail.SubmissionUncertain,
            subtitle = strings.WorkDetail.RecoveryMessage,
            systemName = null,
        )
        GlasmioListRow(
            title = strings.Common.MutationReconcile,
            systemName = null,
            onClick = viewModel::refresh,
        )
        state.uncertainOperations.forEach { op ->
          GithubMutationRecoveryRow(op.operationId, viewModel.operationLabel(op), viewModel::acknowledgeOperation)
        }
      }
    }
  }

  if (state.section == CollaborationSection.Discussion) {
    readingItem(key = "collaboration-detail-description") {
      WorkDescriptionCard(entry, state, viewModel)
    }
    if (viewModel.kind == CollaborationKind.PullRequest) {
      item(key = "collaboration-discussion-summary") {
        val host = requireNotNull(LocalGithubPageHost.current)
        WorkDiscussionSummary(
            entry = entry,
            enabled = host.navigationEnabled,
            onStatus = onStatus,
            onOpenFiles = { if (host.navigationEnabled) onOpenFiles() },
            onOpenCommits = { if (host.navigationEnabled) onOpenCommits() },
        )
      }
    }
    item(key = "collaboration-activity-heading") {
      GithubSectionHeader(LocalGithubStrings.current.WorkDetail.Activity)
    }
    if (timeline != null) {
      githubThreadGroupedItems(
          state = timeline,
          groups = activityGroups,
          key = { it.first().key },
          load = viewModel.timeline::load,
          placeholder = { WorkTimelinePlaceholder() },
      ) { group ->
        val host = requireNotNull(LocalGithubPageHost.current)
        WorkActivityCard(
            group,
            state,
            viewModel,
            onOpenCommitGroup = onOpenCommitGroup,
            onOpenCommit = { oid ->
              if (host.navigationEnabled) onOpenCommit(oid)
            },
        )
      }
    }
    return
  } else if (state.section != CollaborationSection.Files) {
    item(key = "collaboration-section-navigation") {
      val strings = LocalGithubStrings.current.WorkDetail
      val host = requireNotNull(LocalGithubPageHost.current)
      GlasmioGroupedList {
        GlasmioListRow(
            title = state.section.label(strings),
            detail = strings.Discussion,
            systemName = null,
            onClick = {
              if (host.navigationEnabled) viewModel.selectSection(CollaborationSection.Discussion)
            },
        )
      }
    }
  }

  if (entrySlots == null || entries == null) return
  val revalidationFlow =
      viewModel.entriesRevalidation(state.section, state.item?.head?.sha)
  if (revalidationFlow != null) {
    item(key = "work-section-sync-failed:${state.section.name}") {
      val syncState by revalidationFlow.collectAsStateWithLifecycle()
      if (syncState is RevalidationState.Failed) {
        val common = LocalGithubStrings.current.Common
        GlasmioGroupedList {
          GlasmioListRow(
              title = common.CacheSyncFailed,
              detail = common.Retry,
              showDisclosure = false,
              onClick = { viewModel.retryEntriesRevalidation(state.section, state.item?.head?.sha) },
          )
        }
      }
    }
  }
  githubPagingItems(
      entrySlots,
      contentType = "work-${state.section}",
      reading =
          state.section in
              setOf(
                  CollaborationSection.Reviews,
                  CollaborationSection.Files,
              ),
  ) { item ->
    when (item) {
      is CollaborationEntry.Comment -> CommentDiscussionEntry(item.value, state, viewModel)
      is CollaborationEntry.Review ->
          DiscussionEntry(
              item.value.user?.login,
              item.value.submittedAt,
              item.value.body,
              state.browserUrl,
              LocalGithubStrings.current.WorkDetail.ReviewState(item.value.state),
              html = item.value.bodyHtml,
              nodeId = item.value.nodeId,
          )
      is CollaborationEntry.File ->
          if (item.headSha == state.item.head?.sha) {
            FileEntry(item.value, state)
          }
      is CollaborationEntry.Commit -> {
        val open = moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler()
        val host = requireNotNull(LocalGithubPageHost.current)
        GlasmioGroupedList {
          GlasmioListRow(
              title = item.value.title,
              subtitle = item.value.subtitle,
              systemName = null,
              onClick =
                  item.value.url?.let { url ->
                    { if (host.navigationEnabled) open(url) }
                  },
          )
        }
      }
      null ->
          when (state.section) {
            CollaborationSection.Files -> GithubDiffFileCard(null, Modifier.fillMaxWidth())
            CollaborationSection.Reviews ->
                GithubReadingGroup {
                  GithubMarkdownPlaceholder(
                      Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                      compact = true,
                  )
                }
            else ->
                GithubLoadingCard(
                    loading = true,
                    placeholder = { OrganizationRowPlaceholder() },
                ) {}
          }
    }
  }
}

@Composable
private fun DiscussionEntry(
    author: String?,
    date: String?,
    body: String?,
    pageUrl: String?,
    status: String? = null,
    html: String? = null,
    nodeId: String? = null,
) {
  GithubReadingGroup {
    DisableSelection {
      GlasmioListRow(
          title = author ?: LocalGithubStrings.current.Workspaces.UnknownAuthor,
          subtitle = date?.take(10),
          detail = status,
          systemName = null,
      )
    }
    if (!body.isNullOrBlank() || !html.isNullOrBlank()) {
      CollaborationText(
          body,
          LocalGithubStrings.current.WorkDetail.NoBody,
          pageUrl,
          html = html,
          nodeId = nodeId,
      )
    }
  }
}

@Composable
private fun FileEntry(
    file: PullRequestFileDto,
    state: CollaborationDetailUiState,
) {
  GithubDiffFileCard(file = file, modifier = Modifier.fillMaxWidth()) {
    state.lastLineComment
        ?.takeIf { it.path == file.filename }
        ?.let { posted ->
          DisableSelection {
            GlasmioListRow(
                title = LocalGithubStrings.current.WorkDetail.CommentPosted,
                subtitle =
                    posted.line?.let {
                      LocalGithubStrings.current.WorkDetail.LineNumber(it)
                    },
                systemName = null,
            )
          }
          CollaborationText(
              posted.body,
              LocalGithubStrings.current.WorkDetail.NoBody,
              posted.htmlUrl ?: state.browserUrl,
              html = posted.bodyHtml,
              nodeId = posted.nodeId,
          )
        }
  }
}

@Composable
internal fun CollaborationText(
    body: String?,
    empty: String,
    pageUrl: String?,
    html: String? = null,
    nodeId: String? = null,
    inCommentCard: Boolean = false,
) {
  val theme = GlasmioTheme
  val modifier =
      Modifier.fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = if (inCommentCard) 6.dp else 10.dp)
  if (body.isNullOrBlank() && html.isNullOrBlank()) {
    DisableSelection {
      Text(
          text = empty,
          modifier = modifier,
          style = theme.textStyles.body,
          color = theme.colors.content,
      )
    }
  } else {
    GithubMarkdown(
        body.orEmpty(),
        modifier,
        pageUrl = pageUrl,
        html = html,
        nodeId = nodeId,
        // 无节点的正文按原文走官方 POST，避免把评论 URL 解析成工作项正文。
        preview = nodeId == null && html == null,
        transparentBackground = inCommentCard,
    )
  }
}
