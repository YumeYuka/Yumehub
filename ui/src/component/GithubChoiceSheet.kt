package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

@Composable
fun GithubChoiceSheet(
    visible: Boolean,
    title: String,
    placeholder: String,
    choices: List<String>,
    query: String,
    selectedValue: String?,
    loading: Boolean,
    hasMore: Boolean,
    error: String?,
    empty: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    label: (String) -> String = { it },
) {
    LaunchedEffect(visible) { if (visible) onQueryChange("") }
    if (visible) GithubLoadFailureToast(error, error.orEmpty())
    val pendingMore = hasMore && error == null
    val allocator = remember(query) { GithubPagingSlotKeys("choice:$query") }
    val keys =
        remember(allocator, choices, loading, pendingMore) {
            allocator.update(choices, loading = pendingMore, initialLoading = loading)
        }
    GithubSearchSelectionSheet(
        visible = visible,
        title = title,
        placeholder = placeholder,
        queryKey = query,
        onQueryChange = onQueryChange,
        onDismiss = onDismiss,
        onConfirm = onDismiss,
        showConfirm = false,
    ) { finish ->
        when {
            error != null && choices.isEmpty() ->
                item {
                    GlasmioListRow(
                        title = LocalGithubStrings.current.Common.Retry,
                        onClick = onRetry,
                    )
                }
            !loading && choices.isEmpty() && !pendingMore ->
                item { GlasmioGroupedList { GlasmioListRow(title = empty) } }
            else ->
                itemsIndexed(keys, key = { _, key -> key }) { index, _ ->
                    val branch = choices.getOrNull(index)
                    if (branch == null && pendingMore && !loading) {
                        LaunchedEffect(visible, query, choices.size) { if (visible) onLoadMore() }
                    }
                    GlasmioGroupedList(
                        framed = false,
                        modifier =
                            Modifier.glasmioGroupedListBackground(
                                    ContinuousRoundedRectangle(
                                        topStart = if (index == 0) 26.dp else 0.dp,
                                        topEnd = if (index == 0) 26.dp else 0.dp,
                                        bottomStart = if (index == keys.lastIndex) 26.dp else 0.dp,
                                        bottomEnd = if (index == keys.lastIndex) 26.dp else 0.dp,
                                    ),
                                ),
                    ) {
                        GithubLoadingContent(
                            loading = branch == null,
                            deferUntilEntry = false,
                            placeholder = { TitleRowPlaceholder(showTopSeparator = index > 0) },
                        ) {
                            if (branch != null)
                                GlasmioListRow(
                                    title = label(branch),
                                    modifier =
                                        Modifier.semantics { selected = branch == selectedValue },
                                    showTopSeparator = index > 0,
                                    showDisclosure = false,
                                    trailing = {
                                        if (branch == selectedValue)
                                            GithubIcon(Lucide.Check, contentDescription = null)
                                    },
                                    onClick = { finish { onSelect(branch) } },
                                )
                        }
                    }
                }
        }
        if (error != null && choices.isNotEmpty())
            item {
                GlasmioListRow(title = LocalGithubStrings.current.Common.Retry, onClick = onRetry)
            }
    }
}
