package moe.yumeyuka.yumehub.feature.discussiondetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.ChevronRight
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessageSquare
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.NullableValue
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.workCacheTag
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.GithubTxt
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ReadingTextPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.*
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.theme.readableForeground
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import retrofit2.HttpException

data class DiscussionDetailRoute(
    val owner: String,
    val name: String,
    val number: Int,
    val parentId: String? = null,
) : GithubRoute

@Composable
fun DiscussionDetailPage(
    route: DiscussionDetailRoute,
    account: String,
    onOpenReplies: (String) -> Unit,
) {
  val viewModel: DiscussionDetailViewModel =
      koinViewModel(
          key = "discussion:$account:$route",
          parameters = { parametersOf(route, account) },
      )
  val state by viewModel.state.collectAsStateWithLifecycle()
  GithubRefreshEffect(
      viewModel,
      DataDomain.Work,
      refreshOnFirstActivation = false,
      onRefresh = viewModel::revalidate,
  )
  val strings = LocalGithubStrings.current
  val host = requireNotNull(LocalGithubPageHost.current)
  val composerLabel =
      if (route.parentId == null) strings.WorkDetail.WriteComment else strings.Activity.Reply
  val openComposer: () -> Unit = {
    if (host.navigationEnabled) viewModel.commentEditor(composerLabel)?.let(host.onOpenEditor)
  }
  val openReplies: ((String) -> Unit)? =
      if (route.parentId == null) {
        { id -> if (host.navigationEnabled) onOpenReplies(id) }
      } else null
  val comments = state.comments
  val window = comments?.state?.collectAsStateWithLifecycle()?.value
  val contentReady = state.detail != null && window?.ready == true
  val presentation =
      rememberGithubDetailPresentation(
          key = "discussion-detail",
          kind = GithubDetailKind.Discussion,
          dataReady = contentReady,
          failed = state.failed || window?.error != null,
      )
  GithubLoadFailureToast(window?.error, strings.Activity.LoadFailed)
  if (state.failed) GithubLoadFailureToast(Unit, strings.Activity.LoadFailed)
  if (state.actionFailed) GithubLoadFailureToast(Unit, strings.Activity.ActionFailed)
  DiscussionReactionFailureToast(viewModel, strings.Activity.ActionFailed)
  GithubPage(
      title =
          if (route.parentId == null) strings.Repository.Discussions else strings.Activity.Reply,
      titleSuffix = "#${route.number}",
      itemSpacing = 12.dp,
      detailPresentation = presentation,
      onRetryDetail = viewModel::load,
      bottomContent = {
        GlasmioButton(
            onClick = openComposer,
            enabled = state.detail != null && host.navigationEnabled,
            size = GlasmioButtonSize.Large,
            style = GlassSurfaceStyle.Navigation,
            sampleBackdrop = true,
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(44.dp),
        ) {
          GithubIcon(Lucide.MessageSquare, contentDescription = null, size = 18.dp)
          Spacer(Modifier.width(6.dp))
          Text(composerLabel, style = GlasmioTheme.textStyles.body)
        }
      },
  ) {
    val detail = state.detail
    val loading = detail == null && !state.failed
    if (detail != null || loading) {
      readingItem(key = "discussion-body") {
        GithubLoadingContent(
            loading,
            placeholder = { GithubReadingGroup { ReadingTextPlaceholder() } },
        ) {
          if (detail != null) {
            val parent = detail.parent
            if (parent != null)
                DiscussionCommentCard(
                    comment = parent,
                    pageUrl = detail.url,
                    onOpenReplies = null,
                    enabled = host.navigationEnabled,
                    answerEnabled = !state.actionRunning,
                    reactionUpdates = viewModel.reactionUpdates,
                    onReply = openComposer,
                    onSetAnswer = viewModel::setAnswer,
                    onToggleReaction = { id, content, reacted ->
                      if (host.navigationEnabled) viewModel.toggleReaction(id, content, reacted)
                    },
                )
            else {
              DiscussionPostCard(
                  detail = detail,
                  enabled = host.navigationEnabled,
                  upvoteEnabled = !state.actionRunning,
                  reactionUpdates = viewModel.reactionUpdates,
                  onToggleReaction = { content, reacted ->
                    viewModel.toggleReaction(detail.id, content, reacted)
                  },
                  onUpvote = viewModel::toggleUpvote,
              )
            }
          }
        }
      }
    }
    val poll = detail?.poll
    if (poll != null && route.parentId == null) {
      item(key = "discussion-poll") {
        GithubSection(poll.question) {
          GlasmioListRow(
              title = strings.Activity.PollVotes(poll.totalVoteCount),
              systemName = null,
              enabled = false,
          )
          poll.options.forEach { option ->
            GlasmioListRow(
                title = option.option,
                detail =
                    strings.Activity.PollVotes(option.totalVoteCount).takeIf {
                      poll.viewerHasVoted || !poll.viewerCanVote
                    },
                trailing = {
                  if (option.viewerHasVoted) GithubIcon(Lucide.Check, contentDescription = null)
                },
                systemName = null,
                onClick =
                    if (poll.viewerCanVote && !poll.viewerHasVoted)
                        ({ viewModel.votePoll(option.id) })
                    else null,
                enabled =
                    poll.viewerCanVote &&
                        !poll.viewerHasVoted &&
                        !state.actionRunning &&
                        host.navigationEnabled,
            )
          }
        }
      }
    }
    if (detail != null && comments != null && window != null) {
      githubThreadItems(
          window,
          GithubDiscussionComment::id,
          comments::load,
          placeholder = { GithubReadingGroup { ReadingTextPlaceholder() } },
      ) { comment, _ ->
        DiscussionCommentCard(
            comment = comment,
            pageUrl = detail.url,
            onOpenReplies = openReplies,
            enabled = host.navigationEnabled,
            answerEnabled = !state.actionRunning,
            reactionUpdates = viewModel.reactionUpdates,
            onReply = openComposer.takeIf { route.parentId != null },
            onSetAnswer = { commentId, answer ->
              if (host.navigationEnabled) viewModel.setAnswer(commentId, answer)
            },
            onToggleReaction = { id, content, reacted ->
              if (host.navigationEnabled) viewModel.toggleReaction(id, content, reacted)
            },
        )
      }
    }
  }
}

