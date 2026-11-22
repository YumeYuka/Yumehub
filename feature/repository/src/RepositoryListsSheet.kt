package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubFilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.GithubIcon

@Composable
internal fun RepositoryListsSheet(
    state: RepositoryListsState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val profile = strings.Profile
    GlasmioBottomSheet(
        visible = state.visible,
        onDismissRequest = onDismiss,
        title = profile.AddToList,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = profile.AddToList,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !state.saving) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Cancel)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = onSave,
                        enabled = !state.loading && !state.saving && !state.failed,
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = strings.Common.Save)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(LocalGlasmioSheetContentPadding.current)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            when {
                state.loading -> GlasmioGroupedList { GlasmioListRow(title = profile.LoadingLists) }
                state.failed ->
                    GlasmioGroupedList {
                        GlasmioListRow(title = profile.ListLoadFailed, onClick = onRetry)
                    }
                state.lists.isEmpty() ->
                    GlasmioGroupedList { GlasmioListRow(title = profile.EmptyLists) }
                else ->
                    GlasmioGroupedList {
                        state.lists.forEachIndexed { index, list ->
                            GithubFilterChoiceRow(
                                title = list.name,
                                checked = list.id in state.selectedIds,
                                enabled = !state.saving,
                                showTopSeparator = index > 0,
                                onClick = { onToggle(list.id) },
                            )
                        }
                    }
            }
        }
    }
}
