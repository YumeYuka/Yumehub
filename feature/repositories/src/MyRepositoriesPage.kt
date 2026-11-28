package moe.yumeyuka.yumehub.feature.repositories

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.FilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.PageSearchLayout
import moe.yumeyuka.yumehub.ui.component.PagingContent
import moe.yumeyuka.yumehub.ui.component.SearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppBackHandler
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.PageRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data object MyRepositoriesRoute : AppRoute

@Composable
fun MyRepositoriesPage(login: String, onOpenRepository: (RepositoryDto) -> Unit) {
    val viewModel: RepositoryViewModel =
        koinViewModel(key = "my-repositories-$login", parameters = { parametersOf(login) })
    val strings = LocalAppStrings.current.MyRepositories
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    PageRefreshEffect(viewModel, DataDomain.Repositories, refreshOnFirstActivation = false) {
        viewModel.refreshLanguages()
    }
    val filters = listing.filters
    val filtered = filters.query.isNotBlank() || filters.language != null || filters.type != "all"
    val languages by viewModel.languages.collectAsStateWithLifecycle()
    if (languages == RepositoryLanguages.Failed)
        LoadFailureToast(Unit, LocalAppStrings.current.Activity.LoadFailed)
    var searching by remember { mutableStateOf(false) }
    var query by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(filters.query))
        }
    var filterSheetVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissSearch = {
        searching = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(query.text) {
        delay(200)
        viewModel.setQuery(query.text)
    }
    AppBackHandler(enabled = searching && !filterSheetVisible, onBack = dismissSearch)
    PageSearchLayout(
        searching,
        query,
        { query = it },
        viewModel::setQuery,
        dismissSearch,
        strings.Search,
    ) {
        PagingContent(
            source = listing.pages,
            contentKey = login to filters,
            revalidation = listing.revalidation,
            onRetryRevalidation = viewModel::retryRevalidation,
        ) { paging ->
            val repositories = paging.items
            val slots =
                rememberPagingSlots(
                    repositories,
                    "my-repositories",
                    initialLoading = paging.initialLoading,
                ) {
                    it.id?.let { id -> "id:$id" }
                        ?: it.fullName?.takeIf(String::isNotBlank)?.let { name -> "name:$name" }
                }
            AppPage(
                title = strings.Title,
                listState = paging.listState,
                itemSpacing = 12.dp,
                actions = {
                    moe.yumeyuka.yumehub.ui.component.ToolbarActions(
                        moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                            Lucide.Search,
                            strings.Search,
                            key = "search",
                        ) {
                            searching = true
                        },
                        moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                            Lucide.ListFilter,
                            strings.Filter,
                            key = "filter",
                        ) {
                            focusManager.clearFocus(force = true)
                            keyboard?.hide()
                            filterSheetVisible = true
                        },
                    )
                },
            ) {
                RepositoryScreen(
                    slots,
                    onOpenRepository,
                    emptyScene = if (filtered) EmptyScene.Search else EmptyScene.Inbox,
                    emptyMessage = if (filtered) strings.EmptyFiltered else strings.Empty,
                )
                pageSearchClearance(searching)
            }
        }
        RepositoryFiltersSheet(
            visible = filterSheetVisible,
            filters = filters,
            languages = languages,
            onDismiss = { filterSheetVisible = false },
            onApply = { draft ->
                viewModel.setFilters(draft.type, draft.sort, draft.language)
                filterSheetVisible = false
            },
        )
    }
}

private data class RepositoryFilterChoice(
    val title: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun RepositoryFiltersSheet(
    visible: Boolean,
    filters: RepositoryFilters,
    languages: RepositoryLanguages?,
    onDismiss: () -> Unit,
    onApply: (RepositoryFilters) -> Unit,
) {
    val strings = LocalAppStrings.current.MyRepositories
    val common = LocalAppStrings.current.Common
    var query by remember(visible) { mutableStateOf("") }
    var draft by remember(visible) { mutableStateOf(filters) }
    val normalizedQuery = query.trim()
    val matches: (String) -> Boolean = {
        normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true)
    }
    val sortOptions =
        listOf(
                "updated" to strings.Updated,
                "stars" to strings.Starred,
                "full_name" to strings.Name,
            )
            .filter { matches(it.second) }
            .map { (value, label) ->
                RepositoryFilterChoice(label, draft.sort == value) {
                    draft = draft.copy(sort = value)
                }
            }
    val typeOptions =
        listOf(
                "all" to strings.All,
                "owner" to strings.Owner,
                "member" to strings.Member,
                "public" to strings.Public,
                "private" to strings.Private,
            )
            .filter { matches(it.second) }
            .map { (value, label) ->
                RepositoryFilterChoice(label, draft.type == value) {
                    draft = draft.copy(type = value)
                }
            }
    val languageValues =
        (languages as? RepositoryLanguages.Loaded)?.values.orEmpty() +
            listOfNotNull(filters.language)
    val languageOptions =
        (listOf<String?>(null) + languageValues.distinct())
            .filter { matches(it ?: strings.All) }
            .map { value ->
                RepositoryFilterChoice(value ?: strings.All, draft.language == value) {
                    draft = draft.copy(language = value)
                }
            }
    SearchSelectionSheet(
        visible = visible,
        title = strings.Filter,
        placeholder = common.Search,
        queryKey = query,
        onQueryChange = { query = it },
        onDismiss = onDismiss,
        onConfirm = { onApply(draft) },
    ) { _ ->
        fun group(title: String, choices: List<RepositoryFilterChoice>) {
            if (choices.isEmpty()) return
            item {
                ListSection(title) {
                    choices.forEach { choice ->
                        FilterChoiceRow(
                            choice.title,
                            choice.selected,
                            onClick = choice.onClick,
                        )
                    }
                }
            }
        }
        group(strings.Sort, sortOptions)
        group(strings.Type, typeOptions)
        group(strings.Language, languageOptions)
    }
}
