package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RefreshCw
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost

/** 分页失败时显示重试，恢复后保留退场动画。 */
@Composable
fun GithubPagingRetryAction(
    items: LazyPagingItems<*>,
) {
    val states = items.loadState
    val failed =
        states.refresh is LoadState.Error ||
            states.append is LoadState.Error ||
            states.prepend is LoadState.Error
    val host = requireNotNull(LocalGithubPageHost.current)
    GithubToolbarActions(
        if (failed)
            toolbarIcon(
                Lucide.RefreshCw,
                LocalGithubStrings.current.Common.Retry,
                host.navigationEnabled,
            ) {
                if (host.navigationEnabled) items.retry()
            }
        else null,
    )
}
