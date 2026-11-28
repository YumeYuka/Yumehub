package moe.yumeyuka.yumehub.feature.workdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestCheckRunDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewCommentDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.canMerge
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.PendingMutationSummary
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdate
import moe.yumeyuka.yumehub.data.repository.NotificationsRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailCommentChangedException
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.data.repository.WorkDetailTypeMismatchException
import moe.yumeyuka.yumehub.data.repository.WorkItemSubscription
import moe.yumeyuka.yumehub.data.repository.WorkItemSubscriptionRepository
import moe.yumeyuka.yumehub.data.repository.WorkMergeMessage
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.convergeGithubReactions
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.data.repository.withToggledReaction
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.locale.CurrentAppStrings
import moe.yumeyuka.yumehub.ui.component.MarkdownEditorState
import moe.yumeyuka.yumehub.ui.component.diffContainsLine
import moe.yumeyuka.yumehub.ui.navigation.EditorSessionKind
import moe.yumeyuka.yumehub.ui.navigation.EditorSession
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

internal enum class CollaborationEditorTarget {
    Comment,
    EditedComment,
    LineComment,
}

internal enum class CommentHideReason(val apiValue: String) {
    OffTopic("OFF_TOPIC"),
    Outdated("OUTDATED"),
    Resolved("RESOLVED"),
    Spam("SPAM"),
    Abuse("ABUSE"),
}

internal enum class CollaborationSection {
    Discussion,
    Reviews,
    Files,
    Commits;

    val title: String
        get() = label(CurrentAppStrings.current.WorkDetail)
}

internal enum class WorkStatusPanel {
    Reviews,
    Checks,
}

@Serializable
internal enum class PullRequestReviewEvent(val apiValue: String) {
    Comment("COMMENT"),
    Approve("APPROVE"),
    RequestChanges("REQUEST_CHANGES"),
}

@Serializable private data class ReviewForm(val body: String, val event: PullRequestReviewEvent)

internal sealed interface CollaborationEntry {
    val key: String

    data class Comment(val value: CollaborationCommentDto) : CollaborationEntry {
        override val key = "comment-${value.id}"
    }

    data class Review(val value: PullRequestReviewDto) : CollaborationEntry {
        override val key = "review-${value.id}"
    }

    data class File(val headSha: String, val value: PullRequestFileDto) : CollaborationEntry {
        override val key = "file-$headSha-${value.filename}"
    }

    data class Commit(val value: moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry) :
        CollaborationEntry {
        override val key = "commit-${value.id}"
    }
}

internal data class CommentEditTarget(val comment: CollaborationCommentDto, val draft: String)

internal data class ReviewLineTarget(
    val path: String,
    val line: Int,
    val side: String,
    val commitId: String,
    val patch: String,
)

internal data class CollaborationDetailUiState(
    val item: CollaborationItemDto? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val browserUrl: String? = null,
    val mergePhase: WritePhase = WritePhase.IDLE,
    val projectTitles: List<String>? = null,
    val section: CollaborationSection = CollaborationSection.Discussion,
    val statusPanel: WorkStatusPanel? = null,
    val composerTarget: CollaborationEditorTarget? = null,
    val canComment: Boolean = false,
    val canReview: Boolean = false,
    val canCommentOnLines: Boolean = false,
    val commentDraft: String = "",
    val reviewDraft: String = "",
    val reviewDraftReady: Boolean = false,
    val reviewEvent: PullRequestReviewEvent = PullRequestReviewEvent.Comment,
    val editingComment: CommentEditTarget? = null,
    val deleteComment: CollaborationCommentDto? = null,
    val reviewLine: ReviewLineTarget? = null,
    val lineCommentDraft: String = "",
    val lastLineComment: PullRequestReviewCommentDto? = null,
    val submitting: Boolean = false,
    val sendingInBackground: Boolean = false,
    val submittedSection: CollaborationSection? = null,
    val submissionError: String? = null,
    val uncertainOperations: List<PendingMutationSummary> = emptyList(),
    val subscription: WorkItemSubscription? = null,
    val subscriptionError: String? = null,
    val desiredSubscription: String? = null,
    val subscriptionUpdating: Boolean = false,
    val notificationDone: Boolean = false,
    val titleEditorOpen: Boolean = false,
    val titleDraft: String = "",
    val bodyEditorOpen: Boolean = false,
    val bodyDraft: String = "",
    val branchQuery: String = "",
    val branchChoices: List<String> = emptyList(),
    val branchLoading: Boolean = false,
    val branchCursor: String? = null,
    val branchError: Boolean = false,
) {
    fun unknownField(name: String): Boolean = uncertainOperations.any {
        it.kind == MutationKind.WORK_FIELD && it.target.endsWith(":$name")
    }

    val commentUncertain: Boolean
        get() = uncertainOperations.any {
            it.kind == MutationKind.COMMENT && it.target.endsWith(":new-comment")
        }

    val canSubmitReview: Boolean
        get() =
            canReview &&
                reviewDraftReady &&
                !loading &&
                !submitting &&
                uncertainOperations.none { it.kind == MutationKind.REVIEW } &&
                (validCommentBody(reviewDraft) ||
                    reviewDraft.isBlank() && reviewEvent == PullRequestReviewEvent.Approve)
}

