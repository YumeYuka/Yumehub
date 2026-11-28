package moe.yumeyuka.yumehub.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioColorPicker
import io.github.neoruaa.glasmio.ui.GlasmioColorPickerLabels
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.storage.mmkv.DefaultAccentColorArgb
import moe.yumeyuka.yumehub.data.storage.mmkv.MaxPredictiveBackProgress
import moe.yumeyuka.yumehub.data.storage.mmkv.MinPredictiveBackProgress
import moe.yumeyuka.yumehub.data.storage.mmkv.ThemeMode
import moe.yumeyuka.yumehub.locale.GithubStrings_Settings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.theme.readableForeground

internal fun LazyListScope.SettingsContent(
    viewModel: SettingsPageViewModel,
    onShowAccentPicker: () -> Unit,
) {
    item(key = "settings-appearance") { AppearanceSection(viewModel) }
    item(key = "settings-accent") { AccentSection(viewModel, onShowAccentPicker) }
    item(key = "settings-downloads") { DownloadDirectorySection(viewModel) }
    item(key = "settings-interface") { InterfaceSection(viewModel) }
    item(key = "settings-navigation") { NavigationSection(viewModel) }
    item(key = "settings-link-handling") { LinkHandlingSection() }
    item(key = "settings-diagnostics") { DiagnosticsSection(viewModel) }
}

@Composable
private fun AppearanceSection(viewModel: SettingsPageViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.Settings
    GithubSection(strings.Appearance) {
        AppLanguageRow()
        GlasmioListRow(
            title = strings.Theme,
            leading = { GithubIcon(themeMode.icon(), contentDescription = null) },
            trailing = {
                SettingsPopUpButton(
                    selected = themeMode,
                    items = ThemeMode.entries,
                    onSelected = viewModel::setThemeMode,
                    label = { it.label(strings) },
                )
            },
        )
    }
}

private fun ThemeMode.label(strings: GithubStrings_Settings): String =
    when (this) {
        ThemeMode.System -> strings.System
        ThemeMode.Light -> strings.Light
        ThemeMode.Dark -> strings.Dark
    }

private fun ThemeMode.icon(): ImageVector =
    when (this) {
        ThemeMode.System -> Lucide.Contrast
        ThemeMode.Light -> Lucide.Sun
        ThemeMode.Dark -> Lucide.Moon
    }

@Composable
private fun AccentSection(viewModel: SettingsPageViewModel, onShowPicker: () -> Unit) {
    val accentArgb by viewModel.accentColor.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.Settings

    GithubSection(strings.Accent) {
        GlasmioListRow(
            title = strings.Accent,
            leading = { GithubIcon(Lucide.Palette, contentDescription = null) },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(22.dp)
                            .background(Color(accentArgb), CircleShape)
                            .border(1.dp, GlasmioTheme.colors.separator, CircleShape)
                            .semantics { contentDescription = strings.CurrentAccent },
                    )
                    SettingsPopUpButton(
                        selected = accentArgb != DefaultAccentColorArgb,
                        items = listOf(false, true),
                        label = { if (it) strings.Custom else strings.Default },
                        onSelected = { custom ->
                            if (custom) onShowPicker()
                            else viewModel.setAccentColor(DefaultAccentColorArgb)
                        },
                    )
                }
            },
            onClick = onShowPicker,
        )
    }
}

@Composable
internal fun <T> SettingsPopUpButton(
    selected: T,
    items: List<T>,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
) {
    val colors = GlasmioTheme.colors
    val accent = readableForeground(colors.accent, colors.elevatedBackground, colors.content)
    CompositionLocalProvider(LocalGlassColors provides colors.copy(accent = accent)) {
        GlasmioPopUpButton(
            selected = selected,
            items = items,
            onSelected = onSelected,
            label = label,
            enabled = enabled,
        )
    }
}

/** 始终在列表之外挂载，关闭时保留取色器退场动画。 */
@Composable
internal fun SettingsDialogs(
    viewModel: SettingsPageViewModel,
    showAccentPicker: Boolean,
    onDismiss: () -> Unit,
) {
    val accentArgb by viewModel.accentColor.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.Settings
    GlasmioColorPicker(
        visible = showAccentPicker,
        selectedColor = Color(accentArgb),
        onColorSelected = { viewModel.setAccentColor(it.toArgb()) },
        onDismiss = onDismiss,
        title = strings.Accent,
        labels =
            GlasmioColorPickerLabels(
                grid = strings.Grid,
                spectrum = strings.Spectrum,
                sliders = strings.Sliders,
                opacity = strings.Opacity,
            ),
    )
}

