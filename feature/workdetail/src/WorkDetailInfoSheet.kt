package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import java.io.IOException
import kotlinx.serialization.SerializationException
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.canMerge
import moe.yumeyuka.yumehub.data.repository.GithubPageException
import moe.yumeyuka.yumehub.data.repository.WorkMergeMessage
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitMessageFields
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMessageBodyField
import retrofit2.HttpException

internal fun CollaborationItemDto.viewerReviewLabel(strings: GithubStrings_WorkDetail): String? =
    if (viewerReviewRequested) strings.ReviewPending else viewerLatestReviewState?.let(strings.ReviewState)

@Composable
internal fun WorkChecksSheet(
    visible: Boolean,
    item: CollaborationItemDto?,
    onDismiss: () -> Unit,
    checks: @Composable () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val title = strings.WorkDetail.Checks
    val scroll = rememberScrollState()
    val contentPadding = 12.dp
    val fadeDistance = with(LocalDensity.current) { contentPadding.toPx() }
    LaunchedEffect(visible) { if (visible) scroll.scrollTo(0) }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        // 分页检查使用固定停靠位置，不随占位、结果和重试行的高度改变。
        skipPartiallyExpanded = false,
        topBarBlurProgress = (scroll.value / fadeDistance).coerceIn(0f, 1f),
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(scroll)
                .padding(LocalGlasmioSheetContentPadding.current)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item?.let {
                GlasmioGroupedList {
                    val mergeStatus =
                        it.mergeStateStatus?.let(strings.WorkDetail.MergeStatus)
                            ?: it.mergeable?.let(strings.WorkDetail.MergeStatus)
                            ?: strings.WorkDetail.MergeRequirements
                    GlasmioListRow(title = mergeStatus, showTopSeparator = false)
                }
            }
            checks()
        }
    }
}

@Composable
internal fun WorkReviewSheet(
    visible: Boolean,
    state: CollaborationDetailUiState,
    onDismiss: () -> Unit,
    onReviewEvent: (PullRequestReviewEvent) -> Unit,
    onBodyChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val review = strings.WorkDetail
    val busy = state.submitting
    val editable = state.canReview && !busy
    val result = state.item?.viewerReviewLabel(review)
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (!busy) onDismiss() },
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
        fitContent = true,
        title = review.Review,
        topBar = {
            GlasmioTopBar(
                title = review.Review,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !busy) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(onClick = onSubmit, enabled = state.canSubmitReview) {
                        GithubIcon(Lucide.MessageCircleCheck, contentDescription = review.SubmitReview)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LocalGlasmioSheetContentPadding.current)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result != null) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = review.ReviewResult,
                        detail = result,
                        showTopSeparator = false,
                    )
                }
            }
            if (state.canReview || state.reviewDraft.isNotEmpty()) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = review.Reviews,
                        showTopSeparator = false,
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
                                enabled = editable,
                            )
                        },
                    )
                }
                GithubMessageBodyField(state.reviewDraft, onBodyChange, review.WriteComment, editable)
            } else if (result == null && !state.loading) {
                GlasmioGroupedList {
                    GlasmioListRow(title = review.ReviewUnavailable, showTopSeparator = false)
                }
            }
        }
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
            item.canMerge() &&
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
                            if (canConfirm) {
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
