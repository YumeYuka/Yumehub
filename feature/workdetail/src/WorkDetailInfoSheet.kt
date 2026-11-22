package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import java.io.IOException
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.WorkMergeMessage
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitMessageFields
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.GithubWorkStatusIcon
import moe.yumeyuka.yumehub.ui.component.githubLabelColor
import moe.yumeyuka.yumehub.ui.component.githubWorkStatusLabel
import retrofit2.HttpException

@Composable
internal fun WorkMetadataSheet(
    visible: Boolean,
    item: CollaborationItemDto?,
    kind: CollaborationKind,
    onDismiss: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val work = strings.WorkDetail
    GlasmioBottomSheet(
        visible = visible && item != null,
        onDismissRequest = onDismiss,
        title = work.Information,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = work.Information,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
                    }
                },
            )
        },
    ) {
        if (item != null) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(LocalGlasmioSheetContentPadding.current)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = work.Status,
                        detail = githubWorkStatusLabel(kind, item.state, item.draft, item.merged),
                        leading = {
                            GithubWorkStatusIcon(
                                kind,
                                item.state,
                                item.draft,
                                item.merged,
                                item.stateReason,
                                size = 20.dp,
                            )
                        },
                        showTopSeparator = false,
                    )
                    item.taskProgress?.let { progress ->
                        GlasmioListRow(
                            title = work.TaskProgress(progress.completed, progress.total),
                            leading = {
                                GithubIcon(Lucide.ListChecks, contentDescription = null, size = 20.dp)
                            },
                        )
                    }
                }
                if (kind == CollaborationKind.PullRequest) {
                    val head = item.head?.ref?.takeIf(String::isNotBlank)
                    val base = item.baseRefName?.takeIf(String::isNotBlank)
                    if (head != null || base != null) {
                        GithubSection(strings.Workspaces.Branches) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                head?.let { WorkMetadataBranch(strings.WorkList.HeadBranch, it) }
                                base?.let { WorkMetadataBranch(strings.WorkList.BaseBranch, it) }
                            }
                        }
                    }
                }
                GithubSection(work.Labels) {
                    if (item.labels.isEmpty()) {
                        GlasmioListRow(title = work.NoLabels, showTopSeparator = false)
                    } else {
                        item.labels.forEachIndexed { index, label ->
                            GlasmioListRow(
                                title = label.name,
                                leading = {
                                    GithubIcon(
                                        Lucide.Tag,
                                        contentDescription = null,
                                        size = 20.dp,
                                        tint = githubLabelColor(label.color),
                                    )
                                },
                                showTopSeparator = index != 0,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkMetadataBranch(label: String, name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = GlasmioTheme.textStyles.footnote,
            color = GlasmioTheme.colors.secondaryContent,
        )
        Text(name, style = GlasmioTheme.textStyles.body, color = GlasmioTheme.colors.content)
    }
}

@Composable
internal fun WorkStatusSheet(
    currentPage: Boolean,
    state: CollaborationDetailUiState,
    viewerLogin: String,
    onDismiss: () -> Unit,
    onReview: () -> Unit,
    onReviewEvent: (PullRequestReviewEvent) -> Unit,
    onSubmitReview: () -> Unit,
    checks: @Composable () -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val common = LocalGithubStrings.current.Common
    val active = state.statusPanel
    // 关闭动画结束前保留面板类型及其内容。
    var lastPanel by remember { mutableStateOf(active) }
    SideEffect { if (active != null) lastPanel = active }
    val panel = active ?: lastPanel ?: return
    val checksPanel = panel == WorkStatusPanel.Checks
    val visible = currentPage && active != null
    val title = if (checksPanel) strings.Checks else strings.Reviews
    val item = state.item
    key(panel) {
        val scroll = rememberScrollState()
        val contentPadding = 12.dp
        val fadeDistance = with(LocalDensity.current) { contentPadding.toPx() }
        LaunchedEffect(visible) { if (visible && checksPanel) scroll.scrollTo(0) }
        GlasmioBottomSheet(
            visible = visible,
            onDismissRequest = onDismiss,
            title = title,
            // 分页检查使用固定停靠位置，不随占位、结果和重试行的高度改变。
            skipPartiallyExpanded = !checksPanel,
            fitContent = !checksPanel,
            topBarBlurProgress = (scroll.value / fadeDistance).coerceIn(0f, 1f),
            topBar = {
                GlasmioTopBar(
                    title = title,
                    navigation = {
                        GlasmioIconButton(onClick = onDismiss) {
                            GithubIcon(Lucide.X, contentDescription = common.Close)
                        }
                    },
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (checksPanel) Modifier else Modifier.animateContentSize(tween(420)))
                    .verticalScroll(scroll)
                    .padding(LocalGlasmioSheetContentPadding.current)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (checksPanel) {
                    item?.let {
                        GlasmioGroupedList {
                            val mergeStatus =
                                it.mergeStateStatus?.let(strings.MergeStatus)
                                    ?: it.mergeable?.let(strings.MergeStatus)
                                    ?: strings.MergeRequirements
                            GlasmioListRow(title = mergeStatus, showTopSeparator = false)
                        }
                    }
                    checks()
                } else {
                    WorkReviewGroups(state, viewerLogin, onReview, onReviewEvent, onSubmitReview)
                }
            }
        }
    }
}

@Composable
private fun WorkReviewGroups(
    state: CollaborationDetailUiState,
    viewerLogin: String,
    onReview: () -> Unit,
    onReviewEvent: (PullRequestReviewEvent) -> Unit,
    onSubmitReview: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val review = strings.WorkDetail
    val item = state.item
    GithubSection(review.RequestedReviewers) {
        val reviewers = item?.requestedReviewers.orEmpty()
        if (reviewers.isEmpty()) GlasmioListRow(title = review.NoReviewRequests, systemName = null)
        reviewers.forEach { reviewer -> GlasmioListRow(title = reviewer, systemName = null) }
    }
    GithubSection(review.ReviewResults) {
        val reviews =
            item?.latestReviews.orEmpty().filterNot { it.user?.login.equals(viewerLogin, true) }
        if (reviews.isEmpty()) GlasmioListRow(title = review.NoReviewResults, systemName = null)
        reviews.forEach {
            GlasmioListRow(
                title = it.user?.login ?: strings.Workspaces.UnknownAuthor,
                detail = review.ReviewState(it.state),
                systemName = null,
            )
        }
    }
    if (!state.canReview && state.reviewDraft.isEmpty()) return
    GithubSection(review.MyReview) {
        GlasmioListRow(
            title = review.ReviewResult,
            systemName = null,
            trailing = {
                GlasmioPopUpButton(
                    selected = state.reviewEvent,
                    items = PullRequestReviewEvent.entries,
                    onSelected = onReviewEvent,
                    label = { event ->
                        when (event) {
                            PullRequestReviewEvent.Comment -> review.Comment
                            PullRequestReviewEvent.Approve -> review.Approve
                            PullRequestReviewEvent.RequestChanges -> review.RequestChanges
                        }
                    },
                    enabled = state.canReview && !state.submitting,
                )
            },
        )
        GlasmioListRow(
            title = review.Comment,
            subtitle = state.reviewDraft.takeIf(String::isNotBlank),
            systemName = null,
            onClick = if (!state.submitting) onReview else null,
        )
    }
    GlasmioButton(
        onClick = onSubmitReview,
        enabled =
            state.canReview &&
                !state.loading &&
                !state.submitting &&
                !state.submissionUncertain &&
                (validCommentBody(state.reviewDraft) ||
                    state.reviewDraft.isBlank() &&
                        state.reviewEvent == PullRequestReviewEvent.Approve),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(review.SubmitReview)
    }
}

@Composable
internal fun WorkMergeSheet(
    visible: Boolean,
    state: CollaborationDetailUiState,
    onDismiss: () -> Unit,
    loadMessage: suspend (id: String, method: String) -> WorkMergeMessage,
    onMerge: (CollaborationItemDto, String, WorkMergeMessage?) -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val common = LocalGithubStrings.current.Common
    val item = state.item
    val methods =
        listOfNotNull(
            "MERGE".takeIf { item?.mergeCommitAllowed == true }?.let { it to strings.MergeCommit },
            "SQUASH".takeIf { item?.squashMergeAllowed == true }?.let { it to strings.SquashMerge },
            "REBASE".takeIf { item?.rebaseMergeAllowed == true }?.let { it to strings.RebaseMerge },
        )
    val id = item?.nodeId
    val head = item?.head?.sha
    val base = item?.baseRefName
    var selected by rememberSaveable(visible, id) { mutableStateOf<String?>(null) }
    val method = selected?.takeIf { selection -> methods.any { it.first == selection } }
    val editMessage = method == "MERGE" || method == "SQUASH"
    var editedHeadline by
        rememberSaveable(visible, id, head, base, method) { mutableStateOf<String?>(null) }
    var editedBody by
        rememberSaveable(visible, id, head, base, method) { mutableStateOf<String?>(null) }
    var retry by remember(visible, id, head, base, method) { mutableStateOf(0) }
    var defaults by
        remember(visible, id, head, base, method, retry) { mutableStateOf<WorkMergeMessage?>(null) }
    var failed by remember(visible, id, head, base, method, retry) { mutableStateOf(false) }
    LaunchedEffect(visible, id, head, base, method, retry) {
        if (!visible || id == null || method == null || !editMessage) return@LaunchedEffect
        try {
            defaults = loadMessage(id, method)
        } catch (error: Exception) {
            if (
                error !is GithubPageException &&
                    error !is HttpException &&
                    error !is IOException &&
                    error !is SerializationException
            ) {
                throw error
            }
            failed = true
        }
    }
    GithubLoadFailureToast(
        failure = retry.takeIf { visible && failed },
        message = strings.MergeMessageLoadFailed,
    )
    val headline = editedHeadline ?: defaults?.headline.orEmpty()
    val body = editedBody ?: defaults?.body.orEmpty()
    val busy = state.submitting
    val messageReady = defaults != null || failed
    val canConfirm =
        method != null &&
            id != null &&
            head != null &&
            item?.canMerge() == true &&
            !busy &&
            !state.loading &&
            !state.submissionUncertain &&
            (!editMessage || messageReady && headline.isNotBlank())
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (!busy) onDismiss() },
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
        title = strings.MergePullRequest,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = strings.MergePullRequest,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !busy) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = {
                            if (canConfirm && item != null && method != null) {
                                val message =
                                    if (editMessage) WorkMergeMessage(headline.trim(), body)
                                    else null
                                onMerge(item, method, message)
                            }
                        },
                        enabled = canConfirm,
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = strings.MergePullRequest)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LocalGlasmioSheetContentPadding.current)
                .animateContentSize(tween(420))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlasmioGroupedList {
                methods.forEach { (method, label) ->
                    GlasmioListRow(
                        title = label,
                        systemName = null,
                        trailing = {
                            if (selected == method)
                                GithubIcon(Lucide.Check, contentDescription = null)
                        },
                        onClick = if (busy) null else ({ selected = method }),
                    )
                }
            }
            if (editMessage) {
                GithubCommitMessageFields(
                    headline = headline,
                    onHeadlineChange = { editedHeadline = it },
                    body = body,
                    onBodyChange = { editedBody = it },
                    enabled = messageReady && !busy,
                )
                if (failed) {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = common.Retry,
                            onClick = if (busy) null else ({ retry++ }),
                        )
                    }
                }
            }
        }
    }
}
