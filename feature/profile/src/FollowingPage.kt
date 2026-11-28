package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.PagingContent
import moe.yumeyuka.yumehub.ui.component.UserCard
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pagingItems
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class FollowingRoute(
    val login: String,
    val instance: String = java.util.UUID.randomUUID().toString(),
    val initialFollowing: Boolean = true,
) : AppRoute

@Composable
fun FollowingPage(
    login: String,
    onOpenUser: (login: String, avatarUrl: String?, name: String?, nodeId: String?) -> Unit,
    initialFollowing: Boolean = true,
) {
    val strings = LocalAppStrings.current
    val viewModel: FollowViewModel = koinViewModel(parameters = { parametersOf(login) })
    var following by rememberSaveable(login, initialFollowing) { mutableStateOf(initialFollowing) }
    PagingContent(
        source = if (following) viewModel.following else viewModel.followers,
        contentKey = "$login:$following",
    ) { paging ->
        val pages = paging.items
        val slots =
            rememberPagingSlots(
                pages,
                "follow-$following",
                initialLoading = paging.initialLoading,
            ) {
                it.id?.let { id -> "id:$id" } ?: it.login?.let { login -> "login:$login" }
            }
        val sectionTitle =
            if (following) strings.Profile.FollowingTab else strings.Profile.FollowersTab
        AppPage(
            title = strings.Profile.FollowingTitle,
            listState = paging.listState,
            itemSpacing = 12.dp,
            contentHeader = {
                GlasmioSegmentedControl(
                    items =
                        listOf(
                            true to strings.Profile.FollowingTab,
                            false to strings.Profile.FollowersTab,
                        ),
                    selected = following,
                    onSelected = { following = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 36.dp,
                )
            },
        ) {
            pagingItems(slots, contentType = "user") { user ->
                UserCard(
                    loading = user == null,
                    login = user?.login,
                    name = user?.name,
                    avatarUrl = user?.avatar,
                    bio = user?.bio,
                    onClick =
                        if (user != null && (user.login != null || user.id != null)) {
                            { onOpenUser(user.login.orEmpty(), user.avatar, user.name, user.id) }
                        } else null,
                )
            }
            collaborationPagingStatus(
                slots,
                sectionTitle,
                emptyMessage = strings.Profile.CollectionEmpty(sectionTitle),
            )
        }
    }
}
