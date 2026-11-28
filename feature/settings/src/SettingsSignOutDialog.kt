package moe.yumeyuka.yumehub.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.compositeOver
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.theme.readableForeground

@Composable
internal fun SettingsSignOutDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val colors = LocalGlassColors.current
    // 深色弹层按钮会叠加更亮的玻璃材质，按更亮的灰底留出对比度余量。
    val buttonTint = if (colors.isDark) 0.28f else 0.12f
    val buttonBackground =
        colors.content.copy(alpha = buttonTint).compositeOver(colors.elevatedBackground)
    val destructive = readableForeground(colors.destructive, buttonBackground, colors.content)
    CompositionLocalProvider(LocalGlassColors provides colors.copy(destructive = destructive)) {
        GlasmioAlertDialog(
            visible = visible,
            onDismissRequest = onDismiss,
            title = strings.Settings.ConfirmSignOut,
            message = strings.Settings.SignOutHint,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(strings.Common.Cancel, onDismiss, GlasmioAlertButtonRole.Cancel),
                    GlasmioAlertButtonSpec(strings.Settings.SignOut, onConfirm, GlasmioAlertButtonRole.Destructive),
                ),
        )
    }
}
