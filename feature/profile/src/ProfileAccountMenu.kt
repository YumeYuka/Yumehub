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
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar
import moe.yumeyuka.yumehub.data.storage.mmkv.MaxSavedAccounts
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost

@Composable
internal fun ProfileAccountMenu(
    accounts: List<AccountAvatar>,
    onAdd: () -> Unit,
    onSwitch: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.Profile
    val enabled = LocalAppPageHost.current?.navigationEnabled == true
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlasmioMenuAnchorState()
    val canAdd = accounts.size < MaxSavedAccounts
    ToolbarActions(
        toolbarIcon(
            Lucide.Users,
            strings.SwitchAccount,
            enabled = enabled,
            key = "accounts",
            menuAnchor = anchor,
        ) {
            if (enabled) expanded = true
        },
    )
    GlasmioMenu(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it && enabled },
        itemCount = accounts.size + if (canAdd) 1 else 0,
        forceBelowAnchor = true,
        menuAnchorState = anchor,
        externalAnchorBounds = anchor.bounds,
        anchor = {},
    ) { backdrop, close ->
        accounts.forEachIndexed { index, account ->
            GlasmioMenuItem(
                title = account.login,
                modifier = Modifier.semantics { selected = index == 0 },
                icon = { UserAvatar(account.avatarUrl, login = null, size = 24.dp) },
                trailing = {
                    if (index == 0)
                        AppIcon(Lucide.Check, contentDescription = null, size = 20.dp)
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
                icon = { AppIcon(Lucide.UserPlus, contentDescription = null, size = 20.dp) },
                backdrop = backdrop,
                onClick = {
                    close()
                    onAdd()
                },
            )
        }
    }
}
