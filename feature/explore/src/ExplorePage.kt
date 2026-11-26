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
import androidx.paging.LoadState
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.Bell
import io.github.yumeyucca.lucide.ListFilter
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubEmptyState
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubPagingContent
import moe.yumeyuka.yumehub.ui.component.IdleGithubPagingRevalidation
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import org.koin.androidx.compose.koinViewModel

@OptIn(FlowPreview::class)
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
    val session by feed.session.collectAsStateWithLifecycle()
    val options = feed.options.collectAsStateWithLifecycle().value
    val login = session?.login
    val filters = options?.filters.orEmpty()
    val settingsFailed by feed.settingsFailed.collectAsStateWithLifecycle()
    val feedKey = remember(options) { options?.let { feedPagingKey(it.filters, it.sort) } }
    val strings = LocalGithubStrings.current
    var filtersVisible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val eventsWhileVisible =
        remember(feed, session, options, lifecycle) {
            feed.eventsFor(login, options).flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
        }
    GithubPagingContent(
        source = eventsWhileVisible,
        contentKey = session to feedKey,
        revalidation =
            remember(feed, session, feedKey) {
                if (login != null && feedKey != null) feed.revalidation(login, feedKey)
                else IdleGithubPagingRevalidation
            },
        onRetryRevalidation =
            if (login != null && feedKey != null) ({ feed.retryRevalidation(login, feedKey) })
            else null,
        showLoadErrors = false,
    ) { paging ->
        val events = paging.items
        val slots =
            rememberGithubPagingSlots(
                events,
                "dashboard-feed",
                paging.initialLoading,
            ) {
                it.id
            }
        val latestSlots by rememberUpdatedState(slots)
        LaunchedEffect(feed, lifecycle, paging.listState) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { latestSlots.visibleItems(paging.listState) }
                        .debounce(100L)
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
            contentHeader = {
                ExploreDiscoverSection(onOpenTrending, onOpenFeatured)
            },
            itemSpacing = 12.dp,
            actions = {
                GlasmioIconButton(onClick = onOpenSearch) {
                    GithubIcon(Lucide.Search, contentDescription = strings.Common.Search)
                }
                GlasmioIconButton(onClick = { filtersVisible = true }, enabled = options != null) {
                    GithubIcon(
                        Lucide.ListFilter,
                        contentDescription = strings.WorkList.SortAndFilter,
                    )
                }
            },
        ) {
            item(key = "explore-activity-spacing") { Spacer(Modifier.height(12.dp)) }
            // The feed explicitly uses “No activity yet” for an unavailable first page.
            val coldFailure =
                slots.snapshot.isEmpty() && events.loadState.refresh is LoadState.Error
            val showEmpty =
                settingsFailed ||
                    (options != null && (filters.isEmpty() || slots.isEmptyResult || coldFailure))
            if (showEmpty) {
                item(key = "explore-feed-empty") {
                    GithubEmptyState(
                        icon = Lucide.Bell,
                        title = strings.Explore.EmptyFeed,
                        modifier = Modifier.fillParentMaxHeight(),
                        verticalBias = -0.1f,
                    )
                }
            }
            if (options == null || filters.isEmpty()) return@GithubPage
            FeedScreen(
                slots = slots,
                cards = cards,
                initialCard = feed::initialDisplayCard,
                onRetry = feed::retry,
                onToggle = feed::toggle,
                onOpenRepository = onOpenRepository,
                onOpenWork = onOpenWork,
                onOpenUser = onOpenUser,
            )
        }
    }
    if (options != null) {
        ExploreFiltersSheet(
            visible = filtersVisible,
            options = options,
            onDismiss = { filtersVisible = false },
            onApply = feed::applyOptions,
        )
    }
}
