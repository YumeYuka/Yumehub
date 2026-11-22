package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.storage.mmkv.AccountAvatar
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import org.koin.androidx.compose.koinViewModel

data object ProfileRoute : GithubRoute

@Composable
fun ProfilePage(
    accounts: List<AccountAvatar>,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenRepositories: (String, Boolean) -> Unit,
    onOpenStarred: (String) -> Unit,
    onOpenOrganizations: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val viewModel: ProfileViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val statusEditor by viewModel.statusEditor.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Profile,
        DataDomain.Relationships,
        enabled = statusEditor == null,
    ) {
        viewModel.refresh()
    }
    if (
        state == ProfileUiState.Error || (state as? ProfileUiState.Content)?.refreshFailed == true
    ) {
        GithubLoadFailureToast(Unit, strings.Profile.LoadFailed)
    }
    GithubPage(
        title = strings.Profile.Title,
        showTitle = false,
        back = true,
        horizontalPadding = 12.dp,
        itemSpacing = 12.dp,
        locked = statusEditor?.submitting == true,
        actions = { ProfileAccountMenu(accounts, onAddAccount, onSwitchAccount) },
        backgroundColor = GlasmioTheme.colors.groupedBackground,
    ) {
        ProfileAccountContent(
            state = state,
            strings = strings.Profile,
            onEditStatus = viewModel::openStatusEditor,
            onOpenSettings = onOpenSettings,
            onOpenFollowing = onOpenFollowing,
            onOpenRepositories = onOpenRepositories,
            onOpenStarred = onOpenStarred,
            onOpenOrganizations = onOpenOrganizations,
        )
    }
    ProfileStatusSheet(
        state = statusEditor,
        strings = strings.Profile,
        common = strings.Common,
        onDismiss = viewModel::dismissStatusEditor,
        onEmojiChange = viewModel::setStatusEmoji,
        onMessageChange = viewModel::setStatusMessage,
        onLimitedAvailabilityChange = viewModel::setStatusLimitedAvailability,
        onExpiryChange = viewModel::setStatusExpiry,
        onOrganizationChange = viewModel::setStatusOrganization,
        onSave = viewModel::saveStatus,
        onClear = viewModel::clearStatus,
    )
}
