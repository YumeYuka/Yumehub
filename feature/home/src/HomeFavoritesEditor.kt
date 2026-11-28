package moe.yumeyuka.yumehub.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.EmptyState
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.SectionHeader
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

@Composable
internal fun HomeFavoritesEditor(
    edit: HomeFavoritesEditState,
    query: String,
    searching: Boolean,
    pane: FavoriteCandidates,
    active: Boolean,
    enabled: Boolean,
    favorites: HomeFavoritesViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val draft = if (searching) edit.searchDraft ?: edit.draft else edit.draft
    val request = pane.request
    LaunchedEffect(pane, request, active) {
        if (!active || !pane.loading) return@LaunchedEffect
        try {
            if (request == 0 && query.isNotEmpty()) delay(300)
            val cursor = pane.next
            val page = favorites.candidates(query, cursor)
            val next = page.nextCursor
            check(next == null || pane.cursors.add(next))
            pane.items =
                (pane.items + page.items.filter { !it.nodeId.isNullOrBlank() })
                    .distinctBy { it.nodeId }
            pane.next = next
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pane.error = error
        }
        pane.loading = false
    }

    val candidates =
        remember(pane.items, draft?.selectedIds, searching) {
            if (searching) pane.items
            else pane.items.filterNot { it.nodeId in draft?.selectedIds.orEmpty() }
        }
    key(query) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            state = pane.scroll,
            userScrollEnabled = enabled,
            contentPadding = PaddingValues(12.dp),
        ) {
            if (!searching) {
                item(key = "pinned") {
                    val failed = draft == null && edit.error == HomeFavoritesEditError.Load
                    HomeReorderGroup(
                        title = strings.Home.PinnedRepositories,
                        items = draft?.selected.orEmpty(),
                        emptyTitle = if (failed) strings.Common.Retry else strings.Home.NoFavorites,
                        loading = draft == null && !failed,
                        onEmptyClick = if (failed) favorites::loadDraft else null,
                        strings = strings.Home,
                        shown = true,
                        label = { it.fullName.orEmpty() },
                        leading = { FavoriteOwnerAvatar(it) },
                        onToggle = { if (enabled) favorites.toggleFavorite(it) },
                        onReorder = { if (enabled) favorites.reorderFavorites(it) },
                    )
                }
            }
            if (!searching && candidates.isNotEmpty()) {
                item(key = "my-repositories") {
                    HomeReorderGroup(
                        title = strings.MyRepositories.Title,
                        items = candidates,
                        emptyTitle = strings.MyRepositories.Empty,
                        strings = strings.Home,
                        shown = false,
                        label = { it.fullName.orEmpty() },
                        leading = { FavoriteOwnerAvatar(it) },
                        onToggle = { if (enabled && draft != null) favorites.toggleFavorite(it) },
                        onReorder = { if (enabled) pane.reorderCandidates(it) },
                    )
                }
            } else {
                item {
                    val title =
                        if (query.isEmpty()) strings.MyRepositories.Title
                        else strings.Home.RepositoryResults
                    SectionHeader(title, modifier = Modifier.padding(bottom = 8.dp))
                }
                itemsIndexed(candidates, key = { _, repo -> "candidate:${repo.nodeId}" }) {
                    index, repo ->
                    FavoriteRepositoryRow(
                        repository = repo,
                        first = index == 0,
                        last = index == candidates.lastIndex,
                        checked = draft?.selectedIds?.contains(repo.nodeId) == true,
                        enabled = enabled && draft != null,
                        onToggle = favorites::toggleFavorite,
                    )
                }
            }
            when {
                pane.error != null ->
                    item {
                        GlasmioListRow(
                            title = strings.Home.RepositoryLoadFailed,
                            detail = strings.Common.Retry,
                            enabled = enabled,
                            onClick = pane::loadMore,
                        )
                    }
                pane.loading || pane.next != null ->
                    item {
                        GlasmioGroupedList { TitleRowPlaceholder(showTopSeparator = false) }
                        LaunchedEffect(pane.next, pane.loading, active, enabled) {
                            if (active && enabled && !pane.loading && pane.next != null) {
                                pane.loadMore()
                            }
                        }
                    }
                pane.items.isEmpty() ->
                    item {
                        EmptyState(
                            scene = if (query.isBlank()) EmptyScene.Inbox else EmptyScene.Search,
                            title = if (query.isBlank()) strings.MyRepositories.Empty else strings.MyRepositories.EmptyFiltered,
                        )
                    }
            }
        }
    }
}

internal class FavoriteCandidates {
    val scroll = androidx.compose.foundation.lazy.LazyListState()
    var request by mutableStateOf(0)
        private set

    var items by mutableStateOf(emptyList<RepositoryDto>())
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<Exception?>(null)
    val cursors = mutableSetOf<String>()

    fun reorderCandidates(ordered: List<RepositoryDto>) {
        val ids = ordered.mapTo(mutableSetOf()) { it.nodeId }
        val replacements = ordered.iterator()
        items = items.map { if (it.nodeId in ids) replacements.next() else it }
    }

    fun loadMore() {
        if (loading) return
        loading = true
        error = null
        request++
    }
}

@Composable
private fun FavoriteRepositoryRow(
    repository: RepositoryDto,
    first: Boolean,
    last: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (RepositoryDto) -> Unit,
) {
    GlasmioGroupedList(
        framed = false,
        modifier =
            Modifier.glasmioGroupedListBackground(
                ContinuousRoundedRectangle(
                    topStart = if (first) 26.dp else 0.dp,
                    topEnd = if (first) 26.dp else 0.dp,
                    bottomStart = if (last) 26.dp else 0.dp,
                    bottomEnd = if (last) 26.dp else 0.dp,
                ),
            ),
    ) {
        GlasmioListRow(
            title = repository.fullName.orEmpty(),
            leading = { FavoriteOwnerAvatar(repository) },
            modifier = Modifier.semantics { selected = checked },
            showTopSeparator = !first,
            showDisclosure = false,
            trailing = {
                if (checked) AppIcon(Lucide.Check, contentDescription = null, size = 20.dp)
            },
            enabled = enabled,
            onClick = { onToggle(repository) },
        )
    }
}

@Composable
private fun FavoriteOwnerAvatar(repository: RepositoryDto) {
    val owner = repository.owner
    UserAvatar(url = owner?.avatarUrl, login = owner?.login, type = owner?.type, size = 28.dp)
}