internal class CollaborationDetailViewModel(
    repository: WorkDetailRepository,
    private val owner: String,
    private val repo: String,
    val kind: CollaborationKind,
    private val number: Int,
    private val viewerLogin: String,
    private val draftStore: CommentDraftStore,
    private val subscriptions: WorkItemSubscriptionRepository,
    private val notifications: NotificationsRepository,
    private val workspacesRepository: WorkspacesRepository,
    private val account: String,
) : ViewModel() {
    private val detail = repository.detail(viewerLogin, owner, repo, kind, number)
    val issueMetadata =
        IssueMetadataEditor(detail, viewModelScope) { error ->
            val strings = CurrentAppStrings.current.WorkDetail
            messageChannel.trySend(
                if (error is WorkDetailReviewChangedException) strings.PropertyChanged
                else strings.PropertySaveFailed,
            )
        }
    private val draftPrefix =
        "${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}/${kind.name}/$number"
    private val draftWrites =
        PendingDraftWrites(viewModelScope) { suffix, body ->
            draftStore.save(viewerLogin, "$draftPrefix/$suffix", body)
        }
    private val composerStates = mutableMapOf<CollaborationEditorTarget, MarkdownEditorState>()
    private var activeEditorSession: EditorSession? = null
    private var activeEditorTarget: CollaborationEditorTarget? = null
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var coordinator: MutationCoordinator? = null
    private var fieldObserversStarted = false
    private val reactionObservers = mutableMapOf<String, Job>()
    private var subscriptionObserver: Job? = null
    private var subscriptionJob: Job? = null
    private var subscriptionNodeId: String? = null
    private var subscriptionGeneration = 0L
    private var confirmedSubscription: WorkItemSubscription? = null
    private var confirmedDetailState: CachedDataState<CollaborationItemDto> = detail.state.value
    private var composerDraftJob: Job? = null
    private var commentDraftEdited = false
    private val mutableUiState =
        MutableStateFlow(CollaborationDetailUiState().withDetail(detail.state.value))
    val uiState = mutableUiState.asStateFlow()
    val reactionUpdates = detail.reactionUpdates()
    private var branchJob: Job? = null
    private var branchGeneration = 0L

    private fun titleKey() = detail.keys.title

    private fun bodyKey() = detail.keys.body

    private fun baseKey() = detail.keys.base

    private fun lockedKey() = detail.keys.locked

    private fun closeStateKey() = detail.keys.state

    private fun subscriptionKey(subjectId: String) =
        MutationKey(MutationKind.WORK_SUBSCRIPTION, subjectId, String.serializer())

    private fun reactionKey(subjectId: String) =
        MutationKey(
            MutationKind.REACTION,
            subjectId,
            ListSerializer(GithubReactionGroup.serializer()),
        )

    fun commentWrites(id: String) = detail.commentWrites(id)

    private fun canWriteComment(coord: MutationCoordinator, id: String): Boolean {
        val edit = coord.current(detail.keys.comment(id))
        val deletion = coord.currentCommand(detail.keys.deleteComment(id))
        return edit.phase != WritePhase.PENDING &&
            edit.phase != WritePhase.UNCERTAIN &&
            deletion.result == null &&
            deletion.phase != WritePhase.PENDING &&
            deletion.phase != WritePhase.UNCERTAIN
    }

    private fun newCommentKey() =
        MutationKey(
            MutationKind.COMMENT,
            "${detail.workTarget}:new-comment",
            CollaborationCommentDto.serializer(),
        )

    private fun lineCommentKey(target: ReviewLineTarget) =
        MutationKey(
            MutationKind.COMMENT,
            "${detail.workTarget}:${lineDraftKey(target)}",
            PullRequestReviewCommentDto.serializer(),
        )

    private fun reviewKey() =
        MutationKey(
            MutationKind.REVIEW,
            "${detail.workTarget}:review",
            PullRequestReviewDto.serializer(),
        )

    private suspend fun ensureCoordinator(): MutationCoordinator =
        coordinator
            ?: detail.mutations().also { coord ->
                coordinator = coord
                startDetailObservers(coord)
            }

    private fun startDetailObservers(coord: MutationCoordinator) {
        if (fieldObserversStarted) return
        fieldObserversStarted = true
        val refreshUiDetail: () -> Unit = {
            mutableUiState.update { it.withDetail(confirmedDetailState) }
        }
        listOf(titleKey(), bodyKey(), baseKey(), closeStateKey()).forEach { key ->
            viewModelScope.launch {
                coord.observe(key).collect { write ->
                    refreshUiDetail()
                    if (write.phase == WritePhase.REJECTED) {
                        write.message?.let(messageChannel::trySend)
                    }
                }
            }
        }
        viewModelScope.launch {
            coord.observe(lockedKey()).collect { write ->
                refreshUiDetail()
                if (write.phase == WritePhase.REJECTED) {
                    write.message?.let(messageChannel::trySend)
                }
            }
        }
        viewModelScope.launch {
            coord.observeCommand(detail.keys.merge).collect { cmd ->
                mutableUiState.update {
                    it.copy(mergePhase = cmd.phase).withDetail(confirmedDetailState)
                }
                if (cmd.phase == WritePhase.UNCERTAIN && cmd.result == null) {
                    coord.reconcileCommand(detail.keys.merge) { detail.checkMerge() }
                }
            }
        }
        WorkProperty.entries.forEach { property ->
            viewModelScope.launch {
                coord.observe(detail.keys.property(property)).collect { write ->
                    refreshUiDetail()
                    if (write.phase == WritePhase.REJECTED)
                        write.message?.let(messageChannel::trySend)
                }
            }
        }
        viewModelScope.launch { coord.observe(detail.keys.issueType).collect { refreshUiDetail() } }
        subscriptionNodeId?.let { observeSubscriptionSlot(coord, it) }
        confirmedDetailState.data?.takeIf(detail::accepts)?.let { item ->
            coord.seedFromCache(titleKey(), item.title)
            coord.seedFromCache(bodyKey(), item.body.orEmpty())
            item.baseRefName?.let { coord.seedFromCache(baseKey(), it) }
            coord.seedFromCache(lockedKey(), item.locked)
            coord.seedFromCache(closeStateKey(), item.state)
        }
        refreshUiDetail()
        viewModelScope.launch {
            coord.pending.collect { pending ->
                val prefix = detail.workTarget
                val subId = subscriptionNodeId
                val prefixWithSeparator = "$prefix:"
                val relevant = pending.filter { summary ->
                    summary.target == prefix ||
                        summary.target.startsWith(prefixWithSeparator) ||
                        (subId != null &&
                            summary.kind == MutationKind.WORK_SUBSCRIPTION &&
                            summary.target == subId)
                }
                mutableUiState.update { it.copy(uncertainOperations = relevant) }
            }
        }
    }

    private val restoreCommentDraft = viewModelScope.launch {
        val draft = draftStore.load(viewerLogin, "$draftPrefix/comment")
        if (!commentDraftEdited) mutableUiState.update { it.copy(commentDraft = draft) }
    }

    init {
        viewModelScope.launch {
            ensureCoordinator()
        }
        viewModelScope.launch {
            val encoded = draftStore.loadOrNull(viewerLogin, "$draftPrefix/review-form")
            val form =
                try {
                    if (encoded == null)
                        ReviewForm(
                            draftStore.load(viewerLogin, "$draftPrefix/review"),
                            PullRequestReviewEvent.Comment,
                        )
                    else
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            Json.decodeFromString<ReviewForm>(encoded)
                        }
                } catch (_: SerializationException) {
                    messageChannel.trySend(CurrentAppStrings.current.Common.MutationNotSubmitted)
                    return@launch
                }
            mutableUiState.update {
                if (it.reviewDraftReady) it
                else
                    it.copy(
                        reviewDraft = form.body,
                        reviewEvent = form.event,
                        reviewDraftReady = true,
                    )
            }
        }
    }

    fun openTitleEditor() {
        if (uiState.value.item?.viewerCanEdit == true && !uiState.value.submitting) {
            mutableUiState.update {
                it.copy(titleEditorOpen = true, titleDraft = it.item?.title.orEmpty())
            }
        }
    }

    fun takeTitleEditorSession(): EditorSession? {
        val state = uiState.value
        val item = state.item ?: return null
        if (!state.titleEditorOpen || state.submitting) return null
        lateinit var session: EditorSession
        session =
            EditorSession(
                title = CurrentAppStrings.current.WorkDetail.EditTitle,
                kind = EditorSessionKind.Markdown,
                source = item.title,
                onSourceChange = { source ->
                    mutableUiState.update { it.copy(titleDraft = source) }
                },
                onSubmit = {
                    updateTitleFromEditor(uiState.value.titleDraft)
                    mutableUiState.update { it.copy(titleEditorOpen = false, titleDraft = "") }
                    session.complete()
                },
                submitLabel = CurrentAppStrings.current.WorkDetail.SaveChanges,
                onDismiss = {
                    mutableUiState.update { it.copy(titleEditorOpen = false, titleDraft = "") }
                },
            )
        session.update(
            source = item.title,
            editable = true,
            submitting = false,
            submitEnabled = true,
            error = null,
        )
        return session
    }

    fun openBodyEditor() {
        val state = uiState.value
        if (state.item?.viewerCanEdit == true && !state.submitting && !state.unknownField("body")) {
            mutableUiState.update {
                it.copy(bodyEditorOpen = true, bodyDraft = it.item?.body.orEmpty())
            }
        }
    }

    fun takeBodyEditorSession(): EditorSession? {
        val state = uiState.value
        val item = state.item ?: return null
        if (!state.bodyEditorOpen || state.submitting) return null
        lateinit var session: EditorSession
        session =
            EditorSession(
                title = CurrentAppStrings.current.WorkDetail.EditDescription,
                kind = EditorSessionKind.Markdown,
                source = item.body.orEmpty(),
                originalSource = item.body,
                pageUrl = state.browserUrl,
                repositoryRootUrl = "https://github.com/$owner/$repo",
                onSourceChange = { source ->
                    mutableUiState.update { it.copy(bodyDraft = source) }
                },
                onSubmit = {
                    updateBodyFromEditor(uiState.value.bodyDraft)
                    mutableUiState.update { it.copy(bodyEditorOpen = false, bodyDraft = "") }
                    session.complete()
                },
                submitLabel = CurrentAppStrings.current.WorkDetail.SaveChanges,
                onDismiss = {
                    mutableUiState.update { it.copy(bodyEditorOpen = false, bodyDraft = "") }
                },
            )
        session.update(
            source = item.body.orEmpty(),
            editable = true,
            submitting = false,
            submitEnabled = true,
            error = null,
        )
        return session
    }

    fun searchBaseBranches(query: String) {
        if (uiState.value.item == null || uiState.value.submitting) return
        val generation = ++branchGeneration
        branchJob?.cancel()
        mutableUiState.update {
            it.copy(
                branchQuery = query,
                branchChoices = if (it.branchQuery == query) it.branchChoices else emptyList(),
                branchLoading = true,
                branchError = false,
                branchCursor = null,
            )
        }
        branchJob = viewModelScope.launch {
            try {
                delay(250)
                val page = workspacesRepository.loadBranches(account, owner, repo, query = query)
                if (generation == branchGeneration)
                    mutableUiState.update {
                        it.copy(
                            branchChoices = page.branches,
                            branchCursor = page.endCursor,
                            branchLoading = false,
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == branchGeneration)
                    mutableUiState.update {
                        it.copy(
                            branchLoading = false,
                            branchError = true,
                            branchChoices =
                                if (error.invalidatesCachedContent()) emptyList()
                                else it.branchChoices,
                        )
                    }
            }
        }
    }

    fun loadMoreBaseBranches() {
        val state = uiState.value
        val cursor = state.branchCursor ?: return
        if (state.branchLoading || state.submitting || branchJob?.isActive == true) return
        val generation = branchGeneration
        branchJob = viewModelScope.launch {
            try {
                val page =
                    workspacesRepository.loadBranches(
                        account,
                        owner,
                        repo,
                        after = cursor,
                        query = state.branchQuery,
                    )
                if (generation == branchGeneration)
                    mutableUiState.update {
                        it.copy(
                            branchChoices = (it.branchChoices + page.branches).distinct(),
                            branchCursor = page.endCursor,
                            branchError = false,
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == branchGeneration)
                    mutableUiState.update {
                        it.copy(
                            branchError = true,
                            branchChoices =
                                if (error.invalidatesCachedContent()) emptyList()
                                else it.branchChoices,
                            branchCursor =
                                it.branchCursor.takeUnless { error.invalidatesCachedContent() },
                        )
                    }
            }
        }
    }

    fun chooseBaseBranch(branch: String) {
        if (branch.isBlank()) return
        editPullRequest("baseRefName", branch.removePrefix("refs/heads/"))
        mutableUiState.update {
            it.copy(branchQuery = "", branchChoices = emptyList(), branchCursor = null)
        }
    }

    val timeline by lazy { detail.timeline(viewModelScope) }
    private val reviewEntries =
        detail
            .reviews()
            .map { page ->
                page.map<PullRequestReviewDto, CollaborationEntry> { CollaborationEntry.Review(it) }
            }
            .cachedIn(viewModelScope)

    fun checks(head: String): Flow<PagingData<PullRequestCheckRunDto>> = detail.checks(head)

    fun checksRevalidation(head: String) = detail.revalidation(detail.checksPagingKey(head))

    fun retryChecksRevalidation(head: String) =
        detail.retryRevalidation(detail.checksPagingKey(head))

    fun entriesRevalidation(section: CollaborationSection, head: String?) =
        when (section) {
            CollaborationSection.Discussion -> null
            CollaborationSection.Reviews -> detail.revalidation(detail.reviewsPagingKey)
            CollaborationSection.Files ->
                head?.let { detail.revalidation(detail.filesPagingKey(it)) }
            CollaborationSection.Commits ->
                head?.let { detail.revalidation(detail.commitsPagingKey(it)) }
        }

    fun retryEntriesRevalidation(section: CollaborationSection, head: String?) {
        when (section) {
            CollaborationSection.Discussion -> Unit
            CollaborationSection.Reviews -> detail.retryRevalidation(detail.reviewsPagingKey)
            CollaborationSection.Files ->
                head?.let { detail.retryRevalidation(detail.filesPagingKey(it)) }
            CollaborationSection.Commits ->
                head?.let { detail.retryRevalidation(detail.commitsPagingKey(it)) }
        }
    }

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            detail.state.collect { cached ->
                confirmedDetailState = cached
                val oldLine = uiState.value.reviewLine
                if (oldLine != null && oldLine.commitId != cached.data?.head?.sha) {
                    draftWrites.flush(lineDraftKey(oldLine))
                }
                mutableUiState.update { it.withDetail(cached) }
                uiState.value.item?.nodeId?.takeIf(String::isNotBlank)?.let { id ->
                    if (subscriptionNodeId != id) {
                        subscriptionNodeId = id
                        coordinator?.let { observeSubscriptionSlot(it, id) }
                        loadSubscription()
                    }
                }
            }
        }
        // 首屏只拉详情与当前分节第一页；属性（一次 load 是节点查询加若干分页查询）留到面板首次
        // 打开时按需取。头部项目标签只多发一次可缺省的项目连接查询。
        detail.refresh()
        viewModelScope.launch {
            detail.headerProjects()
        }
        viewModelScope.launch {
            uiState.collect { state ->
                composerStates.forEach { (target, editor) ->
                    editor.syncExternal(editorSource(state, target))
                }
                syncEditorSession(state)
            }
        }
    }

    fun composerEditorState(target: CollaborationEditorTarget): MarkdownEditorState =
        composerStates.getOrPut(target) { MarkdownEditorState(editorSource(uiState.value, target)) }

    fun changeComposerSource(target: CollaborationEditorTarget, source: String) {
        if (uiState.value.composerTarget != target) return
        when (target) {
            CollaborationEditorTarget.Comment -> changeCommentDraft(source)
            CollaborationEditorTarget.EditedComment -> changeEditedComment(source)
            CollaborationEditorTarget.LineComment -> changeLineComment(source)
        }
    }

    fun takeEditorSession(): EditorSession? {
        if (uiState.value.submitting) return null
        val target = uiState.value.composerTarget ?: return null
        if (activeEditorSession != null) return null
        val state = uiState.value
        val strings = CurrentAppStrings.current.WorkDetail
        lateinit var session: EditorSession
        session =
            EditorSession(
                title =
                    when (target) {
                        CollaborationEditorTarget.Comment -> strings.Comment
                        CollaborationEditorTarget.EditedComment -> strings.EditComment
                        CollaborationEditorTarget.LineComment -> strings.LineComment
                    },
                kind = EditorSessionKind.Markdown,
                source = editorSource(state, target),
                originalSource =
                    state.editingComment?.comment?.body.takeIf {
                        target == CollaborationEditorTarget.EditedComment
                    },
                pageUrl = state.browserUrl,
                repositoryRootUrl = "https://github.com/$owner/$repo",
                onSourceChange = { source ->
                    if (activeEditorSession === session) changeComposerSource(target, source)
                },
                onSubmit = {
                    if (activeEditorSession === session && uiState.value.composerTarget == target) {
                        when (target) {
                            CollaborationEditorTarget.Comment -> submitComment()
                            CollaborationEditorTarget.EditedComment -> saveEditedComment()
                            CollaborationEditorTarget.LineComment -> submitLineComment()
                        }
                    }
                },
                headerContent =
                    if (target == CollaborationEditorTarget.LineComment)
                        ({ CollaborationEditorHeader(this) })
                    else null,
                submitLabel =
                    when (target) {
                        CollaborationEditorTarget.EditedComment -> strings.SaveChanges
                        else -> strings.Publish
                    },
                onDismiss = {
                    if (activeEditorSession === session) {
                        activeEditorSession = null
                        activeEditorTarget = null
                        dismissComposer()
                    }
                },
            )
        session.adoptMarkdownState(composerEditorState(target))
        activeEditorTarget = target
        activeEditorSession = session
        syncEditorSession(state)
        return session
    }

    private fun syncEditorSession(state: CollaborationDetailUiState) {
        val session = activeEditorSession ?: return
        val target = activeEditorTarget ?: return
        val source = editorSource(state, target)
        val validTarget =
            state.composerTarget == target &&
                when (target) {
                    CollaborationEditorTarget.EditedComment -> state.editingComment != null
                    CollaborationEditorTarget.LineComment -> state.reviewLine != null
                    else -> true
                }
        val allowed =
            validTarget &&
                !state.loading &&
                when (target) {
                    CollaborationEditorTarget.Comment ->
                        !state.commentUncertain && state.canComment && validCommentBody(source)
                    CollaborationEditorTarget.EditedComment ->
                        state.editingComment?.let {
                            canManageComment(it.comment) &&
                                validCommentBody(source) &&
                                source != it.comment.body.orEmpty()
                        } == true
                    CollaborationEditorTarget.LineComment ->
                        state.canCommentOnLines &&
                            state.reviewLine?.commitId == state.item?.head?.sha &&
                            validCommentBody(source)
                }
        session.update(
            source = if (validTarget) source else session.source,
            editable = validTarget && !state.submitting,
            submitting = state.submitting && !state.sendingInBackground,
            submitEnabled = allowed && !state.submitting,
            error = state.submissionError,
        )
        if (!validTarget && !state.submitting) session.complete()
    }

    private fun editorSource(
        state: CollaborationDetailUiState,
        target: CollaborationEditorTarget,
    ): String =
        when (target) {
            CollaborationEditorTarget.Comment -> state.commentDraft
            CollaborationEditorTarget.EditedComment -> state.editingComment?.draft.orEmpty()
            CollaborationEditorTarget.LineComment -> state.lineCommentDraft
        }

    fun entriesFor(
        section: CollaborationSection,
        head: String,
    ): Flow<PagingData<CollaborationEntry>>? =
        when (section) {
            CollaborationSection.Discussion -> null
            CollaborationSection.Reviews -> reviewEntries
            CollaborationSection.Files ->
                head.takeIf(String::isNotBlank)?.let { sha ->
                    detail.files(sha).map { page ->
                        page.map<PullRequestFileDto, CollaborationEntry> {
                            CollaborationEntry.File(sha, it)
                        }
                    }
                }
            CollaborationSection.Commits ->
                head.takeIf(String::isNotBlank)?.let { sha ->
                    detail.commits(sha).map { page ->
                        page.map<
                            moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry,
                            CollaborationEntry,
                        > {
                            CollaborationEntry.Commit(it)
                        }
                    }
                }
        }

    private fun CollaborationDetailUiState.withDetail(
        cached: CachedDataState<CollaborationItemDto>,
    ): CollaborationDetailUiState {
        val rawItem = cached.data?.takeIf(detail::accepts)
        val coord = coordinator
        val item =
            if (rawItem == null || coord == null) {
                rawItem
            } else {
                val projTitle = coord.current(titleKey()).visible ?: rawItem.title
                val projBody = coord.current(bodyKey()).visible
                val projBase = coord.current(baseKey()).visible ?: rawItem.baseRefName
                val projLocked = coord.current(lockedKey()).visible ?: rawItem.locked
                val projState = coord.current(closeStateKey()).visible ?: rawItem.state
                val bodyChanged = projBody != null && projBody != rawItem.body.orEmpty()
                val projected = detail.projectProperties(rawItem)
                projected.copy(
                    title = projTitle,
                    body = if (bodyChanged) projBody else rawItem.body,
                    bodyHtml = if (bodyChanged) null else rawItem.bodyHtml,
                    baseRefName = projBase,
                    locked = projLocked,
                    state = if (rawItem.merged) "closed" else projState,
                )
            }
        val failure =
            cached.error
                ?: if (cached.data != null && rawItem == null) {
                    WorkDetailTypeMismatchException()
                } else null
        val canComment =
            item != null &&
                viewerLogin.isNotBlank() &&
                !item.locked &&
                !cached.loading &&
                failure == null
        val openPullRequest =
            kind == CollaborationKind.PullRequest &&
                item != null &&
                item.state == "open" &&
                !item.head?.sha.isNullOrBlank()
        return copy(
            item = item,
            projectTitles =
                coord?.current(detail.keys.property(WorkProperty.Projects))?.visible?.map {
                    it.title
                },
            loading = cached.loading,
            error = failure?.let(::collaborationErrorMessage),
            browserUrl = githubBrowserUrl(item?.htmlUrl),
            canComment = canComment,
            canReview =
                canComment &&
                    openPullRequest &&
                    !item.draft &&
                    item.user?.login?.equals(viewerLogin, ignoreCase = true) == false,
            canCommentOnLines = canComment && openPullRequest,
            composerTarget =
                composerTarget.takeUnless {
                    it == CollaborationEditorTarget.LineComment &&
                        reviewLine?.commitId != item?.head?.sha
                },
            reviewLine = reviewLine.takeIf { it?.commitId == item?.head?.sha },
            lastLineComment = lastLineComment.takeIf { this.item?.head?.sha == item?.head?.sha },
        )
    }

    fun retryDetail() {
        if (!uiState.value.submitting) detail.refresh(force = true)
    }

    fun refresh() {
        if (uiState.value.submitting) return
        detail.refresh(force = true)
        detail.refreshEntries()
        loadSubscription(force = true)
    }

    /** 后台核对：只重新拉取详情与订阅，保留已加载的时间线、评论与 diff 分页。 */
    fun revalidate() {
        if (uiState.value.submitting) return
        detail.refresh(force = true)
        loadSubscription(force = true)
    }

    private fun observeSubscriptionSlot(coord: MutationCoordinator, subjectId: String) {
        subscriptionObserver?.cancel()
        subscriptionObserver = viewModelScope.launch {
            coord.observe(subscriptionKey(subjectId)).collect { write ->
                val projected = write.visible
                mutableUiState.update { current ->
                    val baseSub = confirmedSubscription ?: current.subscription
                    val updatedSub =
                        if (projected != null && baseSub != null) {
                            baseSub.copy(state = projected)
                        } else baseSub
                    current.copy(
                        subscription = updatedSub,
                        desiredSubscription =
                            write.desired.takeIf { write.phase == WritePhase.PENDING },
                        subscriptionUpdating = write.phase == WritePhase.PENDING,
                        subscriptionError =
                            if (
                                write.phase == WritePhase.REJECTED ||
                                    write.phase == WritePhase.UNCERTAIN
                            ) {
                                write.message ?: current.subscriptionError
                            } else null,
                    )
                }
                if (write.phase == WritePhase.REJECTED) {
                    write.message?.let(messageChannel::trySend)
                }
            }
        }
    }

    fun loadSubscription(force: Boolean = false, notifyFailure: Boolean = true) {
        val id = subscriptionNodeId ?: return
        if (uiState.value.subscriptionUpdating) return
        val generation = ++subscriptionGeneration
        subscriptionJob?.cancel()
        mutableUiState.update { it.copy(subscriptionError = null) }
        subscriptionJob = viewModelScope.launch {
            try {
                val coord = ensureCoordinator()
                observeSubscriptionSlot(coord, id)
                val stamp = coord.beginRead(subscriptionKey(id))
                val loaded = subscriptions.load(viewerLogin, id, force)
                confirmedSubscription = loaded
                coord.publishConfirmed(subscriptionKey(id), stamp, loaded.state)
                if (generation == subscriptionGeneration) {
                    val slot = coord.current(subscriptionKey(id))
                    val effective = slot.visible
                    val shown =
                        if (effective != null) {
                            loaded.copy(state = effective)
                        } else loaded
                    mutableUiState.update { current ->
                        if (generation == subscriptionGeneration) current.copy(subscription = shown)
                        else current
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == subscriptionGeneration) {
                    val message = collaborationErrorMessage(error)
                    mutableUiState.update { it.copy(subscriptionError = message) }
                    if (notifyFailure) messageChannel.trySend(message)
                }
            }
        }
    }

    fun updateSubscription(targetState: String) {
        require(targetState in setOf("SUBSCRIBED", "UNSUBSCRIBED", "IGNORED"))
        val state = uiState.value
        val subjectId = state.item?.nodeId?.takeIf(String::isNotBlank) ?: return
        if (state.subscription?.canSubscribe == false) return
        val generation = ++subscriptionGeneration
        subscriptionJob?.cancel()
        mutableUiState.update { it.copy(subscriptionError = null) }
        viewModelScope.launch {
            val coord =
                try {
                    ensureCoordinator()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = collaborationErrorMessage(error)
                    mutableUiState.update { it.copy(subscriptionError = message) }
                    messageChannel.trySend(message)
                    return@launch
                }
            observeSubscriptionSlot(coord, subjectId)
            val key = subscriptionKey(subjectId)
            val confirmed =
                confirmedSubscription
                    ?: state.subscription
                    ?: try {
                        val stamp = coord.beginRead(key)
                        subscriptions
                            .load(viewerLogin, subjectId, force = state.subscriptionError != null)
                            .also {
                                confirmedSubscription = it
                                coord.publishConfirmed(key, stamp, it.state)
                            }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (generation == subscriptionGeneration) {
                            val message = collaborationErrorMessage(error)
                            mutableUiState.update { it.copy(subscriptionError = message) }
                            messageChannel.trySend(message)
                        }
                        return@launch
                    }
            if (!confirmed.canSubscribe) {
                mutableUiState.update { it.copy(subscription = confirmed) }
                return@launch
            }
            val currentState = coord.current(key).visible ?: confirmed.state
            if (currentState == targetState) return@launch
            val accepted =
                coord.submit(
                    key = key,
                    confirmed = confirmed.state,
                    desired = targetState,
                    tags = detail.mutationTags,
                    send = { desired ->
                        val updated = subscriptions.setSubscription(viewerLogin, subjectId, desired)
                        confirmedSubscription = updated
                        updated.state
                    },
                    readBack = {
                        val reloaded = subscriptions.load(viewerLogin, subjectId, force = true)
                        confirmedSubscription = reloaded
                        reloaded.state
                    },
                )
            if (!accepted) {
                val message =
                    "${CurrentAppStrings.current.Common.MutationNotSubmitted}: ${CurrentAppStrings.current.Common.MutationLimit}"
                mutableUiState.update { it.copy(subscriptionError = message) }
                messageChannel.trySend(message)
            }
        }
    }

    fun completeNotification() {
        val state = uiState.value
        if (state.item == null || state.notificationDone || state.composerTarget != null) return
        notifications.enqueueWorkDone(viewerLogin, WorkTarget(owner, repo, number, kind))
        mutableUiState.update { it.copy(notificationDone = true) }
    }

    fun selectSection(section: CollaborationSection) {
        if (kind == CollaborationKind.PullRequest || section == CollaborationSection.Discussion) {
            if (uiState.value.section != section) composerDraftJob?.cancel()
            mutableUiState.update { it.copy(section = section) }
        }
    }

    fun openComposer(target: CollaborationEditorTarget = CollaborationEditorTarget.Comment) {
        composerDraftJob?.cancel()
        if (target == CollaborationEditorTarget.Comment && !restoreCommentDraft.isCompleted) {
            composerDraftJob = viewModelScope.launch {
                restoreCommentDraft.join()
                showComposer(target)
            }
        } else showComposer(target)
    }

    private fun showComposer(target: CollaborationEditorTarget) {
        val state = uiState.value
        if (
            state.submitting ||
                (target == CollaborationEditorTarget.Comment && state.commentUncertain) ||
                state.loading
        )
            return
        val allowed =
            when (target) {
                CollaborationEditorTarget.Comment ->
                    state.canComment || state.commentDraft.isNotEmpty()
                CollaborationEditorTarget.EditedComment -> state.editingComment != null
                CollaborationEditorTarget.LineComment -> state.reviewLine != null
            }
        if (allowed) mutableUiState.update { it.copy(composerTarget = target, statusPanel = null) }
    }

    private fun dismissComposer() {
        val state = uiState.value
        if (state.submitting) return
        when (state.composerTarget) {
            CollaborationEditorTarget.EditedComment -> cancelEditComment()
            CollaborationEditorTarget.LineComment -> cancelLineComment()
            else -> Unit
        }
        mutableUiState.update { it.copy(composerTarget = null) }
    }

    fun showStatusPanel(panel: WorkStatusPanel?) {
        if (panel != null) composerDraftJob?.cancel()
        mutableUiState.update {
            if (it.composerTarget == null) it.copy(statusPanel = panel) else it
        }
    }

    fun changeCommentDraft(draft: String) {
        if (uiState.value.submitting) return
        commentDraftEdited = true
        mutableUiState.update { it.copy(commentDraft = draft) }
        draftWrites.schedule("comment", draft)
    }

    fun changeReviewDraft(draft: String) {
        val state = uiState.value
        if (state.submitting || !state.reviewDraftReady) return
        mutableUiState.update { it.copy(reviewDraft = draft) }
        saveReviewDraft(ReviewForm(draft, state.reviewEvent))
    }

    fun canManageComment(comment: CollaborationCommentDto): Boolean =
        comment.user?.login?.equals(viewerLogin, ignoreCase = true) == true &&
            uiState.value.item != null &&
            uiState.value.error == null &&
            !uiState.value.submitting &&
            !uiState.value.loading

    fun canHideComment(comment: CollaborationCommentDto): Boolean =
        comment.viewerCanMinimize &&
            !comment.isMinimized &&
            uiState.value.item != null &&
            uiState.value.error == null &&
            !uiState.value.submitting &&
            !uiState.value.loading

    fun toggleBodyReaction(content: GithubReactionContent, reacted: Boolean) {
        val item = uiState.value.item ?: return
        toggleReaction(
            subjectId = item.nodeId,
            canReact = item.viewerCanReact,
            sourceReactions = item.reactions,
            content = content,
            reacted = reacted,
        )
    }

    fun toggleReaction(
        comment: CollaborationCommentDto,
        content: GithubReactionContent,
        reacted: Boolean,
    ) {
        toggleReaction(
            subjectId = comment.nodeId,
            canReact = comment.viewerCanReact,
            sourceReactions = comment.reactions,
            content = content,
            reacted = reacted,
        )
    }

    private fun toggleReaction(
        subjectId: String?,
        canReact: Boolean,
        sourceReactions: List<GithubReactionGroup>,
        content: GithubReactionContent,
        reacted: Boolean,
    ) {
        if (subjectId == null || !canReact) return
        val key = reactionKey(subjectId)
        val slotReactions = coordinator?.current(key)?.visible
        val previous = reactionUpdates.current(subjectId, sourceReactions)
        val reactions = slotReactions ?: previous?.reactions ?: sourceReactions
        if (reactions.any { it.content == content && it.viewerReacted } != reacted) return
        val desired = reactions.withToggledReaction(content)
        val pending = GithubReactionUpdate(sourceReactions, desired, pending = true)
        reactionUpdates.set(subjectId, pending)
        viewModelScope.launch {
            val coord =
                try {
                    ensureCoordinator()
                } catch (error: CancellationException) {
                    reactionUpdates.set(subjectId, previous)
                    throw error
                } catch (_: Exception) {
                    reactionUpdates.set(subjectId, previous)
                    messageChannel.trySend(CurrentAppStrings.current.Activity.ActionFailed)
                    return@launch
                }
            if (reactionObservers[subjectId]?.isActive != true) {
                reactionObservers[subjectId] = viewModelScope.launch {
                    coord.observe(key).collect { write ->
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
                        if (
                            write.phase == WritePhase.REJECTED ||
                                write.phase == WritePhase.UNCERTAIN
                        ) {
                            messageChannel.trySend(CurrentAppStrings.current.Activity.ActionFailed)
                        }
                    }
                }
            }
            val accepted =
                coord.submit(
                    key = key,
                    confirmed = sourceReactions,
                    desired = desired,
                    tags = detail.mutationTags,
                    send = { nextDesired ->
                        val baseline = coord.current(key).confirmed ?: sourceReactions
                        convergeGithubReactions(
                            baseline,
                            nextDesired,
                            { coord.confirmStep(key, it) },
                        ) { targetContent, currentReacted ->
                            detail.toggleReaction(subjectId, targetContent, currentReacted)
                        }
                    },
                    readBack = {
                        if (subjectId == uiState.value.item?.nodeId) {
                            detail.readDetail().reactions
                        } else {
                            detail.readComment(subjectId).reactions
                        }
                    },
                )
            if (!accepted) {
                reactionUpdates.set(subjectId, previous)
                messageChannel.trySend(
                    "${CurrentAppStrings.current.Common.MutationNotSubmitted}: ${CurrentAppStrings.current.Common.MutationLimit}",
                )
            }
        }
    }

    fun editComment(comment: CollaborationCommentDto) {
        if (!canManageComment(comment)) return
        uiState.value.editingComment?.let { draftWrites.flush("edit/${it.comment.id}") }
        composerDraftJob?.cancel()
        composerDraftJob = viewModelScope.launch {
            val restored = draftStore.loadOrNull(viewerLogin, "$draftPrefix/edit/${comment.id}")
            if (!canManageComment(comment)) return@launch
            mutableUiState.update {
                it.copy(
                    editingComment = CommentEditTarget(comment, restored ?: comment.body.orEmpty()),
                    composerTarget = CollaborationEditorTarget.EditedComment,
                    section = CollaborationSection.Discussion,
                    submissionError = null,
                )
            }
        }
    }

    fun changeEditedComment(draft: String) {
        val state = uiState.value
        val target = state.editingComment ?: return
        if (state.submitting) return
        mutableUiState.update { it.copy(editingComment = target.copy(draft = draft)) }
        draftWrites.schedule("edit/${target.comment.id}", draft)
    }

    fun cancelEditComment() {
        val state = uiState.value
        if (state.submitting) return
        state.editingComment?.let { draftWrites.flush("edit/${it.comment.id}") }
        mutableUiState.update { it.copy(editingComment = null) }
    }

    fun requestDeleteComment(comment: CollaborationCommentDto) {
        if (canManageComment(comment)) mutableUiState.update { it.copy(deleteComment = comment) }
    }

    fun hideComment(comment: CollaborationCommentDto, reason: CommentHideReason) {
        if (!canHideComment(comment)) return
        val commentId = comment.nodeId?.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            val coord = ensureCoordinator()
            if (!canWriteComment(coord, commentId)) return@launch
            val confirmed = comment
            val desired = confirmed.copy(isMinimized = true, minimizedReason = reason.apiValue)
            val accepted =
                coord.submit(
                    key = detail.keys.comment(commentId),
                    confirmed = confirmed,
                    desired = desired,
                    tags = detail.mutationTags,
                    send = {
                        detail.minimizeComment(comment, reason.apiValue)
                        try {
                            detail.readComment(commentId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IOException("Comment visibility could not be read back", e)
                        }
                    },
                    readBack = { detail.readComment(commentId) },
                )
            if (!accepted) {
                messageChannel.trySend(
                    "${CurrentAppStrings.current.Common.MutationNotSubmitted}: ${CurrentAppStrings.current.Common.MutationLimit}",
                )
            }
        }
    }

    fun quoteComment(comment: CollaborationCommentDto) =
        quoteBody(comment.user?.login, comment.body)

    fun quoteBody(author: String?, body: String?) {
        composerDraftJob?.cancel()
        composerDraftJob = viewModelScope.launch {
            restoreCommentDraft.join()
            appendQuote(author, body)
        }
    }

    private fun appendQuote(author: String?, body: String?) {
        val state = uiState.value
        if (!state.canComment || state.loading || state.submitting || state.commentUncertain) return
        val quote = buildList {
            author?.takeIf(String::isNotBlank)?.let { add("> @$it") }
            addAll(
                body.orEmpty().lineSequence().map { line ->
                    if (line.isEmpty()) ">" else "> $line"
                },
            )
        }
            .joinToString("\n")
        val draft = buildString {
            if (quote.isNotBlank()) append(quote).append("\n\n")
            append(state.commentDraft)
        }
        commentDraftEdited = true
        mutableUiState.update {
            it.copy(
                commentDraft = draft,
                composerTarget = CollaborationEditorTarget.Comment,
                section = CollaborationSection.Discussion,
                submissionError = null,
            )
        }
        draftWrites.schedule("comment", draft)
    }

    fun dismissDeleteComment() {
        if (!uiState.value.submitting) mutableUiState.update { it.copy(deleteComment = null) }
    }

    fun confirmDeleteComment() {
        val state = uiState.value
        val comment = state.deleteComment ?: return
        val commentId = comment.nodeId?.takeIf(String::isNotBlank) ?: return
        if (!canManageComment(comment)) return
        mutableUiState.update { it.copy(deleteComment = null) }
        viewModelScope.launch {
            val coord = ensureCoordinator()
            if (!canWriteComment(coord, commentId)) return@launch
            val key = detail.keys.deleteComment(commentId)
            val receipt =
                coord.submitCommand(
                    key,
                    detail.mutationTags,
                    onConfirmed = { clearDraft("edit/${comment.id}") },
                    send = {
                        detail.deleteComment(comment)
                        CommandAccepted
                    },
                    readBack = { null },
                )
            val outcome = receipt?.await() ?: coord.currentCommand(key)
            when {
                receipt != null && outcome.phase == WritePhase.IDLE ->
                    mutableUiState.update {
                        it.copy(
                            editingComment =
                                it.editingComment.takeUnless { edit ->
                                    edit?.comment?.id == comment.id
                                },
                        )
                    }
                outcome.phase == WritePhase.UNCERTAIN ->
                    messageChannel.trySend(CurrentAppStrings.current.Common.MutationUncertain)
                outcome.phase != WritePhase.PENDING ->
                    messageChannel.trySend(
                        outcome.message ?: CurrentAppStrings.current.Common.MutationNotSubmitted,
                    )
            }
        }
    }

    fun saveEditedComment() {
        val state = uiState.value
        val target = state.editingComment ?: return
        val commentId = target.comment.nodeId?.takeIf(String::isNotBlank) ?: return
        if (
            !canManageComment(target.comment) ||
                !validCommentBody(target.draft) ||
                target.draft == target.comment.body.orEmpty()
        )
            return
        val submittedEditor = activeEditorSession
        val background = submittedEditor != null
        viewModelScope.launch {
            draftWrites.discard("edit/${target.comment.id}")
            val draftSaved =
                draftStore.saveConfirmed(
                    viewerLogin,
                    "$draftPrefix/edit/${target.comment.id}",
                    target.draft,
                )
            if (!draftSaved) {
                messageChannel.trySend(CurrentAppStrings.current.WorkDetail.PropertySaveFailed)
                return@launch
            }
            val coord = ensureCoordinator()
            if (!canWriteComment(coord, commentId)) return@launch
            if (background) {
                submittedEditor?.complete()
                mutableUiState.update { it.copy(composerTarget = null, editingComment = null) }
            }
            val confirmed = target.comment
            val desired = confirmed.copy(body = target.draft, bodyHtml = null)
            val accepted =
                coord.submit(
                    key = detail.keys.comment(commentId),
                    confirmed = confirmed,
                    desired = desired,
                    tags = detail.mutationTags,
                    send = { req ->
                        val updated = detail.updateComment(target.comment, req.body.orEmpty())
                        draftStore.removeIfUnchanged(
                            viewerLogin,
                            "$draftPrefix/edit/${target.comment.id}",
                            req.body.orEmpty(),
                        )
                        if (!background) {
                            mutableUiState.update {
                                it.copy(composerTarget = null, editingComment = null)
                            }
                        }
                        updated
                    },
                    readBack = { detail.readComment(commentId) },
                )
            if (!accepted) {
                messageChannel.trySend(
                    "${CurrentAppStrings.current.Common.MutationNotSubmitted}: ${CurrentAppStrings.current.Common.MutationLimit}",
                )
            }
        }
    }

    fun selectReviewLine(file: PullRequestFileDto, commit: String, oldLine: Int?, newLine: Int?) {
        val state = uiState.value
        if (
            commit != state.item?.head?.sha ||
                !state.canCommentOnLines ||
                state.submitting ||
                state.loading
        )
            return
        val side = if (newLine != null) "RIGHT" else "LEFT"
        val line = newLine ?: oldLine ?: return
        val path = file.filename
        val patch = file.patch ?: return
        if (!diffContainsLine(patch, line, side)) return
        val target = ReviewLineTarget(path, line, side, commit, patch)
        if (state.reviewLine == target) return
        state.reviewLine?.let { draftWrites.flush(lineDraftKey(it)) }
        composerDraftJob?.cancel()
        composerDraftJob = viewModelScope.launch {
            val draft = draftStore.load(viewerLogin, "$draftPrefix/${lineDraftKey(target)}")
            val current = uiState.value
            if (
                current.item?.head?.sha != commit ||
                    !current.canCommentOnLines ||
                    current.submitting ||
                    current.uncertainOperations.any { it.target == lineCommentKey(target).target }
            )
                return@launch
            mutableUiState.update {
                it.copy(
                    reviewLine = target,
                    composerTarget = CollaborationEditorTarget.LineComment,
                    lineCommentDraft = draft,
                    lastLineComment = null,
                    submissionError = null,
                )
            }
        }
    }

    fun changeLineComment(draft: String) {
        val state = uiState.value
        val target = state.reviewLine ?: return
        if (state.submitting) return
        mutableUiState.update { it.copy(lineCommentDraft = draft) }
        draftWrites.schedule(lineDraftKey(target), draft)
    }

    fun cancelLineComment() {
        val state = uiState.value
        if (state.submitting) return
        state.reviewLine?.let { draftWrites.flush(lineDraftKey(it)) }
        mutableUiState.update { it.copy(reviewLine = null) }
    }

    fun submitLineComment() {
        val state = uiState.value
        val target = state.reviewLine ?: return
        val draft = state.lineCommentDraft
        if (
            !state.canCommentOnLines ||
                state.submitting ||
                state.loading ||
                !validCommentBody(draft)
        )
            return
        if (!diffContainsLine(target.patch, target.line, target.side)) return
        submitCommand(
            section = CollaborationSection.Files,
            key = lineCommentKey(target),
            draftSuffix = lineDraftKey(target),
            draftText = draft,
            onSuccess = { created ->
                mutableUiState.update {
                    if (it.reviewLine == target && it.lineCommentDraft == draft)
                        it.copy(reviewLine = null, lineCommentDraft = "", lastLineComment = created)
                    else it.copy(lastLineComment = created)
                }
            },
            send = {
                detail.createLineComment(
                    draft,
                    target.commitId,
                    target.path,
                    target.line,
                    target.side,
                )
            },
            readBack = { null },
        )
    }

    private fun lineDraftKey(target: ReviewLineTarget): String =
        "line/${target.commitId}/${target.path.length}:${target.path}/${target.side}/${target.line}"

    private fun clearDraft(suffix: String) {
        draftWrites.discard(suffix)
        draftStore.remove(viewerLogin, "$draftPrefix/$suffix")
    }

    override fun onCleared() {
        draftWrites.flushAll()
        super.onCleared()
    }

    suspend fun mergeMessage(id: String, method: String): WorkMergeMessage =
        detail.mergeMessage(id, method)

    fun mergePullRequest(
        snapshot: CollaborationItemDto,
        method: String,
        message: WorkMergeMessage?,
    ): Boolean {
        val state = uiState.value
        val item = state.item ?: return false
        val head = snapshot.head?.sha ?: return false
        if (
            item.nodeId != snapshot.nodeId ||
                item.head?.sha != head ||
                item.baseRefName != snapshot.baseRefName ||
                state.loading ||
                state.submitting ||
                state.mergePhase == WritePhase.PENDING ||
                state.mergePhase == WritePhase.UNCERTAIN ||
                !item.canMerge() ||
                (method != "REBASE" && message?.headline.isNullOrBlank())
        )
            return false
        val coord = detail.mutations()
        if (
            listOf(baseKey(), closeStateKey()).any {
                coord.current(it).phase.let { p ->
                    p == WritePhase.PENDING || p == WritePhase.UNCERTAIN
                }
            }
        )
            return false
        val receipt =
            coord.submitCommand(
                detail.keys.merge,
                detail.mutationTags,
                send = {
                    detail.merge(head, snapshot.baseRefName, method, message)
                    CommandAccepted
                },
                readBack = { detail.checkMerge() },
            ) ?: return false
        mutableUiState.update { it.copy(mergePhase = WritePhase.PENDING) }
        viewModelScope.launch {
            val outcome = receipt.await()
            when (outcome.phase) {
                WritePhase.REJECTED ->
                    messageChannel.trySend(
                        outcome.message ?: CurrentAppStrings.current.Common.MutationNotSubmitted,
                    )
                WritePhase.UNCERTAIN ->
                    messageChannel.trySend(CurrentAppStrings.current.Common.MutationUncertain)
                else -> Unit
            }
        }
        return true
    }

    fun submitTitle(title: String) {
        val state = uiState.value
        if (state.item?.viewerCanEdit != true || state.submitting || state.unknownField("title")) {
            return
        }
        updateTitleFromEditor(title)
    }

    private fun updateTitleFromEditor(title: String) {
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        val trimmed = title.trim()
        if (trimmed.isBlank() || trimmed.length > 256) return
        submitField(
            key = titleKey(),
            confirmed = snapshot.title,
            desired = trimmed,
            send = { detail.editTitle(snapshot, it) },
            readBack = { detail.readDetail().title },
        )
    }

    private fun updateBodyFromEditor(body: String) {
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        if (body.length > 65_536) return
        submitField(
            key = bodyKey(),
            confirmed = snapshot.body.orEmpty(),
            desired = body,
            send = { detail.editBody(snapshot, it) },
            readBack = { detail.readDetail().body.orEmpty() },
        )
    }

    fun editPullRequest(field: String, value: String) {
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.length > (if (field == "title") 256 else 1024)) return
        if (field == "title") {
            submitField(
                key = titleKey(),
                confirmed = snapshot.title,
                desired = trimmed,
                send = { detail.editPullRequest(snapshot, field, it) },
                readBack = { detail.readDetail().title },
            )
        } else {
            if (mergeBlocksChanges()) return
            submitField(
                key = baseKey(),
                confirmed = snapshot.baseRefName.orEmpty(),
                desired = trimmed,
                send = { detail.editPullRequest(snapshot, field, it) },
                readBack = { requireNotNull(detail.readDetail().baseRefName) },
            )
        }
    }

    fun toggleConversationLock() {
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        submitField(
            key = lockedKey(),
            confirmed = snapshot.locked,
            desired = !snapshot.locked,
            send = { detail.setLocked(snapshot, it) },
            readBack = { detail.readDetail().locked },
        )
    }

    fun closePullRequest() {
        if (mergeBlocksChanges()) return
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        submitField(
            key = closeStateKey(),
            confirmed = snapshot.state,
            desired = "closed",
            send = { detail.closePullRequest(snapshot) },
            readBack = { detail.readDetail().state },
        )
    }

    private fun mergeBlocksChanges() =
        detail.mutations().currentCommand(detail.keys.merge).phase.let {
            it == WritePhase.PENDING || it == WritePhase.UNCERTAIN
        }

    fun closeIssue() {
        val snapshot =
            confirmedDetailState.data?.takeIf(detail::accepts) ?: uiState.value.item ?: return
        if (snapshot.state != "open" || !snapshot.viewerCanClose) return
        submitField(
            key = closeStateKey(),
            confirmed = snapshot.state,
            desired = "closed",
            send = { detail.closeIssue(snapshot) },
            readBack = { detail.readDetail().state },
        )
    }

    fun selectReviewEvent(event: PullRequestReviewEvent) {
        val state = uiState.value
        if (state.submitting || !state.reviewDraftReady) return
        mutableUiState.update { it.copy(reviewEvent = event) }
        saveReviewDraft(ReviewForm(state.reviewDraft, event))
    }

    private fun saveReviewDraft(form: ReviewForm) {
        draftStore.saveEncoded(viewerLogin, "$draftPrefix/review-form") {
            Json.encodeToString(form)
        }
    }

    fun submitComment() {
        val state = uiState.value
        val body = state.commentDraft
        if (state.composerTarget != CollaborationEditorTarget.Comment) return
        if (!state.canComment || state.loading || state.submitting || !validCommentBody(body))
            return
        submitCommand(
            section = CollaborationSection.Discussion,
            key = newCommentKey(),
            draftSuffix = "comment",
            draftText = body,
            onSuccess = {
                mutableUiState.update {
                    if (it.commentDraft == body) it.copy(commentDraft = "") else it
                }
            },
            send = { detail.createComment(body) },
            readBack = { null },
        )
    }

    fun submitReview() {
        val state = uiState.value
        if (state.statusPanel != WorkStatusPanel.Reviews || !state.canSubmitReview) return
        val commit = state.item?.head?.sha ?: return
        val body = state.reviewDraft.takeUnless(String::isBlank)
        submitCommand(
            section = CollaborationSection.Reviews,
            key = reviewKey(),
            draftSuffix = "review-form",
            encodeDraft = { Json.encodeToString(ReviewForm(state.reviewDraft, state.reviewEvent)) },
            onSuccess = {
                mutableUiState.update {
                    if (it.reviewDraft != state.reviewDraft || it.reviewEvent != state.reviewEvent)
                        return@update it
                    it.copy(
                        reviewDraft = "",
                        reviewEvent = PullRequestReviewEvent.Comment,
                        statusPanel =
                            it.statusPanel.takeUnless { panel -> panel == WorkStatusPanel.Reviews },
                    )
                }
            },
            send = {
                detail.createReview(commit, state.reviewEvent.apiValue, body).also {
                    draftStore.removeIfUnchanged(
                        viewerLogin,
                        "$draftPrefix/review",
                        state.reviewDraft,
                    )
                }
            },
            readBack = { null },
        )
    }

    fun operationLabel(op: PendingMutationSummary): String {
        val strings = CurrentAppStrings.current.WorkDetail
        val field = op.target.removePrefix("${detail.workTarget}:")
        return when (op.kind) {
            MutationKind.MERGE -> strings.MergePullRequest
            MutationKind.REVIEW -> strings.Review
            MutationKind.COMMENT ->
                if (field.startsWith("delete-comment:")) strings.DeleteComment else strings.Comment
            MutationKind.WORK_SUBSCRIPTION -> strings.Subscribe
            MutationKind.WORK_PROPERTY ->
                WorkProperty.entries
                    .firstOrNull { it.name == field.substringAfter("property:") }
                    ?.title(strings) ?: strings.EditProperty
            MutationKind.ISSUE_FIELD -> strings.IssueFields
            else ->
                when (field) {
                    "title" -> strings.EditTitle
                    "body" -> strings.EditDescription
                    "base" -> strings.ChangeBaseBranch
                    "locked" -> strings.LockConversation
                    "state" ->
                        if (kind == CollaborationKind.PullRequest) strings.ClosePullRequest
                        else strings.CloseIssue
                    else -> strings.EditProperty
                }
        }
    }

    fun acknowledgeOperation(id: String) {
        detail.mutations().acknowledge(id)
    }

    private fun <T : Any> submitField(
        key: MutationKey<T>,
        confirmed: T,
        desired: T,
        send: suspend (T) -> T,
        readBack: suspend () -> T,
    ) {
        viewModelScope.launch {
            val coord =
                try {
                    ensureCoordinator()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    messageChannel.trySend(collaborationErrorMessage(error))
                    return@launch
                }
            val accepted =
                coord.submit(key, confirmed, desired, detail.mutationTags, send, readBack)
            if (!accepted) {
                val limitMsg =
                    "${CurrentAppStrings.current.Common.MutationNotSubmitted}: ${CurrentAppStrings.current.Common.MutationLimit}"
                mutableUiState.update { it.copy(submissionError = limitMsg) }
                messageChannel.trySend(limitMsg)
            }
        }
    }

    private fun <R : Any> submitCommand(
        section: CollaborationSection,
        key: MutationKey<R>,
        draftSuffix: String? = null,
        draftText: String = "",
        encodeDraft: (() -> String)? = null,
        onSuccess: (R) -> Unit,
        send: suspend () -> R,
        readBack: suspend () -> R?,
    ) {
        if (
            uiState.value.submitting ||
                uiState.value.uncertainOperations.any {
                    it.kind == key.kind && it.target == key.target
                }
        )
            return
        val submittedEditor = activeEditorSession
        val background = submittedEditor != null
        mutableUiState.update {
            it.copy(
                submitting = true,
                sendingInBackground = background,
                submittedSection = section,
                submissionError = null,
            )
        }
        syncEditorSession(uiState.value)
        if (draftSuffix != null) draftWrites.discard(draftSuffix)
        viewModelScope.launch {
            try {
                val store = draftStore
                val account = viewerLogin
                val storedKey = draftSuffix?.let { "$draftPrefix/$it" }
                val storedText =
                    if (encodeDraft == null) draftText
                    else
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            encodeDraft()
                        }
                if (storedKey != null && !store.saveConfirmed(account, storedKey, storedText)) {
                    val message = CurrentAppStrings.current.Common.MutationNotSubmitted
                    mutableUiState.update {
                        it.copy(
                            submitting = false,
                            sendingInBackground = false,
                            composerTarget = if (background) null else it.composerTarget,
                            submissionError = message,
                        )
                    }
                    syncEditorSession(uiState.value)
                    if (background) messageChannel.trySend(message)
                    return@launch
                }
                if (background) submittedEditor?.complete()
                val coord = ensureCoordinator()
                var latestError: Exception? = null
                val receipt =
                    coord.submitCommand(
                        key = key,
                        tags = detail.mutationTags,
                        onConfirmed = {
                            if (storedKey != null)
                                store.removeIfUnchanged(account, storedKey, storedText)
                        },
                        send = {
                            try {
                                send()
                            } catch (error: Exception) {
                                latestError = error
                                throw error
                            }
                        },
                        readBack = readBack,
                    )
                if (receipt == null) {
                    val shared = coord.currentCommand(key)
                    val limitMsg =
                        shared.message
                            ?: if (shared.phase == WritePhase.UNCERTAIN)
                                CurrentAppStrings.current.Common.MutationUncertain
                            else CurrentAppStrings.current.Common.MutationNotSubmitted
                    mutableUiState.update {
                        it.copy(
                            submitting = false,
                            sendingInBackground = false,
                            composerTarget = if (background) null else it.composerTarget,
                            submissionError = limitMsg,
                        )
                    }
                    syncEditorSession(uiState.value)
                    messageChannel.trySend(limitMsg)
                    return@launch
                }
                val cmd = receipt.await()
                when (cmd.phase) {
                    WritePhase.IDLE -> {
                        onSuccess(checkNotNull(cmd.result))
                        mutableUiState.update {
                            it.copy(
                                submitting = false,
                                sendingInBackground = false,
                                composerTarget = null,
                            )
                        }
                    }
                    WritePhase.REJECTED,
                    WritePhase.UNCERTAIN -> {
                        val message =
                            if (cmd.phase == WritePhase.UNCERTAIN)
                                CurrentAppStrings.current.Common.MutationUncertain
                            else
                                when (val error = latestError) {
                                    is WorkDetailCommentChangedException ->
                                        "评论已在其他位置更新，请重新打开评论核对，草稿已保留"
                                    is WorkDetailReviewChangedException -> "拉取请求已更新，请刷新后重新检查，草稿已保留。"
                                    null ->
                                        cmd.message ?: CurrentAppStrings.current.Common.MutationNotSubmitted
                                    else -> collaborationErrorMessage(error)
                                }
                        mutableUiState.update {
                            it.copy(
                                submitting = false,
                                sendingInBackground = false,
                                composerTarget = if (background) null else it.composerTarget,
                                submissionError = message,
                            )
                        }
                        if (background || uiState.value.composerTarget == null)
                            messageChannel.trySend(message)
                    }
                    WritePhase.PENDING -> error("A command receipt must be terminal")
                }
                syncEditorSession(uiState.value)
            } catch (error: CancellationException) {
                mutableUiState.update { it.copy(submitting = false, sendingInBackground = false) }
                throw error
            } catch (error: Exception) {
                val message = collaborationErrorMessage(error)
                mutableUiState.update {
                    it.copy(
                        submitting = false,
                        sendingInBackground = false,
                        composerTarget = if (background) null else it.composerTarget,
                        submissionError = message,
                    )
                }
                syncEditorSession(uiState.value)
                messageChannel.trySend(message)
            }
        }
    }
}

