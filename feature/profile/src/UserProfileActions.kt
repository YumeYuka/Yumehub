package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import androidx.compose.foundation.layout.Box
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Flag
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.UserPlus
import moe.yumeyuka.yumehub.locale.GithubStrings_Profile
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost

@Composable
internal fun UserProfileActions(
    visible: Boolean,
    state: UserProfileState,
    strings: GithubStrings_Profile,
    onToggleFollowing: () -> Unit,
    onReport: () -> Unit,
) {
    var expanded by remember(visible) { mutableStateOf(false) }
    val host = LocalGithubPageHost.current
    val anchor = rememberGlasmioMenuAnchorState()
    Box {
    GithubToolbarActions(
        if (visible) toolbarIcon(Lucide.UserPlus, strings.UserActions, menuAnchor = anchor) {
            if (host?.navigationEnabled == true) expanded = true
        } else null,
    )
    if (visible) GlasmioMenu(
        expanded = expanded && host?.navigationEnabled == true,
        onExpandedChange = { if (!it || host?.navigationEnabled == true) expanded = it },
        itemCount = 2,
        forceBelowAnchor = true,
        menuAnchorState = anchor,
        externalAnchorBounds = anchor.bounds,
        anchor = {},
    ) { backdrop, close ->
        GlasmioMenuItem(
            title = strings.FollowUser,
            modifier =
                Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState =
                        if (state.following == true) ToggleableState.On else ToggleableState.Off
                },
            icon = { GithubIcon(Lucide.UserPlus, contentDescription = null, size = 20.dp) },
            trailing = {
                if (state.following == true) {
                    GithubIcon(Lucide.Check, contentDescription = null, size = 20.dp)
                }
            },
            enabled =
                state.following != null &&
                    state.profile?.nodeId != null &&
                    !state.unavailable &&
                    !state.followSubmitting,
            backdrop = backdrop,
            onClick = {
                expanded = false
                close()
                if (host?.navigationEnabled == true) onToggleFollowing()
            },
        )
        GlasmioMenuItem(
            title = strings.ReportUser,
            destructive = true,
            icon = {
                GithubIcon(
                    Lucide.Flag,
                    contentDescription = null,
                    size = 20.dp,
                    tint = GlasmioTheme.colors.destructive,
                )
            },
            backdrop = backdrop,
            onClick = {
                expanded = false
                close()
                if (host?.navigationEnabled == true) onReport()
            },
        )
    }
    }
}