@Composable
private fun DiscussionReactionFailureToast(viewModel: DiscussionDetailViewModel, message: String) {
  val failure by viewModel.reactionFailure.collectAsStateWithLifecycle()
  GithubLoadFailureToast(failure, message)
}

@Composable
private fun DiscussionPostCard(
    detail: GithubDiscussionDetail,
    enabled: Boolean,
    upvoteEnabled: Boolean,
    reactionUpdates: GithubReactionUpdates,
    onToggleReaction: (GithubReactionContent, Boolean) -> Unit,
    onUpvote: () -> Unit,
) {
  val strings = LocalGithubStrings.current
  val colors = GlasmioTheme.colors
  val copy = rememberCopyText()
  val metadata =
      listOfNotNull(
              githubRelativeTime(detail.createdAt, strings.Notifications),
              detail.labels.firstOrNull() ?: detail.category.takeIf(String::isNotBlank),
          )
          .joinToString(" · ")
  GithubReadingGroup {
      GithubCommentAuthorHeader(
          author = detail.author,
          avatarUrl = detail.avatarUrl,
          metadata = metadata,
          role = detail.authorAssociation?.let(strings.Activity.DiscussionAuthorRole),
          menuEnabled = enabled,
          menuItems =
              listOf(
                  GlasmioCascadingMenuItem(
                      title = strings.WorkDetail.CopyLink,
                      onClick = { copy(strings.WorkDetail.CopyLink, detail.url) },
                  ),
              ),
      )
      Text(
          detail.title,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          style =
              GlasmioTheme.typography.headlineLarge.copy(
                  fontSize = 22.sp,
                  lineHeight = 32.sp,
                  fontWeight = FontWeight.Bold,
              ),
          color = colors.content,
      )
      DiscussionBody(detail.bodyHtml, detail.url, post = true)
      val showVote = detail.canUpvote || detail.upvoted || detail.upvotes > 0
      if (showVote || detail.canReact || detail.reactions.any { it.count > 0 }) {
        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          if (showVote) {
            GlasmioButton(
                onClick = onUpvote,
                enabled = enabled && upvoteEnabled && (detail.canUpvote || detail.upvoted),
                modifier = Modifier.padding(start = 16.dp),
                size = GlasmioButtonSize.Small,
                sampleBackdrop = false,
                emphasis = if (detail.upvoted) GlassEmphasis.Prominent else GlassEmphasis.Regular,
            ) {
              Text(
                  strings.Activity.Upvote(detail.upvotes),
                  style = GlasmioTheme.textStyles.footnote,
              )
            }
          }
          GithubReactionBar(
              subjectKey = detail.id,
              sourceReactions = detail.reactions,
              canReact = detail.canReact,
              enabled = enabled,
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(horizontal = 16.dp),
              updates = reactionUpdates,
              onToggle = onToggleReaction,
          )
        }
      }
  }
}

