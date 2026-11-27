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
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import moe.yumeyuka.yumehub.ui.component.GithubBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection

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
    val strings = LocalGithubStrings.current
    val profile = strings.Profile
    val common = strings.Common
    var acknowledgeVisible by remember { mutableStateOf(false) }
    GithubBottomSheet(
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
                        GithubIcon(Lucide.X, contentDescription = common.Cancel)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = onCreate,
                        enabled = ready && name.isNotBlank() && !creating && !uncertain,
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = profile.NewList)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GithubSection(profile.ListNameLabel) {
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
        GlasmioAlertDialog(
            visible = true,
            title = common.MutationUncertain,
            message = common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        common.Cancel,
                        onClick = { acknowledgeVisible = false },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        common.MutationAcknowledged,
                        onClick = {
                            acknowledgeVisible = false
                            onAcknowledgeUncertain()
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeVisible = false },
        )
    }
}
