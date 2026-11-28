package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.WorkStatusTag
import moe.yumeyuka.yumehub.ui.component.labelColor
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.theme.WorkColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkDetailHeader(
    state: CollaborationDetailUiState,
    kind: CollaborationKind,
    onInformation: () -> Unit,
    onLabels: () -> Unit,
    onProjects: () -> Unit,
    onReviewers: () -> Unit,
    onChecks: () -> Unit,
    onIssueMetadata: (IssueMetadataPanel) -> Unit,
) {
    val entry = state.item
    val enabled = !state.submitting || state.sendingInBackground
    val isPullRequest = kind == CollaborationKind.PullRequest
    val strings = LocalAppStrings.current.WorkDetail
    val projectTitle =
        state.projectTitles?.let { it.firstOrNull() ?: strings.NoProjects } ?: strings.Projects
    GlasmioGroupedList {
        LoadingContent(
            loading = entry == null,
            placeholder = { WorkHeaderPlaceholder() },
        ) {
            if (entry != null)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WorkStatusTag(
                        item = entry,
                        kind = kind,
                        onClick = onInformation,
                        enabled = enabled,
                    )
                    WorkHeaderLabels(entry.labels, enabled, onLabels)
                    WorkHeaderProject(projectTitle, enabled, onProjects)
                    if (isPullRequest) WorkHeaderReviewers(entry, enabled, onReviewers)
                    if (kind == CollaborationKind.Issue) {
                        val all = LocalAppStrings.current
                        if (entry.viewerCanTypeIssue || entry.issueType != null) {
                            WorkHeaderButton(
                                enabled,
                                { onIssueMetadata(IssueMetadataPanel.Type) },
                            ) {
                                AppIcon(
                                    Lucide.CircleDot,
                                    contentDescription = null,
                                    size = 16.dp,
                                    tint = issueOptionColor(entry.issueTypeColor),
                                )
                                WorkHeaderText(entry.issueType ?: all.WorkList.IssueType)
                            }
                        }
                        WorkHeaderButton(
                            enabled,
                            { onIssueMetadata(IssueMetadataPanel.Assignees) },
                        ) {
                            AppIcon(
                                Lucide.UserRound,
                                contentDescription = null,
                                size = 16.dp,
                                tint = WorkColors.muted,
                            )
                            WorkHeaderText(
                                when (entry.assignedActors.size) {
                                    0 -> strings.Unassigned
                                    1 -> entry.assignedActors.single()
                                    else -> strings.AssigneeCount(entry.assignedActors.size)
                                },
                            )
                        }
                    }
                    val progress = entry.taskProgress
                    WorkHeaderButton(enabled, onInformation) {
                        AppIcon(
                            Lucide.ListChecks,
                            contentDescription = null,
                            size = 16.dp,
                            tint = WorkColors.muted,
                        )
                        WorkHeaderText(
                            strings.TaskProgress(progress?.completed ?: 0, progress?.total ?: 0),
                        )
                    }
                    if (isPullRequest) WorkHeaderChecks(entry, enabled, onChecks)
                }
        }
    }
}

@Composable
private fun WorkHeaderLabels(
    labels: List<CollaborationLabelDto>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current.WorkDetail
    WorkHeaderButton(enabled, onClick) {
        if (labels.isEmpty()) {
            AppIcon(
                Lucide.Tag,
                contentDescription = null,
                size = 16.dp,
                tint = WorkColors.muted,
            )
            WorkHeaderText(strings.NoLabels)
        } else {
            val outline = GlasmioTheme.colors.elevatedBackground
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                for (index in 0 until minOf(labels.size, 3)) {
                    Box(
                        Modifier.zIndex(-index.toFloat())
                            .size(12.dp)
                            .background(labelColor(labels[index].color), CircleShape)
                            .border(1.dp, outline, CircleShape),
                    )
                }
            }
            WorkHeaderText(strings.LabelCount(labels.size))
        }
    }
}

@Composable
private fun WorkHeaderProject(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkHeaderButton(enabled, onClick, modifier) {
        AppIcon(
            Lucide.Package,
            contentDescription = null,
            size = 16.dp,
            tint = WorkColors.muted,
        )
        WorkHeaderText(title, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun WorkHeaderReviewers(
    entry: CollaborationItemDto,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current.WorkDetail
    val reviewers = entry.requestedReviewers
    WorkHeaderButton(enabled, onClick, modifier) {
        if (reviewers.isEmpty()) {
            AppIcon(
                Lucide.UserRound,
                contentDescription = null,
                size = 16.dp,
                tint = WorkColors.muted,
            )
            WorkHeaderText(strings.NoReviewRequests, Modifier.weight(1f, fill = false))
        } else {
            reviewers.take(3).forEachIndexed { index, login ->
                UserAvatar(
                    url = entry.requestedReviewerAvatars.getOrNull(index),
                    login = login,
                    size = 16.dp,
                )
            }
            WorkHeaderText(
                strings.ReviewRequests(entry.reviewRequestCount ?: reviewers.size),
                Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun WorkHeaderChecks(entry: CollaborationItemDto, enabled: Boolean, onClick: () -> Unit) {
    val total = entry.requiredStatusCheckCount?.takeIf { it > 0 } ?: return
    val strings = LocalAppStrings.current.WorkDetail
    val checks = entry.requiredStatusChecks
    val passed = checks.count { it.state == "SUCCESS" }
    val allPassed = passed == total && checks.size == total
    val color =
        when {
            allPassed -> WorkColors.open
            checks.any { it.state == "FAILURE" || it.state == "ERROR" } -> WorkColors.closed
            checks.any {
                it.state == "PENDING" || it.state == "EXPECTED" || it.state == "IN_PROGRESS"
            } -> WorkColors.draft
            else -> WorkColors.muted
        }
    val label =
        when {
            allPassed -> strings.ChecksPassed(passed, total)
            checks.size == total -> strings.ChecksProgress(passed, total)
            else -> strings.RequiredChecks(total)
        }
    WorkHeaderButton(enabled, onClick) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        WorkHeaderText(label)
    }
}

@Composable
private fun WorkHeaderButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    GlasmioButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        sampleBackdrop = false,
        size = GlasmioButtonSize.Small,
        content = content,
    )
}

@Composable
private fun WorkHeaderText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        modifier = modifier,
        textAlign = textAlign,
        style = GlasmioTheme.textStyles.footnote,
        color = GlasmioTheme.colors.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
