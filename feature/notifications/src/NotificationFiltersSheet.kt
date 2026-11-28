package moe.yumeyuka.yumehub.feature.notifications

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.SearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.SectionHeader

@Composable
internal fun NotificationFiltersSheet(
    visible: Boolean,
    repositories: List<String>,
    selectedRepository: String?,
    selectedReason: NotificationReasonFilter,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (String?, NotificationReasonFilter) -> Unit,
) {
    val strings = LocalAppStrings.current.Notifications
    val common = LocalAppStrings.current.Common
    var query by remember(visible) { mutableStateOf("") }
    var draftRepository by remember(visible, selectedRepository) {
        mutableStateOf(selectedRepository)
    }
    var draftReason by remember(visible, selectedReason) { mutableStateOf(selectedReason) }
    val normalizedQuery = query.trim()
    val repositoryOptions =
        buildList {
            add(null to strings.AllRepositories)
            repositories.forEach { add(it to it) }
        }.filter { (_, label) -> label.contains(normalizedQuery, ignoreCase = true) }
    val reasonOptions =
        NotificationReasonFilter.entries.filter {
            it.label(strings).contains(normalizedQuery, ignoreCase = true)
        }

    SearchSelectionSheet(
        visible = visible,
        title = strings.FilterTitle,
        placeholder = common.Search,
        queryKey = query,
        onQueryChange = { query = it },
        onDismiss = onDismiss,
        onConfirm = {
            onApply(draftRepository, draftReason)
            onDismiss()
        },
        enabled = enabled,
    ) { _ ->
        if (repositoryOptions.isEmpty() && reasonOptions.isEmpty()) {
            item {
                GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                    GlasmioListRow(title = strings.NoMatchingFilters, showDisclosure = false)
                }
            }
        } else {
            if (repositoryOptions.isNotEmpty()) {
                item { SectionHeader(strings.Repository) }
                item {
                    GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                        repositoryOptions.forEachIndexed { index, (repository, label) ->
                            filterRow(
                                title = label,
                                selected = draftRepository == repository,
                                showTopSeparator = index > 0,
                                onClick = { draftRepository = repository },
                            )
                        }
                    }
                }
            }

            if (reasonOptions.isNotEmpty()) {
                item { SectionHeader(strings.Reason) }
                item {
                    GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                        reasonOptions.forEachIndexed { index, reason ->
                            filterRow(
                                title = reason.label(strings),
                                selected = draftReason == reason,
                                showTopSeparator = index > 0,
                                onClick = { draftReason = reason },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun filterRow(
    title: String,
    selected: Boolean,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = title,
        modifier = Modifier.semantics { this.selected = selected },
        trailing = {
            if (selected) AppIcon(Lucide.Check, contentDescription = null, size = 20.dp)
        },
        showDisclosure = false,
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}
