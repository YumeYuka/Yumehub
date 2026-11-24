package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubWorkStatusTag
import moe.yumeyuka.yumehub.ui.component.githubLabelColor
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

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
    val strings = LocalGithubStrings.current.WorkDetail
    val projectTitle = state.projectTitles.firstOrNull()
    GlasmioGroupedList {
        if (entry == null) {
            Box(Modifier.fillMaxWidth().height(58.dp).padding(16.dp)) {
                TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.55f)
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GithubWorkStatusTag(
                    item = entry,
                    kind = kind,
                    onClick = onInformation,
                    enabled = enabled,
                )
                WorkHeaderLabels(entry.labels, enabled, onLabels)
                WorkHeaderProject(projectTitle, enabled, onProjects)
                if (isPullRequest) WorkHeaderReviewers(entry, enabled, onReviewers)
                if (kind == CollaborationKind.Issue) {
                    val all = LocalGithubStrings.current
                    if (entry.viewerCanTypeIssue || entry.issueType != null) {
                        WorkHeaderButton(enabled, { onIssueMetadata(IssueMetadataPanel.Type) }) {
                            GithubIcon(
                                Lucide.CircleDot, contentDescription = null, size = 16.dp,
                                tint = issueOptionColor(entry.issueTypeColor),
                            )
                            WorkHeaderText(entry.issueType ?: all.WorkList.IssueType)
                        }
                    }
                    WorkHeaderButton(enabled, { onIssueMetadata(IssueMetadataPanel.Assignees) }) {
                        GithubIcon(
                            Lucide.UserRound,
                            contentDescription = null,
                            size = 16.dp,
                            tint = GithubWorkColors.muted,
                        )
                        WorkHeaderText(
                            when (entry.assignedActors.size) {
                                0 -> strings.Unassigned
                                1 -> entry.assignedActors.single()
                                else -> strings.AssigneeCount(entry.assignedActors.size)
                            }
                        )
                    }
                }
                val progress = entry.taskProgress
                WorkHeaderButton(enabled, onInformation) {
                    GithubIcon(
                        Lucide.ListChecks,
                        contentDescription = null,
                        size = 16.dp,
                        tint = GithubWorkColors.muted,
                    )
                    WorkHeaderText(strings.TaskProgress(progress?.completed ?: 0, progress?.total ?: 0))
                }
                if (isPullRequest) WorkHeaderChecks(entry, enabled, onChecks)
            }
        }
    }
}

@Composable
private fun WorkHeaderLabels(labels: List<CollaborationLabelDto>, enabled: Boolean, onClick: () -> Unit) {
    val strings = LocalGithubStrings.current.WorkDetail
    WorkHeaderButton(enabled, onClick) {
        if (labels.isEmpty()) {
            GithubIcon(
                Lucide.Tag,
                contentDescription = null,
                size = 16.dp,
                tint = GithubWorkColors.muted,
            )
            WorkHeaderText(strings.NoLabels)
        } else {
            val outline = GlasmioTheme.colors.elevatedBackground
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                for (index in 0 until minOf(labels.size, 3)) {
                    Box(
                        Modifier.zIndex(-index.toFloat())
                            .size(12.dp)
                            .background(githubLabelColor(labels[index].color), CircleShape)
                            .border(1.dp, outline, CircleShape)
                    )
                }
            }
            WorkHeaderText(strings.LabelCount(labels.size))
        }
    }
}

@Composable
private fun WorkHeaderProject(
    title: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    WorkHeaderButton(enabled, onClick, modifier) {
        GithubIcon(
            Lucide.Package,
            contentDescription = null,
            size = 16.dp,
            tint = GithubWorkColors.muted,
        )
        WorkHeaderText(title ?: strings.NoProjects, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun WorkHeaderReviewers(
    entry: CollaborationItemDto,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val reviewers = entry.requestedReviewers
    WorkHeaderButton(enabled, onClick, modifier) {
        if (reviewers.isEmpty()) {
            GithubIcon(
                Lucide.UserRound,
                contentDescription = null,
                size = 16.dp,
                tint = GithubWorkColors.muted,
            )
            WorkHeaderText(strings.NoReviewRequests, Modifier.weight(1f, fill = false))
        } else {
            reviewers.take(3).forEachIndexed { index, login ->
                GithubAvatar(
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
    val strings = LocalGithubStrings.current.WorkDetail
    val checks = entry.requiredStatusChecks
    val passed = checks.count { it.state == "SUCCESS" }
    val allPassed = passed == total && checks.size == total
    val color =
        when {
            allPassed -> GithubWorkColors.open
            checks.any { it.state == "FAILURE" || it.state == "ERROR" } -> GithubWorkColors.closed
            checks.any {
                it.state == "PENDING" || it.state == "EXPECTED" || it.state == "IN_PROGRESS"
            } -> GithubWorkColors.draft
            else -> GithubWorkColors.muted
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
