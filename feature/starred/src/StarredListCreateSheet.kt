package moe.yumeyuka.yumehub.feature.starred

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.MutationAcknowledgementDialog

@Composable
internal fun StarredListCreateSheet(
    visible: Boolean,
    name: String,
    ready: Boolean,
    creating: Boolean,
    failed: Boolean,
    uncertain: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onAcknowledgeUncertain: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val profile = strings.Profile
    val common = strings.Common
    var acknowledgeVisible by remember { mutableStateOf(false) }
    AppBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        title = profile.NewList,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = profile.NewList,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        AppIcon(Lucide.X, contentDescription = common.Cancel)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = onCreate,
                        enabled = ready && name.isNotBlank() && !creating && !uncertain,
                    ) {
                        AppIcon(Lucide.Check, contentDescription = profile.NewList)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListSection(profile.ListNameLabel) {
                GlasmioTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = profile.ListNamePlaceholder,
                    enabled = ready && !creating && !uncertain,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (creating) {
                GlasmioGroupedList { GlasmioListRow(title = common.MutationPending) }
            } else if (uncertain) {
                GlasmioGroupedList {
                    GlasmioListRow(title = common.MutationUncertain)
                    GlasmioListRow(
                        title = common.MutationAcknowledged,
                        showTopSeparator = true,
                        showDisclosure = false,
                        onClick = { acknowledgeVisible = true },
                    )
                }
            } else if (failed) {
                GlasmioGroupedList { GlasmioListRow(title = profile.ListCreateFailed) }
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