internal fun validCommentBody(body: String): Boolean = body.isNotBlank() && body.length <= 64_000

internal fun collaborationErrorMessage(error: Throwable): String =
    when (error) {
        is WorkDetailTypeMismatchException -> "编号对应的内容类型不符，请从正确的议题或拉取请求列表打开"
        is WorkDetailReviewChangedException -> "拉取请求已更新，请刷新后重新检查"
        is HttpException ->
            when (error.code()) {
                401 -> "会话已失效，请重新登录"
                403,
                429 -> "GitHub 拒绝请求，请检查权限或稍后重试"
                404 -> "找不到该内容，或当前账号无权访问"
                422 -> "GitHub 未接受该操作，请检查内容和当前状态"
                else -> "GitHub 请求失败（HTTP ${error.code()}）"
            }
        is IOException -> "网络连接失败，请检查网络后重试"
        is SerializationException -> "无法解析 GitHub 返回的数据"
        is GithubPageException -> {
            val message = error.message.orEmpty()
            when {
                message.contains("rate limit", ignoreCase = true) -> "GitHub 请求过于频繁，请稍后重试"
                message.contains("Bad credentials", ignoreCase = true) -> "会话已失效，请重新登录"
                else -> "GitHub 请求失败，请稍后重试"
            }
        }
        else -> "操作失败，请稍后重试"
    }

private fun githubBrowserUrl(url: String?): String? =
    url?.toHttpUrlOrNull()
        ?.takeIf {
            it.isHttps &&
                it.host == "github.com" &&
                it.port == 443 &&
                it.username.isEmpty() &&
                it.password.isEmpty() &&
                it.query == null
        }
        ?.toString()
