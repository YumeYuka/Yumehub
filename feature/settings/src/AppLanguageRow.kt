package moe.yumeyuka.yumehub.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Globe
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.AppLanguage
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.platform.AppLanguageManager

@Composable
internal fun AppLanguageRow() {
    val strings = LocalAppStrings.current.Settings
    val context = LocalContext.current
    var selected by remember(context) { mutableStateOf(AppLanguageManager.current(context)) }
    LifecycleResumeEffect(context) {
        selected = AppLanguageManager.current(context)
        onPauseOrDispose {}
    }
    GlasmioListRow(
        title = strings.AppLanguage,
        leading = { AppIcon(Lucide.Globe, contentDescription = null) },
        trailing = {
            SettingsPopUpButton(
                selected = selected,
                items = AppLanguage.entries,
                onSelected = { language ->
                    AppLanguageManager.apply(context, language)
                    selected = language
                },
                label = {
                    when (it) {
                        AppLanguage.System -> strings.System
                        AppLanguage.Chinese -> strings.Chinese
                        AppLanguage.English -> strings.English
                    }
                },
            )
        },
    )
}