@Composable
private fun InterfaceSection(viewModel: SettingsPageViewModel) {
    val liquidGlassEnabled by viewModel.liquidGlassEnabled.collectAsStateWithLifecycle()
    val reduceTransparency by viewModel.reduceTransparency.collectAsStateWithLifecycle()
    val topBarBlurEnabled by viewModel.topBarBlurEnabled.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.Settings
    val keepBottomBarExpanded by viewModel.keepBottomBarExpanded.collectAsStateWithLifecycle()
    val glassHdrEnabled by viewModel.glassHdrEnabled.collectAsStateWithLifecycle()
    val accent = GlasmioTheme.colors.accent

    GithubSection(strings.Interface) {
        ToggleRow(
            title = strings.PinBottomBar,
            subtitle = strings.PinBottomBarHint,
            checked = keepBottomBarExpanded,
            onCheckedChange = viewModel::setKeepBottomBarExpanded,
            tint = accent,
            icon = Lucide.PanelBottom,
        )
        ToggleRow(
            title = strings.LiquidGlass,
            icon = Lucide.Droplets,
            subtitle = strings.LiquidGlassHint,
            checked = liquidGlassEnabled,
            onCheckedChange = viewModel::setLiquidGlassEnabled,
            tint = accent,
            enabled = !reduceTransparency,
        )
        ToggleRow(
            title = strings.Hdr,
            subtitle = strings.HdrHint,
            checked = glassHdrEnabled,
            onCheckedChange = viewModel::setGlassHdrEnabled,
            enabled = liquidGlassEnabled && !reduceTransparency,
            tint = accent,
            icon = Lucide.Sun,
        )
        ToggleRow(
            title = strings.ReduceTransparency,
            icon = Lucide.Contrast,
            subtitle = strings.ReduceTransparencyHint,
            checked = reduceTransparency,
            onCheckedChange = viewModel::setReduceTransparency,
            tint = accent,
        )
        ToggleRow(
            title = strings.TopBarBlur,
            subtitle =
                if (reduceTransparency) strings.TopBarBlurReduceTransparencyOn
                else strings.TopBarBlurDefault,
            icon = Lucide.PanelTop,
            checked = topBarBlurEnabled,
            onCheckedChange = viewModel::setTopBarBlurEnabled,
            enabled = !reduceTransparency,
            tint = accent,
        )
    }
}

@Composable
private fun NavigationSection(viewModel: SettingsPageViewModel) {
    val predictiveBackEnabled by viewModel.predictiveBackEnabled.collectAsStateWithLifecycle()
    val progress by viewModel.predictiveBackMaxProgress.collectAsStateWithLifecycle()
    val accent = GlasmioTheme.colors.accent
    val strings = LocalGithubStrings.current.Settings

    GithubSection(strings.Navigation) {
        ToggleRow(
            title = strings.PredictiveBack,
            subtitle = strings.NextLaunch,
            checked = predictiveBackEnabled,
            onCheckedChange = viewModel::setPredictiveBackEnabled,
            tint = accent,
        )
        PredictiveBackProgressRow(
            progress = progress,
            enabled = predictiveBackEnabled,
            onProgressChange = viewModel::setPredictiveBackMaxProgress,
        )
    }
}

@Composable
private fun DiagnosticsSection(viewModel: SettingsPageViewModel) {
    val fpsCounter by viewModel.fpsCounter.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current.Settings

    GithubSection(strings.Diagnostics) {
        ToggleRow(
            title = strings.Fps,
            subtitle = strings.FpsHint,
            checked = fpsCounter,
            onCheckedChange = viewModel::setFpsCounter,
            tint = GlasmioTheme.colors.accent,
            icon = Lucide.Activity,
        )
    }
}

@Composable
private fun PredictiveBackProgressRow(
    progress: Float,
    enabled: Boolean,
    onProgressChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LocalGithubStrings.current.Settings.BackProgress,
                style = GlasmioTheme.textStyles.body,
                color = GlasmioTheme.colors.content,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${progress.toInt()}%",
                style = GlasmioTheme.textStyles.body,
                color = GlasmioTheme.colors.secondaryContent,
            )
        }
        GlasmioSlider(
            value = progress,
            onValueChange = onProgressChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            valueRange = MinPredictiveBackProgress..MaxPredictiveBackProgress,
            enabled = enabled,
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tint: Color,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        leading =
            if (icon != null) {
                { GithubIcon(icon, contentDescription = title) }
            } else {
                null
            },
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            GlasmioSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                onTintColor = tint,
            )
        },
    )
}
