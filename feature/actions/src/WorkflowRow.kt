package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.runtime.Composable
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.WorkflowRow

@Composable
internal fun WorkflowRow(
    entry: RepositoryActivityEntry,
    onClick: (() -> Unit)?,
    separator: Boolean = false,
) {
    val strings = LocalAppStrings.current
    WorkflowRow(
        title = entry.title,
        state = entry.state,
        stateDescription = entry.state?.let(strings.Activity.State),
        subtitle =
            listOfNotNull(
                    entry.runCount?.let(strings.Activity.WorkflowRunCount),
                    entry.workflowName?.takeUnless { it == entry.title },
                    entry.number?.let { "#$it" },
                    entry.subtitle,
                    entry.event?.lowercase(),
                    entry.login?.let { "@$it" },
                )
                .joinToString(" · ")
                .ifEmpty { null },
        showTopSeparator = separator,
        onClick = onClick,
    )
}
