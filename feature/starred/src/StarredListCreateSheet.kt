package moe.yumeyuka.yumehub.feature.starred

import androidx.compose.foundation.layout.Arrangement
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
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
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
    creating: Boolean,
    failed: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val profile = strings.Profile
    val common = strings.Common
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (!creating) onDismiss() },
        dismissOnBackPress = !creating,
        dismissOnClickOutside = !creating,
        title = profile.NewList,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = profile.NewList,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !creating) {
                        GithubIcon(Lucide.X, contentDescription = common.Cancel)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = onCreate,
                        enabled = name.isNotBlank() && !creating,
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = profile.NewList)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(LocalGlasmioSheetContentPadding.current).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GithubSection(profile.ListNameLabel) {
                GlasmioTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = profile.ListNamePlaceholder,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (failed) {
                GlasmioGroupedList { GlasmioListRow(title = profile.ListCreateFailed) }
            }
        }
    }
}
