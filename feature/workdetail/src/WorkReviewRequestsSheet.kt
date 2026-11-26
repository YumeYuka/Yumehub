package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.UsersRound
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
@Composable
internal fun WorkReviewRequestsSheet(
    state: WorkPropertiesState,
    viewModel: WorkInfoViewModel?,
    onDismiss: () -> Unit,
) {
    val locale = LocalGithubStrings.current
    val strings = locale.WorkDetail
    val live = state.editor?.takeIf { it.property == WorkProperty.Reviewers }
    // 保存成功后编辑器即被清空；保留最近一次快照，退出动画期间内容不变成空白。
    var retained by remember { mutableStateOf<WorkPropertyEditor?>(null) }
    androidx.compose.runtime.SideEffect { if (live != null) retained = live }
    val editor = live ?: retained
    LaunchedEffect(viewModel) { viewModel?.edit(WorkProperty.Reviewers) }
    if (state.saveFailed) GithubLoadFailureToast(Unit, strings.PropertySaveFailed)
    val editable =
        live != null && live.editable && !live.loading && !live.failed &&
            !live.needsReconcile && !state.submitting
    val shown = remember(editor) { editor?.reviewerGroups() }
    GithubSearchSelectionSheet(
        // visible 保持 true 直到收起动画结束：动画完成后 onDismissRequest 才触发。
        visible = true,
        title = strings.RequestReview,
        placeholder = strings.PropertySearch,
        queryKey = editor?.query.orEmpty(),
        onQueryChange = { viewModel?.search(it) },
        onDismiss = {
            viewModel?.closeEditor()
            retained = null
            onDismiss()
        },
        onConfirm = { viewModel?.applyEditor() },
        confirmEnabled = editable && editor?.reviewRequestsChanged() == true,
        showConfirm = editor?.editable == true,
        requestClose = live == null && retained != null,
    ) {
        if (editor == null || shown == null || editor.loading) {
            item("review-requests-loading") {
                GlasmioGroupedList {
                    repeat(3) {
                        TextPlaceholder(Modifier.padding(16.dp), fraction = 0.45f)
                    }
                }
            }
        } else if (editor.failed || editor.needsReconcile) {
            item("review-requests-retry") {
                var acknowledgeVisible by remember { mutableStateOf(false) }
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = if (state.uncertain) locale.Common.MutationReconcile else locale.Common.Retry,
                        subtitle =
                            when {
                                state.uncertain -> locale.Common.MutationUncertain
                                editor.needsReconcile -> strings.PropertySaveFailed
                                else -> strings.PropertyLoadFailed
                            },
                        onClick = { viewModel?.retryEditor() },
                        showTopSeparator = false,
                    )
                    if (state.uncertain) {
                        GlasmioListRow(
                            title = locale.Common.MutationAcknowledged,
                            showTopSeparator = true,
                            showDisclosure = false,
                            onClick = { acknowledgeVisible = true },
                        )
                    }
                }
                if (acknowledgeVisible && state.uncertain) {
                    io.github.neoruaa.glasmio.ui.GlasmioAlertDialog(
                        visible = true,
                        title = locale.Common.MutationUncertain,
                        message = locale.Common.MutationAcknowledgeWarning,
                        buttons =
                            listOf(
                                io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec(
                                    locale.Common.Cancel,
                                    onClick = { acknowledgeVisible = false },
                                    role = io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole.Cancel,
                                ),
                                io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec(
                                    locale.Common.MutationAcknowledged,
                                    onClick = {
                                        acknowledgeVisible = false
                                        viewModel?.acknowledgeUncertain()
                                    },
                                    role = io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole.Default,
                                ),
                            ),
                        onDismissRequest = { acknowledgeVisible = false },
                    )
                }
            }
        } else {
            reviewerGroup("pending", strings.ReviewPending, shown.pending, editable) { viewModel?.select(it) }
            reviewerGroup("reviewed", strings.ReviewCompleted, shown.reviewed, editable) { viewModel?.select(it) }
            reviewerGroup("available", strings.ReviewAvailable, shown.available, editable) { viewModel?.select(it) }
            if (editor.editable && (!editor.hasReviewerCandidates || editor.nextCursor != null)) {
                item("review-candidates-page") {
                    if (editor.moreFailed) {
                        GlasmioListRow(title = locale.Common.Retry, onClick = { viewModel?.more() })
                    } else {
                        LaunchedEffect(editor.query, editor.nextCursor) { viewModel?.more() }
                        TextPlaceholder(Modifier.padding(16.dp), fraction = 0.45f)
                    }
                }
            }
            if ((!editor.editable || (editor.hasReviewerCandidates && editor.nextCursor == null)) &&
                shown.pending.isEmpty() && shown.reviewed.isEmpty() && shown.available.isEmpty()) {
                item("review-requests-empty") {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.NoPropertyOptions,
                            showTopSeparator = false,
                        )
                    }
                }
            }
        }
    }
}

