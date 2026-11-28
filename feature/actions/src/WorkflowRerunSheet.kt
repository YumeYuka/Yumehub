package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RotateCcw
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.MutationAcknowledgementDialog

@Composable
internal fun WorkflowRerunSheet(
    busy: Boolean,
    uncertain: Boolean,
    onClose: () -> Unit,
    onRerun: (debug: Boolean) -> Unit,
    onAcknowledgeUncertain: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val activity = strings.Activity
    var debug by remember { mutableStateOf(false) }
    var acknowledgeVisible by remember { mutableStateOf(false) }
    val sheet = rememberGlasmioSheetState(skipPartiallyExpanded = true)
    AppBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        fitContent = true,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        title = activity.RerunFailed,
        topBar = {
            GlasmioTopBar(
                title = activity.RerunFailed,
                navigation = {
                    GlasmioIconButton(onClick = onClose) {
                        AppIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = !busy && !uncertain,
                        onClick = { onRerun(debug) },
                    ) {
                        AppIcon(Lucide.RotateCcw, contentDescription = activity.Rerun)
                    }
                },
            )
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = activity.DebugLogging,
                    trailing = {
                        GlasmioSwitch(
                            checked = debug,
                            enabled = !busy && !uncertain,
                            onCheckedChange = { debug = it },
                        )
                    },
                )
            }
            if (busy) {
                GlasmioGroupedList { GlasmioListRow(title = strings.Common.MutationPending) }
            } else if (uncertain) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Common.MutationUncertain,
                        subtitle = activity.RunUncertain,
                    )
                    GlasmioListRow(
                        title = strings.Common.MutationAcknowledged,
                        showTopSeparator = true,
                        showDisclosure = false,
                        onClick = { acknowledgeVisible = true },
                    )
                }
            }
        }
    }
    if (acknowledgeVisible && uncertain) {
        MutationAcknowledgementDialog(
            onDismiss = { acknowledgeVisible = false },
            onAcknowledge = {
                acknowledgeVisible = false
                onAcknowledgeUncertain()
            },
        )
    }
}
