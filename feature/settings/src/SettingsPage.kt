package moe.yumeyuka.yumehub.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import org.koin.androidx.compose.koinViewModel

/** 设置页面的路由键。 */
object SettingsRoute : GithubRoute

@Composable
fun SettingsPage(onSignOut: () -> Unit) {
    val viewModel: SettingsPageViewModel = koinViewModel()
    var showAccentPicker by rememberSaveable { mutableStateOf(false) }
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    val strings = LocalGithubStrings.current
    GithubPage(title = strings.Profile.Settings) {
        SettingsContent(viewModel, onShowAccentPicker = { showAccentPicker = true })
        item(key = "settings-account-actions") {
            GithubSection(strings.Settings.AccountActions) {
                GlasmioListRow(
                    title = strings.Settings.SignOut,
                    titleColor = GlasmioTheme.colors.destructive,
                    systemName = null,
                    onClick = { showSignOutDialog = true },
                )
            }
        }
    }
    SettingsDialogs(viewModel, showAccentPicker, onDismiss = { showAccentPicker = false })
    SettingsSignOutDialog(
        visible = showSignOutDialog,
        onDismiss = { showSignOutDialog = false },
        onConfirm = {
            if (showSignOutDialog) {
                showSignOutDialog = false
                onSignOut()
            }
        },
    )
}
