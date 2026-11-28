package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import moe.yumeyuka.yumehub.locale.LocalAppStrings

@Composable
fun MutationAcknowledgementDialog(onDismiss: () -> Unit, onAcknowledge: () -> Unit) {
    val strings = LocalAppStrings.current.Common
    GlasmioAlertDialog(
        visible = true,
        title = strings.MutationUncertain,
        message = strings.MutationAcknowledgeWarning,
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    strings.Cancel,
                    onClick = onDismiss,
                    role = GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    strings.MutationAcknowledged,
                    onClick = onAcknowledge,
                    role = GlasmioAlertButtonRole.Default,
                ),
            ),
        onDismissRequest = onDismiss,
    )
}
