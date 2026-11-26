package moe.yumeyuka.yumehub.feature.home

import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.GlasmioReorderableGroup
import io.github.neoruaa.glasmio.ui.GlasmioSearchToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.GithubStrings_Home
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler

private enum class HomeEditTab {
    Work,
    Favorites,
    Search,
}

@Composable
internal fun HomeWorkSheet(
    visible: Boolean,
    draft: HomeWorkLayout,
    favorites: HomeFavoritesViewModel,
    onDismiss: () -> Unit,
    onChange: (HomeWorkLayout) -> Unit,
    onConfirm: () -> Unit,
) {
    val locale = LocalGithubStrings.current
    val strings = locale.Home
    val common = locale.Common
    val edit by favorites.editState.collectAsStateWithLifecycle()
    var tab by rememberSaveable(visible) { mutableStateOf(HomeEditTab.Work) }
    var query by rememberSaveable(visible, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val enabled = !edit.saving && !edit.saved
    val showSearch = tab == HomeEditTab.Favorites && edit.searchDraft != null
    val screen = if (showSearch) HomeEditTab.Search else tab
    val stateHolder = rememberSaveableStateHolder()
    val myCandidates = remember(favorites, visible) { FavoriteCandidates() }
    val searches = remember(favorites, visible) { LinkedHashMap<String, FavoriteCandidates>(8, 0.75f, true) }
    val dismiss = {
        favorites.dismissEditor()
        onDismiss()
    }
    LaunchedEffect(visible) {
        if (!visible) favorites.dismissEditor()
    }
    LaunchedEffect(visible, tab) {
        if (visible && tab == HomeEditTab.Favorites) favorites.loadDraft()
    }
    LaunchedEffect(edit.accepted) {
        if (edit.accepted) {
            favorites.consumeAccepted()
            if (visible) {
                favorites.resetEditor()
                onConfirm()
            } else {
                favorites.dismissEditor()
            }
        }
    }
    if (visible) {
        val message =
            when (edit.error) {
                HomeFavoritesEditError.Load -> strings.FavoritesLoadFailed
                HomeFavoritesEditError.Save -> strings.FavoritesSaveFailed
                HomeFavoritesEditError.Limit -> strings.FavoritesLimit
                null -> ""
            }
        GithubLoadFailureToast(edit.error, message)
    }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = dismiss,
        title = strings.EditHome,
        skipPartiallyExpanded = false,
        dismissOnBackPress = !showSearch,
        dismissOnClickOutside = !showSearch,
        topBar = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Column {
                GlasmioTopBar(
                    title = if (showSearch) locale.MyRepositories.Search else strings.EditHome,
                    navigation = {
                        GithubToolbarActions(
                            toolbarIcon(
                                if (showSearch) Lucide.ChevronLeft else Lucide.X,
                                if (showSearch) common.Back else common.Cancel,
                                true,
                                key = "dismiss",
                            ) {
                                if (showSearch) {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    favorites.finishSearch(apply = false)
                                } else dismiss()
                            },
                        )
                    },
                    actions = {
                        GithubToolbarActions(
                            if (tab == HomeEditTab.Favorites && !showSearch)
                                toolbarIcon(Lucide.Search, common.Search, enabled && edit.draft != null) {
                                    query = TextFieldValue()
                                    favorites.startSearch()
                                }
                            else null,
                            toolbarIcon(Lucide.Check, common.Confirm, enabled) {
                                focus.clearFocus()
                                keyboard?.hide()
                                if (showSearch) favorites.finishSearch(apply = true)
                                else favorites.confirmEditor()
                            },
                        )
                    },
                )
                AnimatedVisibility(
                    visible = !showSearch,
                    enter = expandVertically(expandFrom = Alignment.Top, animationSpec = tween(180)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(180)),
                ) {
                    GlasmioSegmentedControl(
                        items =
                            listOf(
                                HomeEditTab.Work to strings.WorkTab,
                                HomeEditTab.Favorites to strings.FavoritesTab,
                            ),
                        selected = tab,
                        onSelected = { tab = it },
                        enabled = enabled && !showSearch,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val cancelSearch = {
            focus.clearFocus()
            keyboard?.hide()
            favorites.finishSearch(apply = false)
        }
        GithubBackHandler(enabled = visible && showSearch, onBack = cancelSearch)
        Box(Modifier.fillMaxSize().padding(LocalGlasmioSheetContentPadding.current)) {
            stateHolder.SaveableStateProvider(screen) {
                if (screen == HomeEditTab.Work) {
                    HomeWorkEditor(draft, strings, enabled, onChange)
                } else Column(Modifier.fillMaxSize()) {
                    val searchPage = screen == HomeEditTab.Search
                    val term = if (searchPage) query.text.trim() else ""
                    val candidates =
                        if (searchPage) remember(searches, term) {
                            searches.getOrPut(term) { FavoriteCandidates() }.also {
                                if (searches.size > 8) searches.remove(searches.keys.first())
                            }
                        }
                        else myCandidates
                    HomeFavoritesEditor(
                        edit = edit,
                        query = term,
                        searching = searchPage,
                        pane = candidates,
                        active = visible,
                        enabled = enabled,
                        favorites = favorites,
                        modifier = Modifier.weight(1f),
                    )
                    if (searchPage) {
                        GlasmioSearchToolbar(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { focus.clearFocus() },
                            onCancel = cancelSearch,
                            cancelLabel = common.Close,
                            placeholder = locale.MyRepositories.Search,
                            enabled = enabled && showSearch,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWorkEditor(
    draft: HomeWorkLayout,
    strings: GithubStrings_Home,
    enabled: Boolean,
    onChange: (HomeWorkLayout) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState(), enabled = enabled)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeReorderGroup(
            title = strings.ShownOnHome,
            items = draft.shown,
            emptyTitle = strings.NoWorkItems,
            strings = strings,
            shown = true,
            label = { it.title(strings) },
            leading = { HomeWorkIcon(it) },
            onToggle = { if (enabled) onChange(draft.toggle(it)) },
            onReorder = { if (enabled) onChange(draft.reorder(it, visible = true)) },
        )
        HomeReorderGroup(
            title = strings.HiddenFromHome,
            items = draft.hidden,
            emptyTitle = strings.AllWorkItemsShown,
            strings = strings,
            shown = false,
            label = { it.title(strings) },
            leading = { HomeWorkIcon(it) },
            onToggle = { if (enabled) onChange(draft.toggle(it)) },
            onReorder = { if (enabled) onChange(draft.reorder(it, visible = false)) },
        )
    }
}

@Composable
internal fun <T> HomeReorderGroup(
    title: String,
    items: List<T>,
    emptyTitle: String,
    strings: GithubStrings_Home,
    shown: Boolean,
    label: (T) -> String,
    leading: @Composable (T) -> Unit,
    onToggle: (T) -> Unit,
    onReorder: (List<T>) -> Unit,
    loading: Boolean = false,
    onEmptyClick: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GithubSectionHeader(title)
        GithubLoadingContent(
            loading = loading,
            deferUntilEntry = false,
            placeholder = { GlasmioGroupedList { TitleRowPlaceholder(showTopSeparator = false) } },
        ) {
            if (items.isEmpty()) {
                GlasmioGroupedList { GlasmioListRow(title = emptyTitle, systemName = null, onClick = onEmptyClick) }
            } else {
                GlasmioReorderableGroup(
                    modifier = Modifier.fillMaxWidth(),
                    items = items,
                    onReorder = onReorder,
                    dragHandleContentDescription = strings.DragToReorder,
                    moveUpLabel = strings.MoveUp,
                    moveDownLabel = strings.MoveDown,
                ) { item ->
                    val name = label(item)
                    Row(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                                onClickLabel =
                                    if (shown) strings.RemoveWorkItem(name)
                                    else strings.AddWorkItem(name),
                                onClick = { onToggle(item) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(GlasmioListRowDefaults.horizontalPadding),
                    ) {
                        leading(item)
                        Text(
                            name,
                            modifier = Modifier.weight(1f),
                            style = GlasmioTheme.textStyles.body,
                            color = GlasmioTheme.colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
