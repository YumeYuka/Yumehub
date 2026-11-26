package moe.yumeyuka.yumehub.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.ExploreCategory
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.repository.SearchCodeFile
import moe.yumeyuka.yumehub.locale.GithubStrings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubPageSearchLayout
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.collaborationPagingStatus
import moe.yumeyuka.yumehub.ui.component.pageSearchClearance
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import org.koin.androidx.compose.koinViewModel

/** 独立搜索页的路由键。从详情返回时保留原搜索结果。 */
data object ExploreSearchRoute : GithubRoute

@Composable
fun ExploreSearchPage(
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenWork: (WorkTarget) -> Unit,
    onOpenUser: (UserDto) -> Unit,
    onOpenFile: (SearchCodeFile, Int?) -> Unit,
) {
    val viewModel: ExploreViewModel = koinViewModel()
    val search by viewModel.searchState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val autoFocus = remember { query.isBlank() }
    var input by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(query, TextRange(query.length)))
        }
    var category by rememberSaveable { mutableStateOf(search.category) }
    LaunchedEffect(viewModel) {
        viewModel.selectCategory(category)
        viewModel.onQueryChange(input.text)
    }
    val closeSearch: () -> Unit = {
        if (host.backEnabled) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            host.onBack()
        }
    }
    GithubPageSearchLayout(
        visible = true,
        query = input,
        onQueryChange = {
            input = it
            viewModel.onQueryChange(it.text)
        },
        onSearch = {
            focus.clearFocus()
            viewModel.search()
        },
        onClose = closeSearch,
        placeholder = strings.Search.Placeholder(search.category.label(strings)),
        autoFocus = autoFocus,
    ) {
        GithubPagingContent(
            search.items,
            contentKey = Triple(search.session, search.category, search.query),
            revalidation = search.revalidation,
            onRetryRevalidation = { viewModel.retryRevalidation(search) },
        ) { paging ->
            val results = paging.items
            val slots =
                rememberGithubPagingSlots(
                    results,
                    "explore-search",
                    paging.initialLoading,
                ) { result ->
                    when (result) {
                        is ExploreResult.Repository ->
                            result.item.id?.toString() ?: result.item.fullName
                        is ExploreResult.Work -> result.target.toString()
                        is ExploreResult.Actor -> result.item.login
                        is ExploreResult.Code ->
                            with(result.file) { "$owner/$repository:$ref:$path" }
                    }
                }
            GithubPage(
                title = strings.Common.Search,
                back = true,
                onBack = closeSearch,
                listState = paging.listState,
                itemSpacing = 12.dp,
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    GlasmioMenu(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        itemCount = ExploreCategory.entries.size,
                        anchor = { open ->
                            GlasmioIconButton(onClick = open, enabled = host.navigationEnabled) {
                                GithubIcon(
                                    Lucide.ListFilter,
                                    contentDescription = strings.Search.Filter,
                                )
                            }
                        },
                    ) { backdrop, close ->
                        ExploreCategory.entries.forEach { choice ->
                            GlasmioMenuItem(
                                title = choice.label(strings),
                                backdrop = backdrop,
                                trailing = {
                                    if (choice == search.category) {
                                        GithubIcon(Lucide.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    close()
                                    category = choice
                                    viewModel.selectCategory(choice)
                                },
                            )
                        }
                    }
                },
            ) {
                if (search.needsQuery) return@GithubPage
                searchResultRows(
                    slots,
                    search.category,
                    onOpenRepository,
                    onOpenWork,
                    onOpenUser,
                    onOpenFile,
                )
                collaborationPagingStatus(
                    slots, strings.Common.Search,
                    emptyMessage = strings.EditorSearch.NoMatches,
                    emptyIcon = Lucide.Search,
                )
                pageSearchClearance(visible = true)
            }
        }
    }
}

private fun ExploreCategory.label(strings: GithubStrings): String =
    when (this) {
        ExploreCategory.Repositories -> strings.Home.Repositories
        ExploreCategory.Issues -> strings.Home.Issues
        ExploreCategory.PullRequests -> strings.Home.PullRequests
        ExploreCategory.People -> strings.Search.People
        ExploreCategory.Organizations -> strings.Home.Organizations
        ExploreCategory.Code -> strings.Repository.Code
    }
