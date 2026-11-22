package moe.yumeyuka.yumehub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.RepositoryRow
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun HomePage(
    login: String,
    onOpenProfile: (() -> Unit)? = null,
    onOpenSearch: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenWork: (CollaborationKind) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenOrganizations: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenDiscussions: () -> Unit,
) {
    // 收藏模型跟随登录会话；AppRoot 的 key(login) 负责切号时重建，本地快照按账号隔离。
    val viewModel: HomeFavoritesViewModel =
        koinViewModel(
            key = "home-favorites-$login",
            parameters = { parametersOf(login) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GithubRefreshEffect(viewModel, DataDomain.Repositories, onRefresh = viewModel::refresh)
    val work: HomeWorkViewModel = koinViewModel()
    val workLayout by work.layout.collectAsStateWithLifecycle()
    var draftOrder by rememberSaveable(login) { mutableStateOf(workLayout.encode()) }
    val workDraft = HomeWorkLayout.decode(draftOrder, emptyList())
    val strings = LocalGithubStrings.current.Home
    val profileTitle = LocalGithubStrings.current.Profile.Title
    val retry = LocalGithubStrings.current.Common.Retry
    var editingWork by rememberSaveable(login) { mutableStateOf(false) }
    val onEditWork = {
        draftOrder = workLayout.encode()
        editingWork = true
    }
    if (
        state == HomeFavoritesState.Error ||
            (state as? HomeFavoritesState.Content)?.refreshFailed == true
    ) {
        GithubLoadFailureToast(Unit, strings.FavoritesLoadFailed)
    }
    GithubPage(
        title = strings.Title,
        back = false,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlasmioIconButton(onClick = onOpenSearch) {
                    GithubIcon(
                        Lucide.Search,
                        contentDescription = LocalGithubStrings.current.Common.Search,
                    )
                }
                GlasmioIconButton(onClick = onEditWork) {
                    GithubIcon(Lucide.Pencil, contentDescription = strings.EditHome)
                }
            }
        },
    ) {
        HomeScreen(
            items = workLayout.shown,
            strings = strings,
            onEdit = onEditWork,
            onOpenRepositories = onOpenRepositories,
            onOpenWork = onOpenWork,
            onOpenStarred = onOpenStarred,
            onOpenOrganizations = onOpenOrganizations,
            onOpenProjects = onOpenProjects,
            onOpenDiscussions = onOpenDiscussions,
        )
        val favorites = state as? HomeFavoritesState.Content
        if (onOpenProfile != null) {
            item(key = "home-profile") {
                GlasmioGroupedList { GlasmioListRow(title = profileTitle, onClick = onOpenProfile) }
            }
        }
        item(key = "home-favorites") {
            GithubSection(strings.Favorites) {
                GithubLoadingContent(
                    loading = state == HomeFavoritesState.Loading,
                    placeholder = { ListRowPlaceholder() },
                ) {
                    if (favorites == null) {
                        GlasmioListRow(title = retry, onClick = viewModel::retry)
                    } else if (favorites.repositories.isEmpty()) {
                        GlasmioListRow(
                            title = strings.NoFavorites,
                            subtitle = strings.FavoritesHint,
                            systemName = null,
                        )
                    } else
                        Column {
                            favorites.repositories.forEachIndexed { index, repository ->
                                androidx.compose.runtime.key(repository.fullName) {
                                    RepositoryRow(
                                        repository = repository,
                                        onClick = { onOpenRepository(repository) },
                                        compact = true,
                                        showTopSeparator = index > 0,
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
    HomeWorkSheet(
        visible = editingWork,
        draft = workDraft,
        favorites = viewModel,
        onDismiss = { editingWork = false },
        onChange = { draftOrder = it.encode() },
        onConfirm = {
            work.save(workDraft)
            editingWork = false
        },
    )
}
