package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import moe.yumeyuka.yumehub.data.repository.ThreadDirection
import moe.yumeyuka.yumehub.data.repository.ThreadWindowState
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.navigation.readingItems

fun <T> LazyListScope.githubThreadItems(
    state: ThreadWindowState<T>,
    key: (T) -> String,
    load: (ThreadDirection) -> Unit,
    placeholder: @Composable () -> Unit,
    pageControl:
        (@Composable
        (loading: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) -> Unit)? =
        null,
    content: @Composable (T, Int) -> Unit,
) {
    fun boundary(direction: ThreadDirection) {
        item(key = "thread-page-$direction") {
            val strings = LocalGithubStrings.current
            val host = requireNotNull(LocalGithubPageHost.current)
            val loading = state.loading == direction
            val enabled = state.loading == null && host.navigationEnabled
            val label =
                if (state.failed == direction) strings.Common.Retry
                else strings.Activity.LoadMoreItems
            if (pageControl != null) {
                pageControl(loading, enabled, label) { if (host.navigationEnabled) load(direction) }
                return@item
            }
            GlasmioButton(
                enabled = enabled,
                onClick = { load(direction) },
            ) {
                GithubLoadingContent(
                    loading = loading,
                    placeholder = {
                        TextLinePlaceholder(
                            GlasmioTheme.textStyles.body,
                            Modifier.width(96.dp),
                            fraction = 1f,
                        )
                    },
                ) {
                    Text(label)
                }
            }
        }
    }
    if (state.failed == ThreadDirection.Latest) boundary(ThreadDirection.Latest)
    if (state.before != null) boundary(ThreadDirection.Older)
    if (!state.ready && state.error == null) {
        readingItem(key = "thread-initial") { Box(Modifier.animateItem()) { placeholder() } }
    }
    readingItems(
        count = state.items.size,
        key = { "thread:${key(state.items[it])}" },
        contentType = { "thread-item" },
    ) { index ->
        Box(Modifier.animateItem()) { content(state.items[index], index) }
    }
    if (state.after != null) boundary(ThreadDirection.Newer)
}

/** 保留线程游标与重试边界，同时按调用方提供的连续分组虚拟化条目。 */
fun <T, G> LazyListScope.githubThreadGroupedItems(
    state: ThreadWindowState<T>,
    groups: List<G>,
    key: (G) -> String,
    load: (ThreadDirection) -> Unit,
    placeholder: @Composable () -> Unit,
    content: @Composable (G) -> Unit,
) {
    fun boundary(direction: ThreadDirection) {
        item(key = "thread-page-$direction") {
            val strings = LocalGithubStrings.current
            val host = requireNotNull(LocalGithubPageHost.current)
            val loading = state.loading == direction
            val enabled = state.loading == null && host.navigationEnabled
            val label =
                if (state.failed == direction) strings.Common.Retry
                else strings.Activity.LoadMoreItems
            GlasmioButton(enabled = enabled, onClick = { load(direction) }) {
                GithubLoadingContent(
                    loading = loading,
                    placeholder = {
                        TextLinePlaceholder(
                            GlasmioTheme.textStyles.body,
                            Modifier.width(96.dp),
                            fraction = 1f,
                        )
                    },
                ) {
                    Text(label)
                }
            }
        }
    }
    if (state.failed == ThreadDirection.Latest) boundary(ThreadDirection.Latest)
    if (state.before != null) boundary(ThreadDirection.Older)
    if (!state.ready && state.error == null) {
        readingItem(key = "thread-initial") { Box(Modifier.animateItem()) { placeholder() } }
    }
    readingItems(
        count = groups.size,
        key = { "thread-group:${key(groups[it])}" },
        contentType = { "thread-group" },
    ) { index ->
        Box(Modifier.animateItem()) { content(groups[index]) }
    }
    if (state.after != null) boundary(ThreadDirection.Newer)
}
