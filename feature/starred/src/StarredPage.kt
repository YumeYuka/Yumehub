package moe.yumeyuka.yumehub.feature.starred

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.GithubPageSearchLayout
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 已加星标页面的路由键。 */
data object StarredRoute : GithubRoute

@Composable
fun StarredPage(login: String, onOpenRepository: (RepositoryDto) -> Unit) {
    val strings = LocalGithubStrings.current
    val viewModel: StarredRepositoriesViewModel =
        koinViewModel(parameters = { parametersOf(login) })
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val listsLoading by viewModel.listsLoading.collectAsStateWithLifecycle()
    val listsFailed by viewModel.listsFailed.collectAsStateWithLifecycle()
    val creation by viewModel.creation.collectAsStateWithLifecycle()
    val selectedList by viewModel.selectedList.collectAsStateWithLifecycle()
    val search by viewModel.searchQuery.collectAsStateWithLifecycle()
    val changes = key(viewModel, selectedList?.id) {
        remember { viewModel.membershipFor(selectedList) }.collectAsStateWithLifecycle(emptyMap()).value
    }
    var searchVisible by remember(login) { mutableStateOf(false) }
    var searchInput by
        rememberSaveable(login, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(search))
        }
    var listSearch by rememberSaveable(login) { mutableStateOf("") }
    var listsExpanded by rememberSaveable(login) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissSearch = {
        searchVisible = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(searchInput.text) {
        delay(200)
        viewModel.search(searchInput.text)
    }
    GithubBackHandler(
        enabled = searchVisible && !listsExpanded && !creation.visible,
        onBack = dismissSearch,
    )
    GithubPageSearchLayout(
        searchVisible, searchInput, { searchInput = it }, viewModel::search,
        dismissSearch, strings.Profile.SearchStarred,
    ) {
        GithubPagingContent(
            source = remember(viewModel, selectedList, search) { viewModel.repositoriesFor(search, selectedList) },
            contentKey = Triple(viewModel.account to login, selectedList?.id to selectedList?.slug, search),
            revalidation = remember(viewModel, selectedList, search) { viewModel.revalidationFor(search, selectedList) },
            onRetryRevalidation = { viewModel.retryRevalidation(search, selectedList) },
        ) { paging ->
            val repositories = paging.items
            val slots =
                rememberGithubPagingSlots(repositories, "starred", paging.initialLoading) {
                    it.id?.toString() ?: it.fullName
                }
            val loaded = slots.snapshot
            val additions = remember(changes, loaded, search, selectedList) {
                if (selectedList == null && search.isNotBlank() || changes.isEmpty()) emptyList() else {
                    val present = loaded.mapNotNullTo(hashSetOf()) { it?.fullName?.lowercase() }
                    changes.mapNotNull { (slug, change) -> change.repository?.takeIf { repo ->
                        change.member == true && slug !in present && change.phase != moe.yumeyuka.yumehub.data.cache.WritePhase.REJECTED &&
                            (search.isBlank() || repo.fullName.orEmpty().contains(search.trim(), true) || repo.description.orEmpty().contains(search.trim(), true))
                    } }
                }
            }
        GithubPage(
            title = selectedList?.name ?: strings.Profile.Starred,
            listState = paging.listState,
            itemSpacing = 12.dp,
            actions = {
                GlasmioIconButton(
                    onClick = { searchVisible = true },
                ) {
                    GithubIcon(
                        Lucide.Search,
                        contentDescription = strings.Profile.SearchStarred,
                    )
                }
                GlasmioIconButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                        listSearch = ""
                        listsExpanded = true
                    },
                ) {
                    GithubIcon(Lucide.ListFilter, contentDescription = strings.Profile.Lists)
                }
            },
        ) {
            additions.forEach { repo -> item(key = "star-change:${repo.fullName}") {
                moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard(repo, onClick = { onOpenRepository(repo) }, showCover = false)
            } }
            StarredRepositoriesScreen(
                slots,
                onOpenRepository,
                hasAdditions = additions.isNotEmpty(),
                emptyText =
                    if (search.isBlank())
                        strings.Profile.CollectionEmpty(
                            selectedList?.name ?: strings.Profile.Starred,
                        )
                    else strings.Profile.NoMatchingStarred,
            )
            pageSearchClearance(searchVisible)
        }
    }
    }
    StarredListSelectionSheet(
        visible = listsExpanded,
        lists = lists,
        selectedListId = selectedList?.id,
        loading = listsLoading,
        failed = listsFailed,
        query = listSearch,
        onQueryChange = { listSearch = it },
        onDismiss = {
            listsExpanded = false
            listSearch = ""
        },
        onRetry = viewModel::refreshLists,
        onSelectList = viewModel::selectList,
        onCreateList = {
            listsExpanded = false
            listSearch = ""
            viewModel.showCreation(true)
        },
    )
    StarredListCreateSheet(
        visible = creation.visible,
        name = creation.name,
        ready = creation.restored,
        creating = creation.submitting,
        failed = creation.failed,
        uncertain = creation.uncertain,
        onNameChange = viewModel::changeName,
        onDismiss = { viewModel.showCreation(false) },
        onCreate = viewModel::createList,
        onAcknowledgeUncertain = viewModel::acknowledgeCreateUncertain,
    )
}
