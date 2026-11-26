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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.DataDomain
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
import moe.yumeyuka.yumehub.ui.navigation.*
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.theme.readableForeground
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

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
    GithubLoadFailureToast(window?.error, strings.Activity.LoadFailed)
    if (state.failed) GithubLoadFailureToast(Unit, strings.Activity.LoadFailed)
    if (state.actionFailed) GithubLoadFailureToast(Unit, strings.Activity.ActionFailed)
    DiscussionReactionFailureToast(viewModel, strings.Activity.ActionFailed)
    GithubPage(
        title =
            if (route.parentId == null) strings.Repository.Discussions else strings.Activity.Reply,
        titleSuffix = "#${route.number}",
        itemSpacing = 12.dp,
        bottomContent = {
            GlasmioButton(
                onClick = openComposer,
                enabled =
                    state.detail != null &&
                        state.draftReady &&
                        state.commentPhase != WritePhase.PENDING &&
                        host.navigationEnabled,
                size = GlasmioButtonSize.Large,
                style = GlassSurfaceStyle.Navigation,
                sampleBackdrop = true,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .height(44.dp),
            ) {
                GithubIcon(Lucide.MessageSquare, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(composerLabel, style = GlasmioTheme.textStyles.body)
            }
        },
    ) {
        val detail = state.detail
        val phases =
            listOf(state.answerPhase, state.upvotePhase, state.pollPhase, state.commentPhase)
        if (phases.any { it != WritePhase.IDLE })
            item {
                val uncertain = WritePhase.UNCERTAIN in phases
                GlasmioListRow(
                    title =
                        when {
                            uncertain -> strings.Common.MutationUncertain
                            WritePhase.PENDING in phases -> strings.Common.MutationPending
                            else -> strings.Common.MutationNotSubmitted
                        },
                    detail = strings.Common.MutationReconcile.takeIf { uncertain },
                    onClick = if (uncertain) viewModel::revalidate else null,
                )
            }
        if (state.failed)
            item(key = "discussion-detail-retry") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Activity.LoadFailed,
                        detail = strings.Common.Retry,
                        onClick = viewModel::load,
                    )
                }
            }
        if (detail != null || !state.failed) {
            readingItem(key = "discussion-body") {
                if (detail == null)
                    GithubReadingGroup {
                        GithubThreadContentPlaceholder(showTitle = route.parentId == null)
                    }
                else {
                    val parent = detail.parent
                    if (parent != null)
                        DiscussionCommentCard(
                            comment =
                                parent.copy(
                                    isAnswer =
                                        parent.id ==
                                            (state.answer ?: NullableValue(detail.answerId)).value,
                                ),
                            pageUrl = detail.url,
                            onOpenReplies = null,
                            enabled = host.navigationEnabled,
                            answerEnabled = state.answerPhase != WritePhase.UNCERTAIN,
                            reactionUpdates = viewModel.reactionUpdates,
                            onReply = openComposer,
                            onSetAnswer = viewModel::setAnswer,
                            onToggleReaction = { id, content, reacted ->
                                if (host.navigationEnabled)
                                    viewModel.toggleReaction(id, content, reacted)
                            },
                        )
                    else {
                        DiscussionPostCard(
                            detail = detail,
                            enabled = host.navigationEnabled,
                            upvoteEnabled = state.upvotePhase != WritePhase.UNCERTAIN,
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
                                if (option.viewerHasVoted)
                                    GithubIcon(Lucide.Check, contentDescription = null)
                            },
                            systemName = null,
                            onClick =
                                if (poll.viewerCanVote && !poll.viewerHasVoted)
                                    ({ viewModel.votePoll(option.id) })
                                else null,
                            enabled =
                                poll.viewerCanVote &&
                                    !poll.viewerHasVoted &&
                                    state.pollPhase != WritePhase.PENDING &&
                                    state.pollPhase != WritePhase.UNCERTAIN &&
                                    host.navigationEnabled,
                        )
                    }
                }
            }
        }
        if (detail == null && !state.failed) {
            githubThreadPlaceholder { GithubReadingGroup { GithubThreadContentPlaceholder() } }
        } else if (detail != null && comments != null && window != null) {
            githubThreadItems(
                window,
                GithubDiscussionComment::id,
                comments::load,
                placeholder = { GithubReadingGroup { GithubThreadContentPlaceholder() } },
            ) { comment, _ ->
                DiscussionCommentCard(
                    comment =
                        comment.copy(
                            isAnswer =
                                comment.id ==
                                    (state.answer ?: NullableValue(detail.answerId)).value,
                        ),
                    pageUrl = detail.url,
                    onOpenReplies = openReplies,
                    enabled = host.navigationEnabled,
                    answerEnabled = state.answerPhase != WritePhase.UNCERTAIN,
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
    GithubReadingGroup(documentKey = detail.bodyHtml.takeIf(String::isNotBlank)) {
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
        if (showVote || detail.canReact || detail.reactions.any { it.count > 0 })
            GithubReadingFooter {
                Row(
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showVote) {
                        GlasmioButton(
                            onClick = onUpvote,
                            enabled =
                                enabled && upvoteEnabled && (detail.canUpvote || detail.upvoted),
                            modifier = Modifier.padding(start = 16.dp),
                            size = GlasmioButtonSize.Small,
                            sampleBackdrop = false,
                            emphasis =
                                if (detail.upvoted) GlassEmphasis.Prominent
                                else GlassEmphasis.Regular,
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
        documentKey = comment.bodyHtml.takeIf(String::isNotBlank),
        onFooterClick =
            onOpenReplies
                ?.takeIf { comment.replies > 0 }
                ?.let { open ->
                    { open(comment.id) }
                },
        footerEnabled = enabled,
        footer = {
            DisableSelection {
                val colors = GlasmioTheme.colors
                val accent =
                    readableForeground(colors.accent, colors.elevatedBackground, colors.content)
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
                    GithubIcon(
                        Lucide.ChevronRight,
                        contentDescription = null,
                        size = 18.dp,
                        tint = accent,
                    )
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
        GithubReadingFooter {
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

@Serializable internal data class DiscussionUpvoteState(val upvoted: Boolean, val upvotes: Int)

internal data class DiscussionDetailState(
    val detail: GithubDiscussionDetail? = null,
    val comments: ThreadWindow<GithubDiscussionComment>? = null,
    val failed: Boolean = false,
    val answer: NullableValue<String>? = null,
    val draftReady: Boolean = false,
    val actionFailed: Boolean = false,
    val upvotePhase: WritePhase = WritePhase.IDLE,
    val answerPhase: WritePhase = WritePhase.IDLE,
    val pollPhase: WritePhase = WritePhase.IDLE,
    val commentPhase: WritePhase = WritePhase.IDLE,
)

internal class DiscussionDetailViewModel(
    private val source: DiscussionRepository,
    private val route: DiscussionDetailRoute,
    private val account: String,
    private val drafts: moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscussionDetailState())
    val state = mutableState.asStateFlow()
    val reactionUpdates = source.reactionUpdates(account)
    private val mutableReactionFailure = MutableStateFlow<Exception?>(null)
    val reactionFailure = mutableReactionFailure.asStateFlow()
    private var job: Job? = null
    private var commentDraft = ""
    private val coordinator = source.mutations(account)
    private val resource =
        source.resource(account, route.owner, route.name, route.number, route.parentId)
    private val draftKey =
        "discussion-comment/${route.owner.lowercase()}/${route.name.lowercase()}/${route.number}/${route.parentId.orEmpty()}"
    private var confirmedDetail: GithubDiscussionDetail? = null
    private val reactionObservers = mutableMapOf<String, Job>()
    private var detailObserversStarted = false
    private val workTag = workCacheTag("Discussion", route.owner, route.name, route.number)

    init {
        viewModelScope.launch {
            commentDraft = drafts.load(account, draftKey)
            mutableState.update { it.copy(draftReady = true) }
        }
        viewModelScope.launch {
            resource.state.collect { cached ->
                val detail = cached.data
                if (detail != null) present(detail)
                else if (confirmedDetail != null) {
                    confirmedDetail = null
                    mutableState.update { it.copy(detail = null, comments = null) }
                }
                mutableState.update { it.copy(failed = cached.error != null) }
            }
        }
        load()
    }

    private fun upvoteKey(discussionId: String) =
        MutationKey(
            MutationKind.DISCUSSION_UPVOTE,
            discussionId,
            DiscussionUpvoteState.serializer(),
        )

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
        val coord = coordinator
        if (detailObserversStarted) return
        detailObserversStarted = true
        viewModelScope.launch {
            coord.observeCommand(commentKey(detail.id)).collect { cmd ->
                mutableState.update { it.copy(commentPhase = cmd.phase) }
            }
        }
        detail.poll?.let { poll ->
            viewModelScope.launch {
                coord.observeCommand(pollKey(poll.id)).collect { cmd ->
                    mutableState.update { state ->
                        state.copy(
                            pollPhase = cmd.phase,
                            detail =
                                cmd.result
                                    ?.takeIf { cmd.phase == WritePhase.IDLE }
                                    ?.let { state.detail?.copy(poll = it) } ?: state.detail,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            coord.observe(upvoteKey(detail.id)).collect { write ->
                val next = write.visible
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
                        answer = write.visible,
                        actionFailed =
                            current.actionFailed ||
                                write.phase == WritePhase.REJECTED ||
                                write.phase == WritePhase.UNCERTAIN,
                    )
                }
            }
        }
    }

    private fun present(detail: GithubDiscussionDetail) {
        confirmedDetail = detail
        startDetailObservers(detail)
        val up = coordinator.current(upvoteKey(detail.id)).visible
        val previous = state.value
        val comments =
            previous.comments?.takeIf { previous.detail?.id == detail.id }
                ?: source
                    .comments(
                        viewModelScope,
                        account,
                        route.parentId ?: detail.id,
                        route.parentId != null,
                    )
                    .also { it.load(ThreadDirection.Latest) }
        mutableState.update {
            it.copy(
                detail =
                    if (up == null) detail
                    else detail.copy(upvoted = up.upvoted, upvotes = up.upvotes),
                comments = comments,
                failed = false,
            )
        }
    }

    fun load() = request(force = state.value.failed)

    fun revalidate() = request(force = true)

    private fun request(force: Boolean) {
        if (job?.isActive == true) return
        val id = confirmedDetail?.id
        val up = id?.let(::upvoteKey)
        val answer = id?.let(::answerKey)
        val upStamp = up?.let(coordinator::beginRead)
        val answerStamp = answer?.let(coordinator::beginRead)
        job = viewModelScope.launch {
            try {
                val detail =
                    source.load(
                        account,
                        route.owner,
                        route.name,
                        route.number,
                        route.parentId,
                        force,
                    )
                if (detail.id == id && force) {
                    if (up != null && upStamp != null)
                        coordinator.publishConfirmed(
                            up,
                            upStamp,
                            DiscussionUpvoteState(detail.upvoted, detail.upvotes),
                        )
                    if (answer != null && answerStamp != null)
                        coordinator.publishConfirmed(
                            answer,
                            answerStamp,
                            NullableValue(detail.answerId),
                        )
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
        val slot = coordinator.current(upvoteKey(detail.id))
        if (slot.phase == WritePhase.UNCERTAIN) {
            revalidate()
            return
        }
        val previous = slot.visible ?: DiscussionUpvoteState(detail.upvoted, detail.upvotes)
        val nextUpvoted = !previous.upvoted
        val nextCount = (previous.upvotes + if (nextUpvoted) 1 else -1).coerceAtLeast(0)
        mutableState.update { it.copy(actionFailed = false) }
        viewModelScope.launch {
            val coord = coordinator
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
                        val reloaded =
                            source.load(
                                account,
                                route.owner,
                                route.name,
                                route.number,
                                route.parentId,
                                force = true,
                            )
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
            val coord = coordinator
            val desiredId = if (answer) commentId else null
            val currentAnswerId = confirmedDetail?.answerId
            val accepted =
                coord.submit(
                    answerKey(detail.id),
                    confirmed = NullableValue(currentAnswerId),
                    desired = NullableValue(desiredId),
                    tags = setOf(workTag, CacheTag(DataDomain.Work, null)),
                    send = { desired ->
                        NullableValue(
                            source.setAnswer(
                                account,
                                detail.id,
                                desired.value ?: commentId,
                                desired.value != null,
                            ),
                        )
                    },
                    readBack = {
                        NullableValue(
                            source
                                .load(
                                    account,
                                    route.owner,
                                    route.name,
                                    route.number,
                                    route.parentId,
                                    force = true,
                                )
                                .answerId,
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
                state.value.pollPhase == WritePhase.UNCERTAIN ||
                !poll.viewerCanVote ||
                poll.viewerHasVoted ||
                poll.options.none { it.id == optionId }
        )
            return
        mutableState.update { it.copy(actionFailed = false) }
        viewModelScope.launch {
            val coord = coordinator
            val key = pollKey(poll.id)
            val accepted =
                coord.submitCommand(
                    key,
                    tags = setOf(workTag),
                    send = { source.votePoll(account, poll.id, optionId) },
                    readBack = {
                        val reloaded =
                            source.load(
                                account,
                                route.owner,
                                route.name,
                                route.number,
                                route.parentId,
                                force = true,
                            )
                        reloaded.poll?.takeIf { it.viewerHasVoted }
                    },
                )
            if (accepted == null) {
                mutableState.update { it.copy(actionFailed = true) }
            }
        }
    }

    private fun ensureReactionObserver(subjectId: String, baseline: List<GithubReactionGroup>) {
        if (reactionObservers[subjectId]?.isActive == true) return
        val key = reactionKey(subjectId)
        reactionObservers[subjectId] = viewModelScope.launch {
            coordinator.observe(key).collect { write ->
                val sourceReactions = state.value.reactableGroups(subjectId) ?: baseline
                val value = write.visible
                if (value != null) {
                    reactionUpdates.set(
                        subjectId,
                        GithubReactionUpdate(
                            source = sourceReactions,
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

    fun toggleReaction(
        subjectId: String,
        content: GithubReactionContent,
        reacted: Boolean,
    ) {
        val sourceReactions = state.value.reactableGroups(subjectId) ?: return
        val key = reactionKey(subjectId)
        val slot = coordinator.current(key)
        val previous = reactionUpdates.current(subjectId, sourceReactions)
        ensureReactionObserver(subjectId, sourceReactions)
        if (slot.phase == WritePhase.UNCERTAIN) {
            coordinator.reconcile(key) { source.reactions(account, subjectId) }
            mutableReactionFailure.value = IllegalStateException("Result unconfirmed")
            return
        }
        val reactions = slot.visible ?: previous?.reactions ?: sourceReactions
        if (reactions.any { it.content == content && it.viewerReacted } != reacted) return
        val desired = reactions.withToggledReaction(content)
        reactionUpdates.set(
            subjectId,
            GithubReactionUpdate(sourceReactions, desired, pending = true),
        )
        mutableReactionFailure.value = null
        viewModelScope.launch {
            val activeCoord = coordinator
            val accepted =
                activeCoord.submit(
                    key,
                    confirmed = sourceReactions,
                    desired = desired,
                    tags = setOf(workTag),
                    send = { nextDesired ->
                        val baseline = activeCoord.current(key).confirmed ?: sourceReactions
                        convergeGithubReactions(
                            baseline,
                            nextDesired,
                            { activeCoord.confirmStep(key, it) },
                        ) { targetContent, currentReacted ->
                            source.toggleReaction(account, subjectId, targetContent, currentReacted)
                        }
                    },
                    readBack = { source.reactions(account, subjectId) },
                )
            if (!accepted) {
                val latest = activeCoord.current(key)
                val visible = latest.visible
                if (visible != null) {
                    reactionUpdates.set(
                        subjectId,
                        GithubReactionUpdate(
                            source = sourceReactions,
                            reactions = visible,
                            pending = latest.phase == WritePhase.PENDING,
                        ),
                    )
                } else {
                    reactionUpdates.set(subjectId, previous)
                }
                if (latest.phase == WritePhase.UNCERTAIN) {
                    activeCoord.reconcile(key) { source.reactions(account, subjectId) }
                }
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
                submitEnabled =
                    validComment(commentDraft) && state.value.commentPhase != WritePhase.UNCERTAIN,
                pageUrl = detail.url,
                repositoryRootUrl = "https://github.com/${route.owner}/${route.name}",
                submitLabel = GithubTxt.current.WorkDetail.Publish,
                onSourceChange = { text ->
                    commentDraft = text
                    drafts.save(account, draftKey, text)
                    editor.update(
                        submitEnabled =
                            validComment(text) && state.value.commentPhase != WritePhase.UNCERTAIN,
                        error = null,
                    )
                },
                onSubmit = { submitComment(detail.id, editor) },
            )
        return editor
    }

    private fun submitComment(discussionId: String, editor: GithubEditorSession) {
        val body = editor.source
        if (!validComment(body) || state.value.commentPhase == WritePhase.UNCERTAIN) return
        editor.update(submitting = true, submitEnabled = false, error = null)
        viewModelScope.launch {
            val coord = coordinator
            if (!drafts.saveConfirmed(account, draftKey, body)) {
                editor.update(
                    submitting = false,
                    editable = true,
                    submitEnabled = true,
                    error = GithubTxt.current.Common.MutationNotSubmitted,
                )
                return@launch
            }
            val key = commentKey(discussionId)
            val store = drafts
            val login = account
            val storedKey = draftKey
            val receipt =
                coord.submitCommand(
                    key,
                    tags = setOf(workTag, CacheTag(DataDomain.Work, null)),
                    onConfirmed = { store.removeIfUnchanged(login, storedKey, body) },
                    send = {
                        source.addComment(login, discussionId, route.parentId, body)
                        CommandAccepted
                    },
                    readBack = { null },
                )
            val cmd = receipt?.await() ?: coord.currentCommand(key)
            if (receipt != null && cmd.phase == WritePhase.IDLE) {
                if (commentDraft == body) commentDraft = ""
                state.value.comments?.load(ThreadDirection.Latest)
                revalidate()
                editor.update(submitting = false)
                editor.complete()
            } else {
                val unknown = cmd.phase == WritePhase.UNCERTAIN
                if (unknown) revalidate()
                editor.update(
                    submitting = false,
                    editable = true,
                    submitEnabled = !unknown && cmd.phase != WritePhase.PENDING,
                    error =
                        if (unknown) GithubTxt.current.Activity.CommentUnconfirmed
                        else GithubTxt.current.Common.MutationNotSubmitted,
                )
            }
        }
    }
}

private fun DiscussionDetailState.reactableGroups(subjectId: String): List<GithubReactionGroup>? {
    val detail = detail ?: return null
    if (detail.id == subjectId) return detail.reactions.takeIf { detail.canReact }
    detail.parent
        ?.takeIf { it.id == subjectId }
        ?.let { parent ->
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
        DiscussionDetailViewModel(get(), route, account, get())
    }
}
