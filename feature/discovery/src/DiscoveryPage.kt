package moe.yumeyuka.yumehub.feature.discovery

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

enum class DiscoveryRoute : GithubRoute { Trending, Featured }

@Composable
fun DiscoveryPage(route: DiscoveryRoute, account: String, onOpenRepository: (RepositoryDto) -> Unit) {
    val vm: DiscoveryViewModel = koinViewModel(parameters = { parametersOf(account, route) })
    val filters by vm.filters.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current
    val trending = route == DiscoveryRoute.Trending
    val title = if (trending) strings.Explore.Trending else strings.Explore.Featured
    var sheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query))
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch = {
        searching = false
        focus.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(input.text) {
        delay(200)
        vm.search(input.text)
    }
    GithubBackHandler(enabled = searching && !sheet, onBack = closeSearch)
    GithubPageSearchLayout(
        searching, input, { input = it }, vm::search, closeSearch, strings.MyRepositories.Search,
    ) {
        GithubPagingContent(vm.repositories, contentKey = Triple(route, filters, query)) { paging ->
            val items = paging.items
            val slots = rememberGithubPagingSlots(items, route.name, paging.initialLoading) { it.nodeId }
            GithubPage(
                title = title,
                listState = paging.listState,
                listTransition = paging.transition,
                itemSpacing = 12.dp,
                actions = {
                    GlasmioIconButton(onClick = { searching = true }) {
                        GithubIcon(Lucide.Search, contentDescription = strings.MyRepositories.Search)
                    }
                    GlasmioIconButton(onClick = {
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                        vm.loadLanguages()
                        sheet = true
                    }) {
                        GithubIcon(Lucide.ListFilter, contentDescription = strings.MyRepositories.Filter)
                    }
                },
            ) {
                githubPagingItems(slots, contentType = "repository") { repository ->
                    RepositoryLoadingCard(
                        repository,
                        onClick = repository?.let { { onOpenRepository(it) } },
                        showCoverPlaceholder = true,
                    )
                }
                if (!paging.initialLoading) {
                    collaborationPagingStatus(
                        items, title, loadingPlaceholder = {},
                        emptyMessage = strings.MyRepositories.EmptyFiltered,
                        emptyIcon = if (trending) Lucide.Flame else Lucide.LayoutGrid,
                    )
                }
                if (items.loadState.refresh is LoadState.Error || items.loadState.append is LoadState.Error) {
                    item(key = "retry") {
                        GlasmioGroupedList {
                            GlasmioListRow(title = strings.Common.Retry, onClick = items::retry)
                        }
                    }
                }
                pageSearchClearance(searching)
            }
        }
    }
    DiscoveryFiltersSheet(sheet, trending, filters, languages, vm::loadLanguages, { sheet = false }) {
        vm.apply(it)
        sheet = false
    }
}
