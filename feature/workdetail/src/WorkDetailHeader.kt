package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLabelChip
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
    onChecks: () -> Unit,
) {
    val entry = state.item
    val enabled = !state.submitting || state.sendingInBackground
    val isPullRequest = kind == CollaborationKind.PullRequest
    val strings = LocalGithubStrings.current.WorkDetail
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
                Row(
                    modifier = if (isPullRequest) Modifier.fillMaxWidth() else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GithubWorkStatusTag(
                        item = entry,
                        kind = kind,
                        onClick = onInformation,
                        enabled = enabled,
                    )
                    if (isPullRequest) {
                        val head = entry.head?.ref?.takeIf(String::isNotBlank)
                        val base = entry.baseRefName?.takeIf(String::isNotBlank)
                        if (head != null && base != null) {
                            WorkHeaderBranches(
                                head = head,
                                base = base,
                                enabled = enabled,
                                onClick = onInformation,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
                if (entry.labels.size > 2) {
                    WorkHeaderButton(enabled, onInformation) {
                        WorkHeaderLabelColors(entry.labels)
                        WorkHeaderText(strings.LabelCount(entry.labels.size))
                    }
                } else {
                    entry.labels.forEach { label ->
                        GithubLabelChip(
                            name = label.name,
                            color = label.color,
                            enabled = enabled,
                            onClick = onInformation,
                        )
                    }
                }
                entry.taskProgress?.let { progress ->
                    WorkHeaderButton(enabled, onInformation) {
                        GithubIcon(Lucide.ListChecks, contentDescription = null, size = 16.dp)
                        WorkHeaderText(strings.TaskProgress(progress.completed, progress.total))
                    }
                }
                if (isPullRequest) WorkHeaderChecks(entry, enabled, onChecks)
            }
        }
    }
}

@Composable
private fun WorkHeaderBranches(
    head: String,
    base: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalGithubStrings.current.WorkList
    WorkHeaderButton(enabled, onClick, modifier) {
        GithubIcon(Lucide.GitBranchPlus, contentDescription = strings.HeadBranch, size = 16.dp)
        WorkHeaderText(head, Modifier.weight(1f, fill = false))
        GithubIcon(Lucide.ArrowRight, contentDescription = strings.BaseBranch, size = 16.dp)
        WorkHeaderText(base, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun WorkHeaderLabelColors(labels: List<CollaborationLabelDto>) {
    val outline = GlasmioTheme.colors.elevatedBackground
    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
        labels.take(3).forEachIndexed { index, label ->
            Box(
                Modifier.zIndex(-index.toFloat())
                    .size(12.dp)
                    .background(githubLabelColor(label.color), CircleShape)
                    .border(1.dp, outline, CircleShape)
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
            else -> GlasmioTheme.colors.secondaryContent
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
private fun WorkHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = GlasmioTheme.textStyles.footnote,
        color = GlasmioTheme.colors.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
