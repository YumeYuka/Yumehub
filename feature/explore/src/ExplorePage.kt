package moe.yumeyuka.yumehub.feature.explore

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExplorePage(
    onOpenSearch: () -> Unit,
    onOpenTrending: () -> Unit,
    onOpenFeatured: () -> Unit,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenWork: (WorkTarget) -> Unit,
    onOpenUser: (UserDto) -> Unit,
) {
    val feed: FeedViewModel = koinViewModel()
    val cards = feed.cards
    val options by feed.options.collectAsStateWithLifecycle()
    val (filters, sort) = options
    val strings = LocalGithubStrings.current
    var filtersVisible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val eventsWhileVisible =
        remember(feed, lifecycle) {
            feed.events.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
        }
    GithubPagingContent(eventsWhileVisible, contentKey = options) { paging ->
        val events = paging.items
        val slots =
            rememberGithubPagingSlots(
                events,
                "feed-${sort.name}:${filters.map { it.name }.sorted()}",
                paging.initialLoading,
            ) {
                it.id
            }
        val latestSlots by rememberUpdatedState(slots)
        LaunchedEffect(feed, lifecycle, paging.listState) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { latestSlots.visibleItems(paging.listState) }
                        .collect(feed::setVisible)
                } finally {
                    feed.setVisible(emptyList())
                }
            }
        }
        GithubPage(
            title = strings.Explore.Title,
            back = false,
            listState = paging.listState,
            listTransition = paging.transition,
            itemSpacing = 12.dp,
            actions = {
                GlasmioIconButton(onClick = onOpenSearch) {
                    GithubIcon(Lucide.Search, contentDescription = strings.Common.Search)
                }
                GlasmioIconButton(onClick = { filtersVisible = true }) {
                    GithubIcon(
                        Lucide.ListFilter,
                        contentDescription = strings.WorkList.SortAndFilter,
                    )
                }
            },
        ) {
            item(key = "explore-discover") {
                ExploreDiscoverSection(onOpenTrending, onOpenFeatured)
            }
            item(key = "explore-activity-spacing") { Spacer(Modifier.height(12.dp)) }
            FeedScreen(
                events = events,
                slots = slots,
                cards = cards,
                filters = filters,
                onRetry = feed::retry,
                onToggle = feed::toggle,
                onOpenRepository = onOpenRepository,
                onOpenWork = onOpenWork,
                onOpenUser = onOpenUser,
            )
        }
    }
    ExploreFiltersSheet(
        visible = filtersVisible,
        options = options,
        onDismiss = { filtersVisible = false },
        onApply = feed::applyOptions,
    )
}
