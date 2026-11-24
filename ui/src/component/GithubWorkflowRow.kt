package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        leading = { GithubWorkflowStatusIcon(state, stateDescription, size = 20.dp) },
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}

@Composable
fun GithubWorkflowStatusIcon(state: String?, contentDescription: String?, size: Dp) {
    val muted = GlasmioTheme.colors.secondaryContent
    val icon =
        when (state?.uppercase()) {
            "ACTIVE", "IN_PROGRESS" -> Lucide.CirclePlay
            "SUCCESS", "COMPLETED" -> Lucide.CircleCheck
            "FAILURE", "ERROR", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED" -> Lucide.CircleX
            "QUEUED", "REQUESTED", "PENDING", "WAITING", "EXPECTED" -> Lucide.Clock
            "CANCELLED", "SKIPPED", "STALE", "DELETED", "DISABLED_FORK",
            "DISABLED_INACTIVITY", "DISABLED_MANUALLY" -> Lucide.CircleSlash
            else -> Lucide.Circle
        }
    val color =
        when (icon) {
            Lucide.CircleX -> GithubWorkColors.closed
            Lucide.CircleCheck -> if (state.equals("SUCCESS", true)) GithubWorkColors.open else muted
            Lucide.CirclePlay ->
                if (state.equals("IN_PROGRESS", true)) GlasmioTheme.colors.accent
                else GlasmioTheme.colors.content
            else -> muted
        }
    GithubIcon(icon, contentDescription = contentDescription, size = size, tint = color)
}
