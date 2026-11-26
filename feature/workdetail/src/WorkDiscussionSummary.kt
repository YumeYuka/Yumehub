package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.githubDiffColors
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

@Composable
internal fun WorkDiscussionSummary(
    item: CollaborationItemDto?,
    enabled: Boolean,
    onStatus: (WorkStatusPanel) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenCommits: () -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    Column {
        WorkSummarySection(strings.Changes, item) { entry ->
            GlasmioListRow(
                title = entry.changedFiles?.let(strings.FileCount) ?: strings.Files,
                trailing = {
                    GithubCardField(
                        entry.takeIf { it.additions != null && it.deletions != null },
                    ) { diff ->
                        val colors = githubDiffColors(GlasmioTheme.colors.isDark)
                        Row {
                            Text(
                                "+${diff.additions}",
                                color = colors.additionForeground,
                                style = GlasmioTheme.textStyles.subheadline,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "−${diff.deletions}",
                                color = colors.deletionForeground,
                                style = GlasmioTheme.textStyles.subheadline,
                            )
                        }
                    }
                },
                leading = {
                    GithubIcon(
                        Lucide.FileText,
                        contentDescription = null,
                        size = 20.dp,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                },
                systemName = null,
                showTopSeparator = false,
                onClick = if (enabled) onOpenFiles else null,
            )
            GlasmioListRow(
                title = entry.commitCount?.let(strings.CommitCount) ?: strings.Commits,
                leading = {
                    GithubIcon(
                        Lucide.GitCommitHorizontal,
                        contentDescription = null,
                        size = 20.dp,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                },
                systemName = null,
                onClick = if (enabled) onOpenCommits else null,
            )
        }
        WorkSummarySection(strings.Status, item) { entry ->
            val reviewColor =
                when (entry.viewerLatestReviewState.takeUnless { entry.viewerReviewRequested }) {
                    "APPROVED" -> GithubWorkColors.open
                    "CHANGES_REQUESTED" -> GithubWorkColors.closed
                    else -> GithubWorkColors.draft
                }
            GlasmioListRow(
                title = strings.Review,
                detail = entry.viewerReviewLabel(strings),
                leading = {
                    GithubIcon(
                        Lucide.MessageCircleCheck,
                        contentDescription = null,
                        size = 20.dp,
                        tint = reviewColor,
                    )
                },
                systemName = null,
                showTopSeparator = false,
                onClick = if (enabled) ({ onStatus(WorkStatusPanel.Reviews) }) else null,
            )
            val failedChecks =
                entry.requiredStatusChecks.count { it.state in setOf("FAILURE", "ERROR") }
            val checksPending =
                entry.requiredStatusChecks.any {
                    it.state in setOf("PENDING", "EXPECTED", "IN_PROGRESS")
                }
            val checksIcon =
                when {
                    entry.allStatusChecksPassed -> Lucide.CircleCheck
                    failedChecks > 0 -> Lucide.CircleSlash
                    else -> Lucide.CircleDot
                }
            val checksColor =
                when {
                    entry.allStatusChecksPassed -> GithubWorkColors.open
                    failedChecks > 0 -> GithubWorkColors.closed
                    checksPending -> GithubWorkColors.draft
                    else -> GlasmioTheme.colors.secondaryContent
                }
            val requiredCount = entry.requiredStatusCheckCount ?: 0
            val passedCount = entry.requiredStatusChecks.count { it.state == "SUCCESS" }
            GlasmioListRow(
                title = strings.Checks,
                leading = {
                    GithubIcon(
                        checksIcon,
                        contentDescription = null,
                        size = 20.dp,
                        tint = checksColor,
                    )
                },
                detail = if (requiredCount > 0) "$passedCount/$requiredCount" else null,
                systemName = null,
                onClick = if (enabled) ({ onStatus(WorkStatusPanel.Checks) }) else null,
            )
        }
    }
}

@Composable
private fun WorkSummarySection(
    title: String,
    item: CollaborationItemDto?,
    rows: @Composable (CollaborationItemDto) -> Unit,
) {
    GithubSection(title) {
        GithubLoadingContent(
            loading = item == null,
            placeholder = {
                Column {
                    WorkInfoRowPlaceholder()
                    WorkInfoRowPlaceholder(showTopSeparator = true)
                }
            },
        ) {
            if (item != null) Column { rows(item) }
        }
    }
}
