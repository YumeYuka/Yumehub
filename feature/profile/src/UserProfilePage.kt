package moe.yumeyuka.yumehub.feature.profile

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class UserProfileRoute(
    val login: String,
    val avatarUrl: String? = null,
    val name: String? = null,
    val nodeId: String? = null,
    val instance: String = java.util.UUID.randomUUID().toString(),
    val organization: Boolean = false,
) : GithubRoute

@Composable
fun UserProfilePage(
    login: String,
    avatarUrl: String? = null,
    name: String? = null,
    nodeId: String? = null,
    onOpenFollowing: (String) -> Unit,
    onOpenRepositories: (String, Boolean) -> Unit,
    onOpenStarred: (String) -> Unit,
    onOpenOrganizations: (String) -> Unit,
    organization: Boolean = false,
) {
    val strings = LocalGithubStrings.current
    val viewModel: UserProfileViewModel =
        koinViewModel(parameters = { parametersOf(login, nodeId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Profile,
        DataDomain.Relationships,
        refreshOnFirstActivation = false,
        enabled = !state.followSubmitting,
        onRefresh = viewModel::load,
    )
    val targetLogin = state.profile?.login ?: login
    val openExternal = rememberExternalUriHandler()
    val context = LocalContext.current
    LaunchedEffect(state.followFailed) {
        if (state.followFailed) {
            Toast.makeText(context, strings.Profile.FollowFailed, Toast.LENGTH_SHORT).show()
            viewModel.dismissFollowError()
        }
    }
    GithubPage(
        title = targetLogin.ifBlank { strings.Profile.ProfileLink },
        showTitle = false,
        itemSpacing = 12.dp,
        actions = {
            UserProfileActions(
                visible = !viewModel.isSelf && !state.unavailable && targetLogin.isNotBlank(),
                state = state,
                strings = strings.Profile,
                onToggleFollowing = viewModel::toggleFollowing,
                onReport = {
                    openExternal(
                        Uri.parse("https://github.com/contact/report-abuse")
                            .buildUpon()
                            .appendQueryParameter("report", targetLogin)
                            .toString(),
                    )
                },
            )
        },
    ) {
        val current = state.profile
        if (state.failed || state.unavailable) {
            item(key = "profile-error") {
                ProfileRetryRow(strings.Profile.LoadFailed, viewModel::load)
            }
        }
        if (current != null || !state.failed && !state.unavailable) {
            item(key = "profile-header") {
                ProfileHeader(
                    current,
                    strings.Profile,
                    {},
                    { current?.login?.let(onOpenFollowing) },
                    showEditStatus = false,
                    fallbackAvatarUrl = avatarUrl,
                    fallbackName = name ?: login.takeIf { it.isNotBlank() },
                    fallbackOrganization = organization,
                )
            }
            item(key = "profile-details") { ProfileDetailsSection(current, strings.Profile) }
            item(key = "profile-collections") {
                ProfileCollectionsCard(
                    current,
                    strings.Profile,
                    onOpenRepositories,
                    onOpenStarred,
                    onOpenOrganizations,
                )
            }
            readingItem(key = "profile-readme") { ProfileReadmeContent(current) }
        }
    }
}
