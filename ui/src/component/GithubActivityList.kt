package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.FileText
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RefreshCw
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.OrganizationRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost

/** 仓库子列表与提交关联列表共用行、占位及分页失败行为。 */
@Composable
fun GithubActivityListPage(
    title: String,
    source: Flow<PagingData<RepositoryActivityEntry>>,
    contentKey: Any,
    onOpen: (RepositoryActivityEntry) -> Unit,
    canOpen: (RepositoryActivityEntry) -> Boolean = { it.url != null },
    subtitle: String? = null,
    icon: ImageVector = Lucide.FileText,
    header: (@Composable () -> Unit)? = null,
    pollWhileVisible: Boolean = false,
    revalidation: StateFlow<RevalidationState> = IdleGithubPagingRevalidation,
    onRetryRevalidation: () -> Unit = {},
    row: (@Composable (RepositoryActivityEntry, (() -> Unit)?) -> Unit)? = null,
    toolbar: (@Composable (refresh: () -> Unit) -> Unit)? = null,
    locked: Boolean = false,
    emptyScene: EmptyScene = EmptyScene.Inbox,
) {
    val strings = LocalGithubStrings.current.Activity
    val host = requireNotNull(LocalGithubPageHost.current)
    GithubPagingContent(source, contentKey, revalidation, onRetryRevalidation) { paging ->
        val pages = paging.items
        val slots =
            rememberGithubPagingSlots(
                pages,
                "activity:$contentKey",
                paging.initialLoading,
                pollWhileVisible = pollWhileVisible,
            ) {
                it.id
            }
        GithubPage(
            title = title,
            locked = locked,
            actions = {
                if (toolbar != null) toolbar { pages.refresh() }
                else
                    GlasmioIconButton(onClick = { if (host.navigationEnabled) pages.refresh() }) {
                        GithubIcon(
                            Lucide.RefreshCw,
                            contentDescription = LocalGithubStrings.current.Repository.Refresh,
                        )
                    }
            },
            titleSubtitle = subtitle,
            listState = paging.listState,
            contentHeader = header,
            itemSpacing = 12.dp,
        ) {
            githubPagingItems(slots, contentType = "activity-row") { entry ->
                GithubLoadingCard(
                    loading = entry == null,
                    placeholder = { OrganizationRowPlaceholder() },
                ) {
                    if (entry != null) {
                        val click: (() -> Unit)? =
                            if (canOpen(entry)) ({ if (host.navigationEnabled) onOpen(entry) })
                            else null
                        if (row != null) row(entry, click)
                        else
                            GlasmioListRow(
                                title = entry.title.ifBlank { strings.Unavailable },
                                subtitle =
                                    listOfNotNull(
                                            entry.number?.let { "#$it" },
                                            entry.subtitle,
                                            entry.timestamp?.let(::githubActivityTime),
                                        )
                                        .joinToString(" · ")
                                        .takeIf(String::isNotEmpty),
                                detail = entry.state?.let(strings.State),
                                leading = {
                                    if (entry.login != null)
                                        GithubAvatar(entry.avatarUrl, entry.login, size = 40.dp)
                                    else GithubIcon(icon, contentDescription = null, size = 20.dp)
                                },
                                systemName = null,
                                showTopSeparator = false,
                                onClick = click,
                            )
                    }
                }
            }
            collaborationPagingStatus(slots, title, emptyScene = emptyScene)
        }
    }
}
