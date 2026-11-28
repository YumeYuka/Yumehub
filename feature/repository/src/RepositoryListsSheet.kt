package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.FilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.MutationRecoveryRow

@Composable
internal fun RepositoryListsSheet(
    state: RepositoryListsState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val profile = strings.Profile
    AppBottomSheet(
        visible = state.visible,
        onDismissRequest = onDismiss,
        title = profile.AddToList,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = profile.AddToList,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        AppIcon(Lucide.X, contentDescription = strings.Common.Cancel)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = onSave,
                        enabled =
                            !state.loading &&
                                !state.saving &&
                                !state.failed &&
                                state.phase != WritePhase.UNCERTAIN,
                    ) {
                        AppIcon(Lucide.Check, contentDescription = strings.Common.Save)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.phase == WritePhase.PENDING)
                GlasmioListRow(title = strings.Common.MutationPending)
            if (state.phase == WritePhase.UNCERTAIN)
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Common.MutationUncertain,
                        subtitle = strings.Common.MutationReconcile,
                        onClick = onRetry,
                    )
                    state.uncertainOperationId?.let {
                        MutationRecoveryRow(it, onAcknowledge = onAcknowledge)
                    }
                }
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
                            FilterChoiceRow(
                                title = list.name,
                                checked = list.id in state.selectedIds,
                                enabled = !state.saving && state.phase != WritePhase.UNCERTAIN,
                                showTopSeparator = index > 0,
                                onClick = { onToggle(list.id) },
                            )
                        }
                    }
            }
        }
    }
}
