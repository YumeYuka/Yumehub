package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

/** 仅提供评论目标的上下文，正文、预览与提交统一由共享 Markdown 编辑器承载。 */
@Composable
internal fun CollaborationEditorHeader(
    viewModel: CollaborationDetailViewModel,
    target: CollaborationEditorTarget,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.WorkDetail
    when (target) {
        CollaborationEditorTarget.Review ->
            GlasmioListRow(
                title = strings.ReviewResult,
                systemName = null,
                trailing = {
                    GlasmioPopUpButton(
                        selected = state.reviewEvent,
                        items = PullRequestReviewEvent.entries,
                        onSelected = viewModel::selectReviewEvent,
                        label = { event ->
                            when (event) {
                                PullRequestReviewEvent.Comment -> strings.Comment
                                PullRequestReviewEvent.Approve -> strings.Approve
                                PullRequestReviewEvent.RequestChanges -> strings.RequestChanges
                            }
                        },
                        enabled = !state.submitting && !state.submissionUncertain,
                    )
                },
            )
        CollaborationEditorTarget.LineComment ->
            state.reviewLine?.let { line ->
                Text(
                    strings.LineContext(line.path, line.line, line.side == "RIGHT"),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = GlasmioTheme.textStyles.footnote,
                    color = GlasmioTheme.colors.secondaryContent,
                )
            }
        else -> Unit
    }
}
