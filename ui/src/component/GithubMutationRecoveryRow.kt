package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

@Composable
fun GithubMutationRecoveryRow(operationId: String, subtitle: String? = null, onAcknowledge: (String) -> Unit) {
    val strings = LocalGithubStrings.current.Common
    var confirming by remember(operationId) { mutableStateOf(false) }
    GlasmioListRow(title = strings.MutationAcknowledged, subtitle = subtitle, onClick = { confirming = true })
    if (confirming) {
        GlasmioAlertDialog(
            visible = true,
            title = strings.MutationUncertain,
            message = strings.MutationAcknowledgeWarning,
            buttons = listOf(
                GlasmioAlertButtonSpec(strings.Cancel, { confirming = false }, GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(strings.MutationAcknowledged, { confirming = false; onAcknowledge(operationId) }, GlasmioAlertButtonRole.Default),
            ),
            onDismissRequest = { confirming = false },
        )
    }
}
