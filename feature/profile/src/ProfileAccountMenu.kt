package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar
import moe.yumeyuka.yumehub.data.storage.mmkv.MaxSavedAccounts
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost

@Composable
internal fun ProfileAccountMenu(
    accounts: List<AccountAvatar>,
    onAdd: () -> Unit,
    onSwitch: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current.Profile
    val enabled = LocalGithubPageHost.current?.navigationEnabled == true
    var expanded by remember { mutableStateOf(false) }
    val canAdd = accounts.size < MaxSavedAccounts
    GlasmioMenu(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it && enabled },
        itemCount = accounts.size + if (canAdd) 1 else 0,
        forceBelowAnchor = true,
        anchor = { open ->
            GlasmioIconButton(onClick = open, enabled = enabled) {
                GithubIcon(Lucide.Users, contentDescription = strings.SwitchAccount)
            }
        },
    ) { backdrop, close ->
        accounts.forEachIndexed { index, account ->
            GlasmioMenuItem(
                title = account.login,
                modifier = Modifier.semantics { selected = index == 0 },
                icon = { GithubAvatar(account.avatarUrl, login = null, size = 24.dp) },
                trailing = {
                    if (index == 0)
                        GithubIcon(Lucide.Check, contentDescription = null, size = 20.dp)
                },
                backdrop = backdrop,
                onClick = {
                    close()
                    if (index != 0) onSwitch(account.login)
                },
            )
        }
        if (canAdd) {
            GlasmioMenuItem(
                title = strings.AddAccount,
                icon = { GithubIcon(Lucide.UserPlus, contentDescription = null, size = 20.dp) },
                backdrop = backdrop,
                onClick = {
                    close()
                    onAdd()
                },
            )
        }
    }
}