private data class ReviewerGroups(
    val pending: List<WorkPropertyOption>,
    val reviewed: List<WorkPropertyOption>,
    val available: List<WorkPropertyOption>,
)

private fun WorkPropertyEditor.reviewerGroups(): ReviewerGroups {
    val selectedIds = selected.map(WorkPropertyOption::id).toSet()
    val reviewedById = reviewed.associateBy(WorkPropertyOption::id)
    val term = query.trim()
    fun WorkPropertyOption.matches() = title.contains(term, ignoreCase = true)
    return ReviewerGroups(
        pending = selected.filter { it.matches() }.map { it.copy(state = reviewedById[it.id]?.state) },
        reviewed = reviewed.filter { it.id !in selectedIds && it.matches() },
        available = (original + options).distinctBy(WorkPropertyOption::id).filter {
            it.id !in selectedIds && it.id !in reviewedById && it.matches()
        },
    )
}

private fun LazyListScope.reviewerGroup(
    group: String,
    title: String,
    reviewers: List<WorkPropertyOption>,
    editable: Boolean,
    onToggle: (WorkPropertyOption) -> Unit,
) {
    if (reviewers.isEmpty()) return
    item("review-requests:$group") {
        GithubSectionHeader(title, Modifier.animateItem().padding(bottom = 8.dp))
    }
    itemsIndexed(reviewers, key = { _, reviewer -> "review-request:${reviewer.id}" }) { index, reviewer ->
        val strings = LocalGithubStrings.current.WorkDetail
        val pending = group == "pending"
        val detail = when {
            pending && reviewer.state != null -> strings.ReviewAgain
            pending -> strings.ReviewPending
            else -> reviewer.state?.let(strings.ReviewState) ?: strings.ReviewNotRequested
        }
        val first = if (index == 0) 26.dp else 0.dp
        val last = if (index == reviewers.lastIndex) 26.dp else 0.dp
        GlasmioGroupedList(
            framed = false,
            modifier = Modifier.animateItem().glasmioGroupedListBackground(
                ContinuousRoundedRectangle(
                    topStart = first,
                    topEnd = first,
                    bottomStart = last,
                    bottomEnd = last,
                ),
            ),
        ) {
            GlasmioListRow(
                title = reviewer.title,
                modifier =
                    if (editable && reviewer.editable) Modifier.semantics { selected = pending }
                    else Modifier,
                detail = detail,
                leading = {
                    if (!reviewer.avatarUrl.isNullOrBlank()) {
                        GithubAvatar(reviewer.avatarUrl, reviewer.title, size = 20.dp)
                    } else {
                        GithubIcon(Lucide.UsersRound, contentDescription = null, size = 20.dp)
                    }
                },
                onClick = if (editable && reviewer.editable) ({ onToggle(reviewer) }) else null,
                showTopSeparator = index != 0,
                showDisclosure = false,
            )
        }
    }
}
