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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.CollaborationCommentDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestCheckRunDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewCommentDto
import moe.yumeyuka.yumehub.data.api.dto.PullRequestReviewDto
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.GithubReactionContent
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdate
import moe.yumeyuka.yumehub.data.repository.NotificationsRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailCommentChangedException
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException
import moe.yumeyuka.yumehub.data.repository.WorkDetailTypeMismatchException
import moe.yumeyuka.yumehub.data.repository.WorkMergeMessage
import moe.yumeyuka.yumehub.data.repository.WorkItemSubscription
import moe.yumeyuka.yumehub.data.repository.WorkItemSubscriptionRepository
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.withToggledReaction
import moe.yumeyuka.yumehub.data.storage.mmkv.CommentDraftStore
import moe.yumeyuka.yumehub.locale.GithubTxt
import moe.yumeyuka.yumehub.ui.component.MarkdownEditorState
import moe.yumeyuka.yumehub.ui.component.githubDiffContainsLine
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorKind
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

internal enum class CollaborationEditorTarget {
    Comment,
    Review,
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
        get() = label(GithubTxt.current.WorkDetail)
}

internal enum class WorkStatusPanel {
    Reviews,
    Checks,
}

internal enum class PullRequestReviewEvent(val apiValue: String) {
    Comment("COMMENT"),
    Approve("APPROVE"),
    RequestChanges("REQUEST_CHANGES"),
}

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
    val section: CollaborationSection = CollaborationSection.Discussion,
    val statusPanel: WorkStatusPanel? = null,
    val composerTarget: CollaborationEditorTarget? = null,
    val canComment: Boolean = false,
    val canReview: Boolean = false,
    val canCommentOnLines: Boolean = false,
    val commentDraft: String = "",
    val reviewDraft: String = "",
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
    val submissionUncertain: Boolean = false,
    val subscription: WorkItemSubscription? = null,
    val subscriptionError: String? = null,
    val desiredSubscription: Boolean? = null,
    val subscriptionUpdating: Boolean = false,
    val notificationDone: Boolean = false,
    val notificationActionError: String? = null,
    val titleEditorOpen: Boolean = false,
    val titleDraft: String = "",
    val bodyEditorOpen: Boolean = false,
    val bodyDraft: String = "",
    val branchQuery: String = "",
    val branchChoices: List<String> = emptyList(),
    val branchLoading: Boolean = false,
    val branchCursor: String? = null,
    val branchError: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val draftPrefix =
        "${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}/${kind.name}/$number"
    private val draftWrites = mutableMapOf<String, Job>()
    private val composerStates = mutableMapOf<CollaborationEditorTarget, MarkdownEditorState>()
    private var activeEditorSession: GithubEditorSession? = null
    private var activeEditorTarget: CollaborationEditorTarget? = null
    private val submissionMessageChannel = Channel<String>(Channel.BUFFERED)
    val submissionMessages = submissionMessageChannel.receiveAsFlow()
    private var subscriptionJob: Job? = null
    private var subscriptionWriteJob: Job? = null
    private var toggleUnknownSubscription = false
    private var subscriptionNodeId: String? = null
    private var subscriptionGeneration = 0L
    private val mutableUiState =
        MutableStateFlow(
            CollaborationDetailUiState(
                    commentDraft = draftStore.load(viewerLogin, "$draftPrefix/comment"),
                    reviewDraft = draftStore.load(viewerLogin, "$draftPrefix/review"),
                )
                .withDetail(detail.state.value),
        )
    val uiState = mutableUiState.asStateFlow()
    val reactionUpdates = detail.reactionUpdates
    private var branchJob: Job? = null
    private var branchGeneration = 0L

    fun openTitleEditor() {
        if (uiState.value.item?.viewerCanEdit == true && !uiState.value.submitting) {
            mutableUiState.update {
                it.copy(titleEditorOpen = true, titleDraft = it.item?.title.orEmpty())
            }
        }
    }

    fun takeTitleEditorSession(): GithubEditorSession? {
        val state = uiState.value
        val item = state.item ?: return null
        if (!state.titleEditorOpen || state.submitting) return null
        lateinit var session: GithubEditorSession
        session =
            GithubEditorSession(
                title = GithubTxt.current.WorkDetail.EditTitle,
                kind = GithubEditorKind.Markdown,
                source = item.title,
                onSourceChange = { source ->
                    mutableUiState.update { it.copy(titleDraft = source) }
                },
                onSubmit = {
                    updateTitleFromEditor(uiState.value.titleDraft)
                    mutableUiState.update { it.copy(titleEditorOpen = false, titleDraft = "") }
                    session.complete()
                },
                submitLabel = GithubTxt.current.WorkDetail.SaveChanges,
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
        if (state.item?.viewerCanEdit == true && !state.submitting && !state.submissionUncertain) {
            mutableUiState.update {
                it.copy(bodyEditorOpen = true, bodyDraft = it.item?.body.orEmpty())
            }
        }
    }

    fun takeBodyEditorSession(): GithubEditorSession? {
        val state = uiState.value
        val item = state.item ?: return null
        if (!state.bodyEditorOpen || state.submitting) return null
        lateinit var session: GithubEditorSession
        session =
            GithubEditorSession(
                title = GithubTxt.current.WorkDetail.EditDescription,
                kind = GithubEditorKind.Markdown,
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
                submitLabel = GithubTxt.current.WorkDetail.SaveChanges,
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
                    mutableUiState.update { it.copy(branchLoading = false, branchError = true) }
            }
        }
    }

    fun loadMoreBaseBranches() {
        val state = uiState.value
        val cursor = state.branchCursor ?: return
        if (state.branchLoading || state.submitting) return
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
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == branchGeneration)
                    mutableUiState.update { it.copy(branchError = true) }
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
    private val fileEntries =
        uiState
            .map { it.item?.head?.sha?.takeUnless(String::isBlank) }
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { sha ->
                detail.files(sha).map { page ->
                    page.map<PullRequestFileDto, CollaborationEntry> {
                        CollaborationEntry.File(sha, it)
                    }
                }
            }
            .cachedIn(viewModelScope)

    fun checks(head: String): Flow<PagingData<PullRequestCheckRunDto>> = detail.checks(head)

    private val commitEntries =
        uiState
            .map { it.item?.head?.sha?.takeIf(String::isNotBlank) }
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { head ->
                detail.commits(head).map { page ->
                    page.map<
                        moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry,
                        CollaborationEntry,
                    > {
                        CollaborationEntry.Commit(it)
                    }
                }
            }
            .cachedIn(viewModelScope)

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            detail.state.collect { cached ->
                val oldLine = uiState.value.reviewLine
                if (oldLine != null && oldLine.commitId != cached.data?.head?.sha) {
                    flushLineDraft(oldLine, uiState.value.lineCommentDraft)
                }
                mutableUiState.update { it.withDetail(cached) }
                uiState.value.item?.nodeId?.takeIf(String::isNotBlank)?.let { id ->
                    if (subscriptionNodeId != id) {
                        subscriptionNodeId = id
                        loadSubscription()
                    }
                }
            }
        }
        // 首屏只拉详情与当前分节第一页；属性（一次 load 是节点查询加若干分页查询）留到面板首次
        // 打开时按需取，避免和首屏抢同一批连接。
        detail.refresh()
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
            CollaborationEditorTarget.Review -> changeReviewDraft(source)
            CollaborationEditorTarget.EditedComment -> changeEditedComment(source)
            CollaborationEditorTarget.LineComment -> changeLineComment(source)
        }
    }

    fun takeEditorSession(): GithubEditorSession? {
        if (uiState.value.submitting) return null
        val target = uiState.value.composerTarget ?: return null
        if (activeEditorSession != null) return null
        val state = uiState.value
        val strings = GithubTxt.current.WorkDetail
        lateinit var session: GithubEditorSession
        session =
            GithubEditorSession(
                title =
                    when (target) {
                        CollaborationEditorTarget.Comment -> strings.Comment
                        CollaborationEditorTarget.Review -> strings.Reviews
                        CollaborationEditorTarget.EditedComment -> strings.EditComment
                        CollaborationEditorTarget.LineComment -> strings.LineComment
                    },
                kind = GithubEditorKind.Markdown,
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
                            // 保存正文只退出编辑器；等编辑路由销毁再恢复评审面板。
                            CollaborationEditorTarget.Review -> session.complete()
                            CollaborationEditorTarget.EditedComment -> saveEditedComment()
                            CollaborationEditorTarget.LineComment -> submitLineComment()
                        }
                    }
                },
                headerContent =
                    if (target == CollaborationEditorTarget.LineComment)
                        ({ CollaborationEditorHeader(this, target) })
                    else null,
                submitLabel =
                    when (target) {
                        CollaborationEditorTarget.EditedComment -> strings.SaveChanges
                        CollaborationEditorTarget.Review -> strings.SaveChanges
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
                !state.submissionUncertain &&
                when (target) {
                    CollaborationEditorTarget.Comment ->
                        state.canComment && validCommentBody(source)
                    CollaborationEditorTarget.Review ->
                        state.canReview && (source.isBlank() || validCommentBody(source))
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
            CollaborationEditorTarget.Review -> state.reviewDraft
            CollaborationEditorTarget.EditedComment -> state.editingComment?.draft.orEmpty()
            CollaborationEditorTarget.LineComment -> state.lineCommentDraft
        }

    fun entriesFor(section: CollaborationSection): Flow<PagingData<CollaborationEntry>>? =
        when (section) {
            CollaborationSection.Discussion -> null
            CollaborationSection.Reviews -> reviewEntries
            CollaborationSection.Files -> fileEntries
            CollaborationSection.Commits -> commitEntries
        }

    private fun CollaborationDetailUiState.withDetail(
        cached: CachedDataState<CollaborationItemDto>,
    ): CollaborationDetailUiState {
        val item = cached.data?.takeIf(detail::accepts)
        val failure =
            cached.error
                ?: if (cached.data != null && item == null) {
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
            loading = cached.loading,
            error = failure?.let(::collaborationErrorMessage),
            browserUrl = githubBrowserUrl(item?.htmlUrl),
            canComment = canComment,
            canReview =
                canComment &&
                    openPullRequest &&
                    item != null &&
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

    fun loadSubscription(force: Boolean = false) {
        val id = subscriptionNodeId ?: return
        if (uiState.value.subscriptionUpdating) return
        val generation = ++subscriptionGeneration
        subscriptionJob?.cancel()
        mutableUiState.update { it.copy(subscriptionError = null) }
        subscriptionJob = viewModelScope.launch {
            try {
                val loaded = subscriptions.load(viewerLogin, id, force)
                if (generation == subscriptionGeneration) {
                    mutableUiState.update { it.copy(subscription = loaded) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == subscriptionGeneration) {
                    mutableUiState.update {
                        it.copy(subscriptionError = collaborationErrorMessage(error))
                    }
                }
            }
        }
    }

    fun toggleSubscription() {
        val state = uiState.value
        val subjectId = state.item?.nodeId?.takeIf(String::isNotBlank) ?: return
        if (state.subscription?.canSubscribe == false) return
        val current = state.desiredSubscription ?: state.subscription?.subscribed
        if (current == null) toggleUnknownSubscription = !toggleUnknownSubscription
        else mutableUiState.update { it.copy(desiredSubscription = !current) }
        if (subscriptionWriteJob?.isActive == true) return
        subscriptionGeneration++
        subscriptionJob?.cancel()
        mutableUiState.update {
            it.copy(
                subscriptionUpdating = true,
                subscriptionError = null,
                notificationActionError = null,
            )
        }
        subscriptionWriteJob = viewModelScope.launch {
            var failed = false
            try {
                var confirmed =
                    subscriptions.load(
                        viewerLogin,
                        subjectId,
                        force = state.subscriptionError != null,
                    )
                if (toggleUnknownSubscription) {
                    toggleUnknownSubscription = false
                    mutableUiState.update { it.copy(desiredSubscription = !confirmed.subscribed) }
                }
                mutableUiState.update { it.copy(subscription = confirmed) }
                while (true) {
                    val desired = uiState.value.desiredSubscription ?: break
                    if (desired == confirmed.subscribed) break
                    if (!confirmed.canSubscribe)
                        throw IllegalStateException("Subscription unavailable")
                    confirmed = subscriptions.setSubscribed(viewerLogin, subjectId, desired)
                    mutableUiState.update { it.copy(subscription = confirmed) }
                }
                mutableUiState.update { it.copy(desiredSubscription = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                toggleUnknownSubscription = false
                mutableUiState.update {
                    it.copy(
                        desiredSubscription = null,
                        subscriptionError = collaborationErrorMessage(error),
                        notificationActionError = collaborationErrorMessage(error),
                    )
                }
            } finally {
                mutableUiState.update { it.copy(subscriptionUpdating = false) }
                if (failed) loadSubscription(force = true)
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
        val state = uiState.value
        if (state.submitting && !state.sendingInBackground) return
        if (kind == CollaborationKind.PullRequest || section == CollaborationSection.Discussion) {
            mutableUiState.update { it.copy(section = section) }
        }
    }

    fun openComposer(target: CollaborationEditorTarget = CollaborationEditorTarget.Comment) {
        val state = uiState.value
        if (
            state.submitting ||
                state.submissionUncertain ||
                state.loading
        )
            return
        val allowed =
            when (target) {
                CollaborationEditorTarget.Comment ->
                    state.canComment || state.commentDraft.isNotEmpty()
                CollaborationEditorTarget.Review ->
                    state.canReview || state.reviewDraft.isNotEmpty()
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
        mutableUiState.update {
            it.copy(
                composerTarget = null,
                statusPanel =
                    if (it.composerTarget == CollaborationEditorTarget.Review)
                        WorkStatusPanel.Reviews
                    else it.statusPanel,
            )
        }
    }

    fun showStatusPanel(panel: WorkStatusPanel?) {
        mutableUiState.update {
            if (it.composerTarget == null) it.copy(statusPanel = panel) else it
        }
    }

    fun changeCommentDraft(draft: String) {
        if (uiState.value.submitting) return
        mutableUiState.update { it.copy(commentDraft = draft) }
        persistDraft("comment", draft)
    }

    fun beginReview() {
        val state = uiState.value
        if (state.submitting || state.submissionUncertain) return
        mutableUiState.update { it.copy(composerTarget = CollaborationEditorTarget.Review) }
    }

    fun changeReviewDraft(draft: String) {
        if (uiState.value.submitting) return
        mutableUiState.update { it.copy(reviewDraft = draft) }
        persistDraft("review", draft)
    }

    fun canManageComment(comment: CollaborationCommentDto): Boolean =
        comment.user?.login?.equals(viewerLogin, ignoreCase = true) == true &&
            uiState.value.item != null &&
            uiState.value.error == null &&
            !uiState.value.submitting &&
            !uiState.value.loading &&
            !uiState.value.submissionUncertain

    fun canHideComment(comment: CollaborationCommentDto): Boolean =
        comment.viewerCanMinimize &&
            !comment.isMinimized &&
            uiState.value.item != null &&
            uiState.value.error == null &&
            !uiState.value.submitting &&
            !uiState.value.loading &&
            !uiState.value.submissionUncertain

    fun editComment(comment: CollaborationCommentDto) {
        if (!canManageComment(comment)) return
        uiState.value.editingComment?.let { flushEditedDraft(it) }
        val restored = draftStore.loadOrNull(viewerLogin, "$draftPrefix/edit/${comment.id}")
        mutableUiState.update {
            it.copy(
                editingComment = CommentEditTarget(comment, restored ?: comment.body.orEmpty()),
                composerTarget = CollaborationEditorTarget.EditedComment,
                section = CollaborationSection.Discussion,
                submissionError = null,
            )
        }
    }

    fun changeEditedComment(draft: String) {
        val state = uiState.value
        val target = state.editingComment ?: return
        if (state.submitting) return
        mutableUiState.update { it.copy(editingComment = target.copy(draft = draft)) }
        persistDraft("edit/${target.comment.id}", draft)
    }

    fun cancelEditComment() {
        val state = uiState.value
        if (state.submitting) return
        state.editingComment?.let { flushEditedDraft(it) }
        mutableUiState.update { it.copy(editingComment = null) }
    }

    private fun flushEditedDraft(target: CommentEditTarget) {
        draftWrites.remove("edit/${target.comment.id}")?.cancel()
        draftStore.save(viewerLogin, "$draftPrefix/edit/${target.comment.id}", target.draft)
    }

    fun requestDeleteComment(comment: CollaborationCommentDto) {
        if (canManageComment(comment)) mutableUiState.update { it.copy(deleteComment = comment) }
    }

    fun hideComment(comment: CollaborationCommentDto, reason: CommentHideReason) {
        if (!canHideComment(comment)) return
        submit(CollaborationSection.Discussion, onSuccess = {}) {
            detail.minimizeComment(comment, reason.apiValue)
        }
    }

    fun toggleCommentReaction(
        comment: CollaborationCommentDto,
        content: GithubReactionContent,
        reacted: Boolean,
    ) {
        if (!comment.viewerCanReact) return
        toggleReaction(comment.nodeId, comment.reactions.toReactionGroups(), content, reacted)
    }

    fun toggleBodyReaction(content: GithubReactionContent, reacted: Boolean) {
        val item = uiState.value.item?.takeIf { it.viewerCanReact } ?: return
        toggleReaction(item.nodeId, item.reactions.toReactionGroups(), content, reacted)
    }

    private fun toggleReaction(
        subjectId: String?,
        sourceReactions: List<GithubReactionGroup>,
        content: GithubReactionContent,
        reacted: Boolean,
    ) {
        val nodeId = subjectId?.takeIf(String::isNotBlank) ?: return
        val current = uiState.value
        if (current.submitting && !current.sendingInBackground) return
        val previous = reactionUpdates.current(nodeId, sourceReactions)
        if (previous?.pending == true) return
        val reactions = previous?.reactions ?: sourceReactions
        val isReacted = reactions.any { it.content == content && it.viewerReacted }
        if (isReacted != reacted) return
        val pending =
            GithubReactionUpdate(
                sourceReactions,
                reactions.withToggledReaction(content),
                pending = true,
            )
        viewModelScope.launch {
            reactionUpdates.set(nodeId, pending)
            try {
                val confirmed = detail.toggleReaction(nodeId, content.name, reacted).toReactionGroups()
                reactionUpdates.set(nodeId, pending.copy(reactions = confirmed, pending = false))
            } catch (error: CancellationException) {
                reactionUpdates.set(nodeId, previous)
                throw error
            } catch (_: Exception) {
                reactionUpdates.set(nodeId, previous)
                submissionMessageChannel.trySend(GithubTxt.current.Activity.ActionFailed)
            }
        }
    }

    fun quoteComment(comment: CollaborationCommentDto) = quoteBody(comment.user?.login, comment.body)

    fun quoteBody(author: String?, body: String?) {
        val state = uiState.value
        if (!state.canComment || state.loading || state.submitting || state.submissionUncertain)
            return
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
        mutableUiState.update {
            it.copy(
                commentDraft = draft,
                composerTarget = CollaborationEditorTarget.Comment,
                section = CollaborationSection.Discussion,
                submissionError = null,
            )
        }
        persistDraft("comment", draft)
    }

    fun dismissDeleteComment() {
        if (!uiState.value.submitting) mutableUiState.update { it.copy(deleteComment = null) }
    }

    fun confirmDeleteComment() {
        val state = uiState.value
        val comment = state.deleteComment ?: return
        if (!canManageComment(comment)) return
        mutableUiState.update { it.copy(deleteComment = null) }
        submit(
            CollaborationSection.Discussion,
            onSuccess = {
                clearDraft("edit/${comment.id}")
                mutableUiState.update {
                    it.copy(
                        editingComment =
                            it.editingComment.takeUnless { target ->
                                target?.comment?.id == comment.id
                            },
                    )
                }
            },
        ) {
            detail.deleteComment(comment)
        }
    }

    fun saveEditedComment() {
        val state = uiState.value
        val target = state.editingComment ?: return
        if (
            !canManageComment(target.comment) ||
                !validCommentBody(target.draft) ||
                target.draft == target.comment.body.orEmpty()
        )
            return
        submit(
            CollaborationSection.Discussion,
            onSuccess = {
                clearDraft("edit/${target.comment.id}")
                mutableUiState.update { it.copy(editingComment = null) }
            },
        ) {
            detail.updateComment(target.comment, target.draft)
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
        state.reviewLine?.let { flushLineDraft(it, state.lineCommentDraft) }
        val side = if (newLine != null) "RIGHT" else "LEFT"
        val line = newLine ?: oldLine ?: return
        val path = file.filename
        val patch = file.patch ?: return
        if (!githubDiffContainsLine(patch, line, side)) return
        val target = ReviewLineTarget(path, line, side, commit, patch)
        if (state.reviewLine == target) return
        mutableUiState.update {
            it.copy(
                reviewLine = target,
                composerTarget = CollaborationEditorTarget.LineComment,
                lineCommentDraft =
                    draftStore.load(viewerLogin, "$draftPrefix/${lineDraftKey(target)}"),
                lastLineComment = null,
                submissionError = null,
            )
        }
    }

    fun changeLineComment(draft: String) {
        val state = uiState.value
        val target = state.reviewLine ?: return
        if (state.submitting) return
        mutableUiState.update { it.copy(lineCommentDraft = draft) }
        persistDraft(lineDraftKey(target), draft)
    }

    fun cancelLineComment() {
        val state = uiState.value
        if (state.submitting) return
        state.reviewLine?.let { flushLineDraft(it, state.lineCommentDraft) }
        mutableUiState.update { it.copy(reviewLine = null) }
    }

    private fun flushLineDraft(target: ReviewLineTarget, body: String) {
        draftWrites.remove(lineDraftKey(target))?.cancel()
        draftStore.save(viewerLogin, "$draftPrefix/${lineDraftKey(target)}", body)
    }

    fun submitLineComment() {
        val state = uiState.value
        val target = state.reviewLine ?: return
        if (
            !state.canCommentOnLines ||
                state.submitting ||
                state.loading ||
                !validCommentBody(state.lineCommentDraft)
        )
            return
        if (!githubDiffContainsLine(target.patch, target.line, target.side)) return
        submit(
            CollaborationSection.Files,
            onSuccess = {
                clearDraft(lineDraftKey(target))
                mutableUiState.update { it.copy(reviewLine = null, lineCommentDraft = "") }
            },
        ) {
            val created =
                detail.createLineComment(
                    state.lineCommentDraft,
                    target.commitId,
                    target.path,
                    target.line,
                    target.side,
                )
            mutableUiState.update { it.copy(lastLineComment = created) }
        }
    }

    private fun lineDraftKey(target: ReviewLineTarget): String =
        "line/${target.commitId}/${target.path.length}:${target.path}/${target.side}/${target.line}"

    private fun persistDraft(suffix: String, body: String) {
        draftWrites.remove(suffix)?.cancel()
        draftWrites[suffix] = viewModelScope.launch {
            delay(400)
            draftStore.save(viewerLogin, "$draftPrefix/$suffix", body)
            draftWrites.remove(suffix)
        }
    }

    private fun clearDraft(suffix: String) {
        draftWrites.remove(suffix)?.cancel()
        draftStore.remove(viewerLogin, "$draftPrefix/$suffix")
    }

    override fun onCleared() {
        draftWrites.values.forEach { it.cancel() }
        draftWrites.clear()
        val state = uiState.value
        draftStore.save(viewerLogin, "$draftPrefix/comment", state.commentDraft)
        draftStore.save(viewerLogin, "$draftPrefix/review", state.reviewDraft)
        state.editingComment?.let {
            draftStore.save(viewerLogin, "$draftPrefix/edit/${it.comment.id}", it.draft)
        }
        state.reviewLine?.let {
            draftStore.save(viewerLogin, "$draftPrefix/${lineDraftKey(it)}", state.lineCommentDraft)
        }
        super.onCleared()
    }

    suspend fun mergeMessage(id: String, method: String): WorkMergeMessage =
        detail.mergeMessage(id, method)

    fun mergePullRequest(
        snapshot: CollaborationItemDto,
        method: String,
        message: WorkMergeMessage?,
        onSuccess: () -> Unit,
    ) {
        val state = uiState.value
        val item = state.item ?: return
        val head = snapshot.head?.sha ?: return
        if (
            item.nodeId != snapshot.nodeId ||
                item.head?.sha != head ||
                item.baseRefName != snapshot.baseRefName ||
                state.loading ||
                state.submitting ||
                state.submissionUncertain ||
                !item.canMerge() ||
                (method != "REBASE" && message?.headline.isNullOrBlank())
        )
            return
        submit(CollaborationSection.Discussion, onSuccess = onSuccess) {
            detail.merge(head, snapshot.baseRefName, method, message)
        }
    }

    fun requestReview(login: String) {
        if (login.isBlank()) return
        submit(CollaborationSection.Reviews, onSuccess = {}) { detail.requestReview(login.trim()) }
    }

    private fun updateTitleFromEditor(title: String) {
        val snapshot = uiState.value.item ?: return
        if (title.isBlank() || title.length > 256) return
        submit(CollaborationSection.Discussion, onSuccess = {}, background = true) {
            detail.editTitle(snapshot, title.trim())
        }
    }

    private fun updateBodyFromEditor(body: String) {
        val snapshot = uiState.value.item ?: return
        if (body.length > 65_536) return
        submit(CollaborationSection.Discussion, onSuccess = {}, background = true) {
            detail.editBody(snapshot, body)
        }
    }

    fun editPullRequest(field: String, value: String) {
        val snapshot = uiState.value.item ?: return
        if (value.isBlank() || value.length > (if (field == "title") 256 else 1024)) return
        submit(CollaborationSection.Discussion, onSuccess = {}) {
            detail.editPullRequest(snapshot, field, value.trim())
        }
    }

    fun toggleConversationLock() {
        val snapshot = uiState.value.item ?: return
        submit(CollaborationSection.Discussion, onSuccess = {}) { detail.setLocked(snapshot) }
    }

    fun closePullRequest() {
        val snapshot = uiState.value.item ?: return
        submit(CollaborationSection.Discussion, onSuccess = {}) {
            detail.closePullRequest(snapshot)
        }
    }

    fun closeIssue() {
        val snapshot = uiState.value.item ?: return
        if (snapshot.state != "open" || !snapshot.viewerCanClose) return
        submit(CollaborationSection.Discussion, onSuccess = {}) { detail.closeIssue(snapshot) }
    }

    fun selectReviewEvent(event: PullRequestReviewEvent) {
        mutableUiState.update { state ->
            if (state.submitting) state else state.copy(reviewEvent = event)
        }
    }

    fun submitComment() {
        val state = uiState.value
        val body = state.commentDraft
        if (state.composerTarget != CollaborationEditorTarget.Comment) return
        if (!state.canComment || state.loading || state.submitting || !validCommentBody(body))
            return
        submit(
            CollaborationSection.Discussion,
            onSuccess = {
                clearDraft("comment")
                mutableUiState.update { it.copy(commentDraft = "") }
            },
        ) {
            detail.createComment(body)
        }
    }

    fun submitReview() {
        val state = uiState.value
        val commit = state.item?.head?.sha ?: return
        val body = state.reviewDraft.takeUnless(String::isBlank)
        if (!state.canReview || state.loading || state.submitting) return
        if (state.reviewEvent != PullRequestReviewEvent.Approve && body == null) return
        if (body != null && !validCommentBody(body)) return
        submit(
            CollaborationSection.Reviews,
            onSuccess = {
                clearDraft("review")
                mutableUiState.update {
                    it.copy(
                        reviewDraft = "",
                        reviewEvent = PullRequestReviewEvent.Comment,
                        statusPanel = null,
                    )
                }
            },
        ) {
            detail.createReview(commit, state.reviewEvent.apiValue, body)
        }
    }

    fun acknowledgeSubmissionResult() {
        if (!uiState.value.loading && !uiState.value.submitting) {
            mutableUiState.update { it.copy(submissionUncertain = false, submissionError = null) }
        }
    }

    private fun submit(
        section: CollaborationSection,
        onSuccess: () -> Unit,
        background: Boolean = activeEditorSession != null,
        request: suspend () -> Unit,
    ) {
        if (uiState.value.submitting || uiState.value.submissionUncertain) return
        val submittedEditor = activeEditorSession
        mutableUiState.update {
            it.copy(
                submitting = true,
                sendingInBackground = background,
                submittedSection = section,
                submissionError = null,
            )
        }
        syncEditorSession(uiState.value)
        // 草稿和提交互斥仍归详情 VM；编辑路由立即退出，不等待网络结果。
        if (background) submittedEditor?.complete()
        viewModelScope.launch {
            try {
                request()
                onSuccess()
                mutableUiState.update {
                    it.copy(submitting = false, sendingInBackground = false, composerTarget = null)
                }
                syncEditorSession(uiState.value)
            } catch (error: CancellationException) {
                mutableUiState.update { it.copy(submitting = false, sendingInBackground = false) }
                throw error
            } catch (error: Exception) {
                // 服务端可能已经处理了断开的写请求，保留草稿并让用户先核对。
                val message =
                    when (error) {
                        is WorkDetailCommentChangedException -> "评论已在其他位置更新，请重新打开评论核对，草稿已保留"
                        is WorkDetailReviewChangedException -> "拉取请求已更新，请刷新后重新检查，草稿已保留。"
                        is IOException,
                        is SerializationException -> "未能确认提交结果。请先刷新核对，草稿已保留。"
                        else -> collaborationErrorMessage(error)
                    }
                val uncertain =
                    error is IOException ||
                        error is SerializationException ||
                        (error is HttpException && error.code() >= 500)
                mutableUiState.update {
                    it.copy(
                        submitting = false,
                        sendingInBackground = false,
                        composerTarget = if (background) null else it.composerTarget,
                        submissionError = if (background && !uncertain) null else message,
                        submissionUncertain = uncertain,
                    )
                }
                syncEditorSession(uiState.value)
                if (background) submissionMessageChannel.trySend(message)
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
