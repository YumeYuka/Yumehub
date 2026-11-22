package moe.yumeyuka.yumehub.feature.starred

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
import io.github.yumeyucca.lucide.CirclePlus
import io.github.yumeyucca.lucide.ListCheck
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.repository.UserRepositoryList
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.GithubFilterChoiceRow

@Composable
internal fun StarredListSelectionSheet(
    visible: Boolean,
    lists: List<UserRepositoryList>,
    selectedListId: String?,
    loading: Boolean,
    failed: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelectList: (UserRepositoryList?) -> Unit,
    onCreateList: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val profile = strings.Profile
    var draftId by remember(visible) { mutableStateOf(selectedListId) }
    val filteredLists =
        lists.filter {
            it.name.contains(query.trim(), ignoreCase = true) ||
                it.description.orEmpty().contains(query.trim(), ignoreCase = true)
        }
    GithubSearchSelectionSheet(
        visible = visible,
        title = profile.Lists,
        placeholder = profile.SearchLists,
        queryKey = query,
        onQueryChange = onQueryChange,
        onDismiss = onDismiss,
        confirmEnabled = draftId == selectedListId || draftId == null || lists.any { it.id == draftId },
        onConfirm = {
            if (draftId != selectedListId) onSelectList(lists.find { it.id == draftId })
            onDismiss()
        },
    ) { finish ->
        item {
            GlasmioGroupedList {
                GithubFilterChoiceRow(
                    title = profile.Starred,
                    checked = draftId == null,
                    leading = {
                        GithubIcon(Lucide.ListCheck, contentDescription = null, size = 20.dp)
                    },
                    onClick = { draftId = null },
                )
                GlasmioListRow(
                    title = profile.NewList,
                    leading = {
                        GithubIcon(Lucide.CirclePlus, contentDescription = null, size = 20.dp)
                    },
                    showDisclosure = false,
                    showTopSeparator = false,
                    onClick = { finish(onCreateList) },
                )
            }
        }
        item {
            GlasmioGroupedList(modifier = Modifier.padding(top = 12.dp)) {
                val status = when {
                    lists.isEmpty() && loading -> profile.LoadingLists
                    lists.isEmpty() && failed -> profile.ListLoadFailed
                    lists.isEmpty() -> profile.EmptyLists
                    filteredLists.isEmpty() -> profile.NoMatchingLists
                    else -> null
                }
                if (status != null) {
                    GlasmioListRow(
                        title = status,
                        showDisclosure = false,
                        showTopSeparator = false,
                        onClick = if (lists.isEmpty() && failed && !loading) onRetry else null,
                    )
                }
                filteredLists.forEachIndexed { index, list ->
                    GithubFilterChoiceRow(
                        title = list.name,
                        checked = draftId == list.id,
                        showTopSeparator = index > 0,
                        onClick = { draftId = list.id },
                    )
                }
            }
        }
    }
}
