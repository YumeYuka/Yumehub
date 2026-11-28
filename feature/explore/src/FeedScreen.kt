package moe.yumeyuka.yumehub.feature.explore

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.WorkTarget
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.FeedActivityPlaceholder
import moe.yumeyuka.yumehub.ui.platform.rememberPageLinkHandler

internal fun LazyListScope.FeedScreen(
    slots: PagingSlots<FeedEventDto>,
    cards: SnapshotStateMap<String, MutableState<FeedCardState>>,
    initialCard: (FeedEventDto) -> FeedCardState?,
    onRetry: (FeedEventDto) -> Unit,
    onToggle: (FeedEventDto) -> Unit,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenWork: (WorkTarget) -> Unit,
    onOpenUser: (UserDto) -> Unit,
) {
    pagingItems(
        slots,
        contentType = "feed-activity",
        placeholder = { FeedActivityPlaceholder() },
    ) { event ->
        val state =
            cards[event.cardKey()]?.value ?: remember(event, initialCard) { initialCard(event) }
        val openUri = rememberPageLinkHandler()
        val repository = state?.repository
        val user = state?.user
        val work = state?.work ?: event.workItem()
        val number = work?.number ?: event.payload.number
        val url = work?.htmlUrl ?: event.payload.release?.htmlUrl
        val fullName = event.repo?.name
        val open: (() -> Unit)? =
            when {
                url != null -> ({ openUri(url) })
                work != null &&
                    number != null &&
                    fullName != null &&
                    event.payload.discussion == null -> ({
                        onOpenWork(
                            WorkTarget(
                                fullName.substringBefore('/'),
                                fullName.substringAfter('/'),
                                number,
                                if (event.isPullRequestWork()) CollaborationKind.PullRequest
                                else CollaborationKind.Issue,
                            ),
                        )
                    })
                work != null || event.payload.release != null -> null
                repository != null -> ({ onOpenRepository(repository) })
                user != null -> ({ onOpenUser(user) })
                else -> null
            }
        FeedActivity(event, state, { onRetry(event) }, { onToggle(event) }, open)
    }
}
