package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

@Composable
fun GithubWorkflowRow(
    title: String,
    state: String?,
    stateDescription: String?,
    subtitle: String?,
    showTopSeparator: Boolean,
    onClick: (() -> Unit)?,
) {
    val muted = GlasmioTheme.colors.secondaryContent
    val (icon, color) =
        when (state?.uppercase()) {
            "ACTIVE" -> Lucide.CirclePlay to GlasmioTheme.colors.content
            "SUCCESS" -> Lucide.CircleCheck to GithubWorkColors.open
            "FAILURE",
            "ERROR",
            "TIMED_OUT",
            "STARTUP_FAILURE",
            "ACTION_REQUIRED" -> Lucide.CircleX to GithubWorkColors.closed
            "IN_PROGRESS" -> Lucide.CirclePlay to GlasmioTheme.colors.accent
            "QUEUED",
            "REQUESTED",
            "PENDING",
            "WAITING",
            "EXPECTED" -> Lucide.Clock to muted
            "COMPLETED" -> Lucide.CircleCheck to muted
            "CANCELLED",
            "SKIPPED",
            "STALE",
            "DELETED",
            "DISABLED_FORK",
            "DISABLED_INACTIVITY",
            "DISABLED_MANUALLY" -> Lucide.CircleSlash to muted
            else -> Lucide.Circle to muted
        }
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        leading = {
            GithubIcon(icon, contentDescription = stateDescription, size = 20.dp, tint = color)
        },
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}
