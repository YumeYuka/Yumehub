package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.runtime.Composable
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Settings
import moe.yumeyuka.yumehub.ui.component.AppIcon

@Composable
internal fun ProfileSettingsAction(contentDescription: String, onOpenSettings: () -> Unit) {
    GlasmioIconButton(onClick = onOpenSettings) {
        AppIcon(Lucide.Settings, contentDescription = contentDescription)
    }
}
