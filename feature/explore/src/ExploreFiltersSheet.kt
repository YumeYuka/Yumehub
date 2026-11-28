package moe.yumeyuka.yumehub.feature.explore

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.locale.ExploreStrings
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.FilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.SearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.ListSection

@Composable
internal fun ExploreFiltersSheet(
    visible: Boolean,
    options: FeedOptions,
    onDismiss: () -> Unit,
    onApply: (FeedOptions) -> Unit,
) {
    val strings = LocalAppStrings.current
    val explore = strings.Explore
    var query by remember(visible) { mutableStateOf("") }
    var draft by remember(visible, options) { mutableStateOf(options) }
    val term = query.trim()
    val sorts =
        FeedSort.entries
            .map { it to it.label(explore) }
            .filter { (_, label) -> label.contains(term, ignoreCase = true) }
    val activities =
        FeedFilter.entries
            .map { it to it.label(explore) }
            .filter { (_, label) -> label.contains(term, ignoreCase = true) }
    val showAll = explore.AllActivities.contains(term, ignoreCase = true)

    SearchSelectionSheet(
        visible = visible,
        title = strings.WorkList.SortAndFilter,
        placeholder = strings.Common.Search,
        queryKey = query,
        onQueryChange = { query = it },
        onDismiss = onDismiss,
        onConfirm = {
            onApply(draft)
            onDismiss()
        },
    ) { _ ->
        if (sorts.isNotEmpty()) {
            item {
                ListSection(
                    title = strings.WorkList.Sort,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    sorts.forEach { (sort, label) ->
                        FilterChoiceRow(title = label, checked = sort == draft.sort) {
                            draft = draft.copy(sort = sort)
                        }
                    }
                }
            }
        }
        if (showAll || activities.isNotEmpty()) {
            item {
                ListSection(
                    title = explore.ActivityType,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    if (showAll) {
                        FilterChoiceRow(
                            title = explore.AllActivities,
                            checked = draft.filters.size == FeedFilter.entries.size,
                        ) {
                            draft = draft.copy(filters = FeedFilter.entries.toSet())
                        }
                    }
                    activities.forEach { (filter, label) ->
                        FilterChoiceRow(title = label, checked = filter in draft.filters) {
                            val filters = draft.filters
                            val updated =
                                if (filter in filters) filters - filter else filters + filter
                            draft = draft.copy(filters = updated)
                        }
                    }
                }
            }
        }
        if (sorts.isEmpty() && activities.isEmpty() && !showAll) {
            item {
                GlasmioGroupedList(Modifier.padding(bottom = 12.dp)) {
                    GlasmioListRow(
                        title = strings.Notifications.NoMatchingFilters,
                        showDisclosure = false,
                    )
                }
            }
        }
    }
}

private fun FeedSort.label(strings: ExploreStrings): String =
    when (this) {
        FeedSort.Newest -> strings.NewestFirst
        FeedSort.Oldest -> strings.OldestFirst
    }

private fun FeedFilter.label(strings: ExploreStrings): String =
    when (this) {
        FeedFilter.Announcements -> strings.Announcements
        FeedFilter.Releases -> strings.ReleaseTitle
        FeedFilter.Stars -> strings.Stars
        FeedFilter.Repositories -> strings.Repositories
        FeedFilter.Follows -> strings.Follows
        FeedFilter.Suggestions -> strings.Suggestions
    }
