package moe.yumeyuka.yumehub.feature.discovery

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import moe.yumeyuka.yumehub.data.repository.TrendingPeriod
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

private enum class LanguageGroup { Programming, Spoken }

@Composable
internal fun DiscoveryFiltersSheet(
    visible: Boolean,
    trending: Boolean,
    filters: DiscoveryFilters,
    languages: LanguageState?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (DiscoveryFilters) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val explore = strings.Explore
    val repos = strings.MyRepositories
    var draft by remember(visible) { mutableStateOf(filters) }
    var expanded by remember(visible) { mutableStateOf<LanguageGroup?>(null) }
    var term by remember(visible) { mutableStateOf("") }
    val needle = term.trim()
    val options = (languages as? LanguageState.Ready)?.options
    val periods = listOf(
        TrendingPeriod.DAILY to explore.Today,
        TrendingPeriod.WEEKLY to explore.ThisWeek,
        TrendingPeriod.MONTHLY to explore.ThisMonth,
    ).filter { it.second.contains(needle, true) }
    val sorts = listOf(false to repos.Starred, true to repos.Updated)
        .filter { it.second.contains(needle, true) }
    val groups = if (trending) LanguageGroup.entries else listOf(LanguageGroup.Programming)
    if (visible && languages == LanguageState.Failed) {
        GithubLoadFailureToast(Unit, strings.Activity.LoadFailed)
    }
    GithubSearchSelectionSheet(
        visible = visible,
        title = repos.Filter,
        placeholder = strings.Common.Search,
        queryKey = term,
        onQueryChange = { term = it },
        onDismiss = onDismiss,
        onConfirm = { onApply(draft) },
    ) {
        if (trending && periods.isNotEmpty()) item {
            GithubSection(explore.Period) {
                periods.forEach { (value, title) ->
                    GithubFilterChoiceRow(title, draft.trending.period == value) {
                        draft = draft.copy(trending = draft.trending.copy(period = value))
                    }
                }
            }
        }
        if (!trending && sorts.isNotEmpty()) item {
            GithubSection(repos.Sort) {
                sorts.forEach { (value, title) ->
                    GithubFilterChoiceRow(title, draft.recentlyUpdated == value) {
                        draft = draft.copy(recentlyUpdated = value)
                    }
                }
            }
        }
        if (needle.isEmpty()) item {
            GlasmioGroupedList(Modifier.padding(top = 12.dp)) {
                groups.forEach { group ->
                    val spoken = group == LanguageGroup.Spoken
                    val selected = if (spoken) draft.trending.spokenLanguageCode else draft.trending.language
                    GlasmioListRow(
                        title = if (spoken) explore.SpokenLanguage else explore.ProgrammingLanguage,
                        detail = selected?.let { if (spoken) options?.spoken?.get(it) ?: it else it }
                            ?: repos.All,
                        showDisclosure = true,
                        onClick = { expanded = group.takeUnless { it == expanded } },
                    )
                }
            }
        }
        val visibleGroups = groups.filter { it == expanded || needle.isNotEmpty() }
        var hasMatches = if (trending) periods.isNotEmpty() else sorts.isNotEmpty()
        for (group in visibleGroups) {
            val spoken = group == LanguageGroup.Spoken
            val title = if (spoken) explore.SpokenLanguage else explore.ProgrammingLanguage
            val values = if (spoken) options?.spoken.orEmpty().toList().sortedBy { it.second }
                else options?.programming.orEmpty().map { it to it }
            val choices = (listOf("" to repos.All) + values).filter { (key, label) ->
                key.contains(needle, true) || label.contains(needle, true) || title.contains(needle, true)
            }
            if (choices.isEmpty()) continue
            hasMatches = true
            item(key = "${group.name}:header") {
                GithubSectionHeader(title, Modifier.padding(bottom = 8.dp))
            }
            itemsIndexed(choices, key = { _, choice -> "${group.name}:${choice.first}" }) { index, (key, label) ->
                val first = if (index == 0) 26.dp else 0.dp
                val last = if (index == choices.lastIndex) 26.dp else 0.dp
                GlasmioGroupedList(
                    framed = false,
                    modifier = Modifier.glasmioGroupedListBackground(
                        ContinuousRoundedRectangle(
                            topStart = first, topEnd = first, bottomStart = last, bottomEnd = last,
                        ),
                    ),
                ) {
                    val value = key.ifEmpty { null }
                    val current = if (spoken) draft.trending.spokenLanguageCode else draft.trending.language
                    GithubFilterChoiceRow(label, current == value, showTopSeparator = index > 0) {
                        draft = draft.copy(trending = if (spoken) draft.trending.copy(spokenLanguageCode = value)
                            else draft.trending.copy(language = value))
                    }
                }
            }
        }
        if (languages == LanguageState.Failed) item {
            GlasmioListRow(title = repos.RetryLanguages, onClick = onRetry)
        } else if (visibleGroups.isNotEmpty() && options == null) item {
            TitleRowPlaceholder()
        } else if (needle.isNotEmpty() && !hasMatches) item {
            GlasmioListRow(title = strings.Notifications.NoMatchingFilters, showDisclosure = false)
        }
    }
}