@Composable
private fun DiscussionCommentCard(
    comment: GithubDiscussionComment,
    pageUrl: String,
    onOpenReplies: ((String) -> Unit)?,
    enabled: Boolean,
    answerEnabled: Boolean,
    reactionUpdates: GithubReactionUpdates,
    onReply: (() -> Unit)?,
    onSetAnswer: ((String, Boolean) -> Unit)?,
    onToggleReaction: (String, GithubReactionContent, Boolean) -> Unit,
) {
  val strings = LocalGithubStrings.current
  val canSetAnswer =
      onSetAnswer != null &&
          (if (comment.isAnswer) comment.canUnmarkAsAnswer else comment.canMarkAsAnswer)
  val reply = onReply ?: onOpenReplies?.let { open -> { open(comment.id) } }
  val menuItems = buildList {
    if (reply != null) {
      add(GlasmioCascadingMenuItem(title = strings.Activity.Reply, onClick = reply))
    }
    if (canSetAnswer) {
      add(
          GlasmioCascadingMenuItem(
              title =
                  if (comment.isAnswer) strings.Activity.UnmarkAsAnswer
                  else strings.Activity.MarkAsAnswer,
              enabled = answerEnabled,
              onClick = { onSetAnswer(comment.id, !comment.isAnswer) },
          ),
      )
    }
  }
  GithubReadingGroup(
      onFooterClick = onOpenReplies?.takeIf { comment.replies > 0 }?.let { open ->
        { open(comment.id) }
      },
      footerEnabled = enabled,
      footer = {
        DisableSelection {
          val colors = GlasmioTheme.colors
          val accent = readableForeground(colors.accent, colors.elevatedBackground, colors.content)
          Row(
              modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            GithubDiscussionParticipantAvatars(comment.replyAuthors)
            Text(
                strings.Activity.Replies(comment.replies),
                modifier = Modifier.weight(1f),
                style = GlasmioTheme.textStyles.subheadline,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GithubIcon(Lucide.ChevronRight, contentDescription = null, size = 18.dp, tint = accent)
          }
        }
      },
  ) {
    GithubCommentAuthorHeader(
        author = comment.author,
        avatarUrl = comment.avatarUrl,
        metadata = githubRelativeTime(comment.createdAt, strings.Notifications),
        menuItems = menuItems,
        menuEnabled = enabled,
    )
    DiscussionBody(comment.bodyHtml, pageUrl)
    if (comment.isAnswer)
        GlasmioListRow(title = strings.Activity.Answer, systemName = null, enabled = false)
    GithubReactionBar(
        subjectKey = comment.id,
        sourceReactions = comment.reactions,
        canReact = comment.canReact,
        enabled = enabled,
        updates = reactionUpdates,
    ) { content, reacted ->
      onToggleReaction(comment.id, content, reacted)
    }
  }
}

@Composable
private fun DiscussionBody(
    html: String,
    pageUrl: String,
    post: Boolean = false,
) {
  val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
  if (html.isBlank())
      Text(
          LocalGithubStrings.current.WorkDetail.NoBody,
          modifier,
          style = if (post) GlasmioTheme.textStyles.subheadline else GlasmioTheme.textStyles.body,
          color = GlasmioTheme.colors.secondaryContent,
      )
  else
      GithubMarkdown(
          "",
          modifier,
          html = html,
          pageUrl = pageUrl,
          transparentBackground = true,
          compactBodyTypography = post,
      )
}

@Serializable
internal data class DiscussionUpvoteState(val upvoted: Boolean, val upvotes: Int)

internal data class DiscussionDetailState(
    val detail: GithubDiscussionDetail? = null,
    val comments: ThreadWindow<GithubDiscussionComment>? = null,
    val failed: Boolean = false,
    val actionRunning: Boolean = false,
    val actionFailed: Boolean = false,
    val upvotePhase: WritePhase = WritePhase.IDLE,
    val answerPhase: WritePhase = WritePhase.IDLE,
    val pollPhase: WritePhase = WritePhase.IDLE,
)

internal class DiscussionDetailViewModel(
    private val source: DiscussionRepository,
    private val route: DiscussionDetailRoute,
    private val account: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(DiscussionDetailState())
  val state = mutableState.asStateFlow()
  val reactionUpdates = source.reactionUpdates(account)
  private val mutableReactionFailure = MutableStateFlow<Exception?>(null)
  val reactionFailure = mutableReactionFailure.asStateFlow()
  private var job: Job? = null
  private var revalidationJob: Job? = null
  private var commentDraft = ""
  private var coordinator: MutationCoordinator? = null
  private var confirmedDetail: GithubDiscussionDetail? = null
  private val reactionObservers = mutableMapOf<String, Job>()
  private var detailObserversStarted = false
  private val workTag = workCacheTag("Discussion", route.owner, route.name, route.number)

  init {
    viewModelScope.launch {
      coordinator =
          try {
            source.mutations(account)
          } catch (error: CancellationException) {
            throw error
          } catch (_: Exception) {
            null
          }
      confirmedDetail?.let { startDetailObservers(it) }
    }
    load()
  }

  private fun upvoteKey(discussionId: String) =
      MutationKey(MutationKind.DISCUSSION_UPVOTE, discussionId, DiscussionUpvoteState.serializer())

  private fun answerKey(discussionId: String) =
      MutationKey(
          MutationKind.DISCUSSION_ANSWER,
          discussionId,
          NullableValue.serializer(String.serializer()),
      )

  private fun reactionKey(subjectId: String) =
      MutationKey(
          MutationKind.REACTION,
          subjectId,
          ListSerializer(GithubReactionGroup.serializer()),
      )

  private fun pollKey(pollId: String) =
      MutationKey(MutationKind.DISCUSSION_VOTE, pollId, GithubDiscussionPoll.serializer())

  private fun commentKey(discussionId: String) =
      MutationKey(
          MutationKind.COMMENT,
          "discussion:$discussionId:${route.parentId.orEmpty()}",
          CommandAccepted.serializer(),
      )

  private fun startDetailObservers(detail: GithubDiscussionDetail) {
    val coord = coordinator ?: return
    if (detailObserversStarted) return
    detailObserversStarted = true
    viewModelScope.launch {
      coord.observe(upvoteKey(detail.id)).collect { write ->
        val next = write.desired ?: write.confirmed
        mutableState.update { current ->
          current.copy(
              detail =
                  if (next != null) {
                    current.detail?.copy(upvoted = next.upvoted, upvotes = next.upvotes)
                  } else current.detail,
              upvotePhase = write.phase,
              actionFailed =
                  current.actionFailed ||
                      write.phase == WritePhase.REJECTED ||
                      write.phase == WritePhase.UNCERTAIN,
          )
        }
      }
    }
    viewModelScope.launch {
      coord.observe(answerKey(detail.id)).collect { write ->
        mutableState.update { current ->
          current.copy(
              answerPhase = write.phase,
              actionRunning = write.phase == WritePhase.PENDING,
              actionFailed =
                  current.actionFailed ||
                      write.phase == WritePhase.REJECTED ||
                      write.phase == WritePhase.UNCERTAIN,
          )
        }
        if (write.phase == WritePhase.IDLE && write.confirmed != null) {
          load()
        }
      }
    }
  }

  fun load() {
    job?.cancel()
    revalidationJob?.cancel()
    job = viewModelScope.launch {
      mutableState.update { it.copy(failed = false) }
      try {
        val coord = coordinator
        val detail = source.load(account, route.owner, route.name, route.number, route.parentId)
        confirmedDetail = detail
        if (coord != null) {
          val upKey = upvoteKey(detail.id)
          val stamp = coord.beginRead(upKey)
          coord.publishConfirmed(
              upKey,
              stamp,
              DiscussionUpvoteState(detail.upvoted, detail.upvotes),
          )
          startDetailObservers(detail)
        }
        val previous = state.value
        val comments =
            previous.comments?.takeIf { previous.detail?.id == detail.id }
                ?: source.comments(
                    viewModelScope,
                    account,
                    route.parentId ?: detail.id,
                    route.parentId != null,
                )
        comments.load(ThreadDirection.Latest)
        val upvoteSlot = coord?.current(upvoteKey(detail.id))?.let { it.desired ?: it.confirmed }
        val projectedDetail =
            if (upvoteSlot != null) {
              detail.copy(upvoted = upvoteSlot.upvoted, upvotes = upvoteSlot.upvotes)
            } else detail
        mutableState.update { it.copy(detail = projectedDetail, comments = comments) }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(failed = true) }
      }
    }
  }

  fun revalidate() {
    val previous = state.value.detail ?: return
    if (job?.isActive == true) return
    revalidationJob?.cancel()
    revalidationJob = viewModelScope.launch {
      mutableState.update { it.copy(failed = false) }
      try {
        val coord = coordinator
        val upKey = upvoteKey(previous.id)
        val stamp = coord?.beginRead(upKey)
        val detail = source.load(account, route.owner, route.name, route.number, route.parentId)
        confirmedDetail = detail
        if (coord != null && stamp != null) {
          coord.publishConfirmed(upKey, stamp, DiscussionUpvoteState(detail.upvoted, detail.upvotes))
        }
        val upvoteSlot = coord?.current(upKey)?.let { it.desired ?: it.confirmed }
        val projectedDetail =
            if (upvoteSlot != null) {
              detail.copy(upvoted = upvoteSlot.upvoted, upvotes = upvoteSlot.upvotes)
            } else detail
        mutableState.update { current ->
          if (current.detail?.id == previous.id && detail.id == previous.id) {
            current.copy(detail = projectedDetail)
          } else {
            current
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(failed = true) }
      }
    }
  }

  fun toggleUpvote() {
    val detail = state.value.detail ?: return
    if (!detail.canUpvote && !detail.upvoted) return
    val nextUpvoted = !detail.upvoted
    val nextCount = (detail.upvotes + if (nextUpvoted) 1 else -1).coerceAtLeast(0)
    mutableState.update { it.copy(actionFailed = false) }
    viewModelScope.launch {
      val coord =
          coordinator
              ?: try {
                source.mutations(account).also {
                  coordinator = it
                  startDetailObservers(detail)
                }
              } catch (error: CancellationException) {
                throw error
              } catch (_: Exception) {
                mutableState.update { it.copy(actionFailed = true) }
                return@launch
              }
      val base = confirmedDetail ?: detail
      val accepted =
          coord.submit(
              upvoteKey(detail.id),
              confirmed = DiscussionUpvoteState(base.upvoted, base.upvotes),
              desired = DiscussionUpvoteState(nextUpvoted, nextCount),
              tags = setOf(workTag, CacheTag(DataDomain.Work, null)),
              send = { desired ->
                val (upvoted, count) = source.setUpvote(account, detail.id, desired.upvoted)
                DiscussionUpvoteState(upvoted, count)
              },
              readBack = {
                val reloaded = source.load(account, route.owner, route.name, route.number, route.parentId)
                DiscussionUpvoteState(reloaded.upvoted, reloaded.upvotes)
              },
          )
      if (!accepted) {
        mutableState.update { it.copy(actionFailed = true) }
      }
    }
  }

  fun setAnswer(commentId: String, answer: Boolean) {
    val detail = state.value.detail ?: return
    mutableState.update { it.copy(actionFailed = false) }
    viewModelScope.launch {
      val coord =
          coordinator
              ?: try {
                source.mutations(account).also {
                  coordinator = it
                  startDetailObservers(detail)
                }
              } catch (error: CancellationException) {
                throw error
              } catch (_: Exception) {
                mutableState.update { it.copy(actionFailed = true) }
                return@launch
              }
      val desiredId = if (answer) commentId else null
      val currentAnswerId =
          state.value.comments?.state?.value?.items?.firstOrNull { it.isAnswer }?.id
      val accepted =
          coord.submit(
              answerKey(detail.id),
              confirmed = NullableValue(currentAnswerId),
              desired = NullableValue(desiredId),
              tags = setOf(workTag, CacheTag(DataDomain.Work, null)),
              send = { desired ->
                val answerId = desired.value
                if (answerId != null) {
                  source.setAnswer(account, answerId, true)
                } else {
                  source.setAnswer(account, commentId, false)
                }
                desired
              },
              readBack = {
                source.load(account, route.owner, route.name, route.number, route.parentId)
                NullableValue(
                    state.value.comments?.state?.value?.items?.firstOrNull { it.isAnswer }?.id,
                )
              },
          )
      if (!accepted) {
        mutableState.update { it.copy(actionFailed = true) }
      }
    }
  }

  fun votePoll(optionId: String) {
    val poll = state.value.detail?.poll ?: return
    if (
        state.value.pollPhase == WritePhase.PENDING ||
            !poll.viewerCanVote ||
            poll.viewerHasVoted ||
            poll.options.none { it.id == optionId }
    )
        return
    mutableState.update { it.copy(actionFailed = false) }
    viewModelScope.launch {
      val coord =
          coordinator
              ?: try {
                source.mutations(account).also { coordinator = it }
              } catch (error: CancellationException) {
                throw error
              } catch (_: Exception) {
                mutableState.update { it.copy(actionFailed = true) }
                return@launch
              }
      val key = pollKey(poll.id)
      launch {
        coord.observeCommand(key).collect { cmd ->
          mutableState.update { current ->
            current.copy(
                detail = cmd.result?.let { voted -> current.detail?.copy(poll = voted) }
                    ?: current.detail,
                pollPhase = cmd.phase,
                actionFailed =
                    current.actionFailed ||
                        cmd.phase == WritePhase.REJECTED ||
                        cmd.phase == WritePhase.UNCERTAIN,
            )
          }
        }
      }
      val accepted =
          coord.submitCommand(
              key,
              tags = setOf(workTag),
              send = { source.votePoll(account, poll.id, optionId) },
              readBack = {
                val reloaded =
                    source.load(account, route.owner, route.name, route.number, route.parentId)
                reloaded.poll?.takeIf { it.viewerHasVoted }
              },
          )
      if (!accepted) {
        mutableState.update { it.copy(actionFailed = true) }
      }
    }
  }

  fun toggleReaction(
      subjectId: String,
      content: GithubReactionContent,
      reacted: Boolean,
  ) {
    val sourceReactions = state.value.reactableGroups(subjectId) ?: return
    val coord = coordinator
    val slotReactions = coord?.current(reactionKey(subjectId))?.let { it.desired ?: it.confirmed }
    val previous = reactionUpdates.current(subjectId, sourceReactions)
    val reactions = slotReactions ?: previous?.reactions ?: sourceReactions
    if (reactions.any { it.content == content && it.viewerReacted } != reacted) return
    val desired = reactions.withToggledReaction(content)
    val pending = GithubReactionUpdate(sourceReactions, desired, pending = true)
    reactionUpdates.set(subjectId, pending)
    mutableReactionFailure.value = null
    viewModelScope.launch {
      val activeCoord =
          coord
              ?: try {
                source.mutations(account).also { coordinator = it }
              } catch (error: CancellationException) {
                reactionUpdates.set(subjectId, previous)
                throw error
              } catch (error: Exception) {
                reactionUpdates.set(subjectId, previous)
                mutableReactionFailure.value = error
                return@launch
              }
      if (reactionObservers[subjectId]?.isActive != true) {
        reactionObservers[subjectId] =
            launch {
              activeCoord.observe(reactionKey(subjectId)).collect { write ->
                val baseline = state.value.reactableGroups(subjectId) ?: sourceReactions
                val value = write.desired ?: write.confirmed
                if (value != null) {
                  reactionUpdates.set(
                      subjectId,
                      GithubReactionUpdate(
                          source = baseline,
                          reactions = value,
                          pending = write.phase == WritePhase.PENDING,
                      ),
                  )
                }
                if (write.phase == WritePhase.REJECTED || write.phase == WritePhase.UNCERTAIN) {
                  mutableReactionFailure.value =
                      IllegalStateException(write.message ?: "Reaction failed")
                }
              }
            }
      }
      val accepted =
          activeCoord.submit(
              reactionKey(subjectId),
              confirmed = sourceReactions,
              desired = desired,
              tags = setOf(workTag),
              send = { nextDesired ->
                val baseline =
                    activeCoord.current(reactionKey(subjectId)).confirmed ?: sourceReactions
                convergeGithubReactions(baseline, nextDesired) { targetContent, currentReacted ->
                  source.toggleReaction(account, subjectId, targetContent, currentReacted)
                }
              },
              readBack = {
                val reloaded =
                    source.load(account, route.owner, route.name, route.number, route.parentId)
                if (reloaded.id == subjectId) reloaded.reactions else sourceReactions
              },
          )
      if (!accepted) {
        reactionUpdates.set(subjectId, previous)
        mutableReactionFailure.value = IllegalStateException("Result unconfirmed")
      }
    }
  }

  fun commentEditor(title: String): GithubEditorSession? {
    val detail = state.value.detail ?: return null
    lateinit var editor: GithubEditorSession
    editor =
        GithubEditorSession(
            title = title,
            kind = GithubEditorKind.Markdown,
            source = commentDraft,
            submitEnabled = validComment(commentDraft),
            pageUrl = detail.url,
            repositoryRootUrl = "https://github.com/${route.owner}/${route.name}",
            submitLabel = GithubTxt.current.WorkDetail.Publish,
            onSourceChange = { text ->
              commentDraft = text
              editor.update(submitEnabled = validComment(text), error = null)
            },
            onSubmit = { submitComment(detail.id, editor) },
        )
    return editor
  }

  private fun submitComment(discussionId: String, editor: GithubEditorSession) {
    val body = editor.source
    if (!validComment(body)) return
    editor.update(submitting = true, submitEnabled = false, error = null)
    viewModelScope.launch {
      val coord =
          coordinator
              ?: try {
                source.mutations(account).also { coordinator = it }
              } catch (error: CancellationException) {
                editor.update(submitting = false)
                throw error
              } catch (_: Exception) {
                editor.update(
                    submitting = false,
                    editable = true,
                    submitEnabled = true,
                    error = GithubTxt.current.Activity.CommentFailed,
                )
                return@launch
              }
      val key = commentKey(discussionId)
      val observeJob =
          launch {
            coord.observeCommand(key).collect { cmd ->
              when (cmd.phase) {
                WritePhase.IDLE ->
                    if (cmd.result != null) {
                      if (route.parentId == null) {
                        mutableState.update { current ->
                          current.copy(
                              detail =
                                  current.detail?.let { it.copy(commentsCount = it.commentsCount + 1) },
                          )
                        }
                      }
                      state.value.comments?.load(ThreadDirection.Latest)
                      commentDraft = ""
                      editor.complete()
                      editor.update(submitting = false)
                    }
                WritePhase.PENDING ->
                    editor.update(submitting = true, submitEnabled = false, error = null)
                WritePhase.UNCERTAIN -> {
                  load()
                  editor.update(
                      submitting = false,
                      editable = false,
                      submitEnabled = false,
                      error = GithubTxt.current.Activity.CommentUnconfirmed,
                  )
                }
                WritePhase.REJECTED ->
                    editor.update(
                        submitting = false,
                        editable = true,
                        submitEnabled = true,
                        error = GithubTxt.current.Activity.CommentFailed,
                    )
              }
            }
          }
      val accepted =
          coord.submitCommand(
              key,
              tags = setOf(workTag, CacheTag(DataDomain.Work, null)),
              send = {
                source.addComment(account, discussionId, route.parentId, body)
                CommandAccepted
              },
              readBack = { null },
          )
      if (!accepted) {
        observeJob.cancel()
        editor.update(
            submitting = false,
            editable = false,
            submitEnabled = false,
            error = GithubTxt.current.Activity.CommentUnconfirmed,
        )
      }
    }
  }
}

private fun DiscussionDetailState.reactableGroups(subjectId: String): List<GithubReactionGroup>? {
  val detail = detail ?: return null
  if (detail.id == subjectId) return detail.reactions.takeIf { detail.canReact }
  detail.parent?.takeIf { it.id == subjectId }?.let { parent ->
    return parent.reactions.takeIf { parent.canReact }
  }
  return comments
      ?.state
      ?.value
      ?.items
      ?.firstOrNull { it.id == subjectId && it.canReact }
      ?.reactions
}

private fun validComment(text: String): Boolean = text.isNotBlank() && text.length <= 64_000

val discussionDetailFeatureModule = module {
  viewModel { (route: DiscussionDetailRoute, account: String) ->
    DiscussionDetailViewModel(get(), route, account)
  }
}
