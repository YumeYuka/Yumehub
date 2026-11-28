package moe.yumeyuka.yumehub.feature.discovery

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yumeyucca.lucide.*
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.navigation.AppBackHandler
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

enum class DiscoveryRoute : AppRoute {
    Trending,
    Featured,
}

@Composable
fun DiscoveryPage(
    route: DiscoveryRoute,
    account: String,
    onOpenRepository: (RepositoryDto) -> Unit,
) {
    val vm: DiscoveryViewModel =
        koinViewModel(
            key = "discovery:$account:$route",
            parameters = { parametersOf(account, route) },
        )
    val filters by vm.filters.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filtered = query.isNotBlank() || filters != DiscoveryFilters.Default
    val languages by vm.languages.collectAsStateWithLifecycle()
    val pages = remember(vm, filters, query) { vm.repositoriesFor(filters, query) }
    val sourceKey = remember(vm, filters, query) { vm.sourceKey(filters, query) }
    val strings = LocalAppStrings.current
    val trending = route == DiscoveryRoute.Trending
    val title = if (trending) strings.Explore.Trending else strings.Explore.Featured
    var sheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var input by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
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
    AppBackHandler(enabled = searching && !sheet, onBack = closeSearch)
    PageSearchLayout(
        searching,
        input,
        { input = it },
        vm::search,
        closeSearch,
        strings.MyRepositories.Search,
    ) {
        PagingContent(
            pages,
            contentKey = Triple(account, sourceKey, query),
            revalidation = remember(vm, sourceKey) { vm.revalidation(sourceKey) },
            onRetryRevalidation = { vm.retryRevalidation(sourceKey) },
        ) { paging ->
            val items = paging.items
            val slots =
                rememberPagingSlots(items, route.name, paging.initialLoading) { it.nodeId }
            AppPage(
                title = title,
                listState = paging.listState,
                itemSpacing = 12.dp,
                actions = {
                    moe.yumeyuka.yumehub.ui.component.ToolbarActions(
                        moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                            Lucide.Search,
                            strings.MyRepositories.Search,
                            key = "search",
                        ) {
                            searching = true
                        },
                        moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                            Lucide.ListFilter,
                            strings.MyRepositories.Filter,
                            key = "filter",
                        ) {
                            focus.clearFocus(force = true)
                            keyboard?.hide()
                            vm.loadLanguages()
                            sheet = true
                        },
                    )
                },
            ) {
                pagingItems(slots, contentType = "repository") { repository ->
                    RepositoryLoadingCard(
                        repository,
                        onClick = repository?.let { { onOpenRepository(it) } },
                        showCoverPlaceholder = true,
                    )
                }
                collaborationPagingStatus(
                    slots,
                    title,
                    emptyMessage = if (filtered) strings.MyRepositories.EmptyFiltered else strings.MyRepositories.Empty,
                    emptyScene = if (filtered) EmptyScene.Search else EmptyScene.Inbox,
                )
                pageSearchClearance(searching)
            }
        }
    }
    DiscoveryFiltersSheet(
        sheet,
        trending,
        filters,
        languages,
        vm::loadLanguages,
        { sheet = false },
    ) {
        vm.apply(it)
        sheet = false
    }
}
