package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import moe.yumeyuka.yumehub.data.repository.ThreadDirection
import moe.yumeyuka.yumehub.data.repository.ThreadWindowState
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.navigation.readingItems

/** Matches the real author header and the renderer's pending body inside the same reading surface. */
@Composable
fun GithubThreadContentPlaceholder(showTitle: Boolean = false) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarPlaceholder(34.dp)
            Column(Modifier.weight(1f)) {
                TextLinePlaceholder(GlasmioTheme.textStyles.subheadline, fraction = 0.45f)
                TextLinePlaceholder(GlasmioTheme.textStyles.caption1, fraction = 0.3f)
            }
        }
        if (showTitle) {
            TextLinePlaceholder(
                GlasmioTheme.typography.headlineLarge,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fraction = 0.78f,
            )
        }
        GithubMarkdownPlaceholder(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), compact = true)
    }
}

/** The pending thread occupies one passive reading slot. */
fun LazyListScope.githubThreadPlaceholder(placeholder: @Composable () -> Unit) {
    readingItem(key = "thread-initial") {
        Box(Modifier.clearAndSetSemantics {}) { placeholder() }
    }
}

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
    githubThreadRows(
        state, state.items.size, { "thread:${key(state.items[it])}" }, load, placeholder, pageControl,
    ) { index -> content(state.items[index], index) }
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
    githubThreadRows(state, groups.size, { "thread-group:${key(groups[it])}" }, load, placeholder, null) {
        content(groups[it])
    }
}

private fun LazyListScope.githubThreadRows(
    state: ThreadWindowState<*>,
    count: Int,
    key: (Int) -> String,
    load: (ThreadDirection) -> Unit,
    placeholder: @Composable () -> Unit,
    pageControl: (@Composable (Boolean, Boolean, String, () -> Unit) -> Unit)?,
    content: @Composable (Int) -> Unit,
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
            val onClick = { if (host.isCurrent && host.navigationEnabled) load(direction) }
            if (pageControl != null) {
                pageControl(loading, enabled, label, onClick)
                return@item
            }
            GlasmioButton(enabled = enabled, onClick = onClick) {
                GithubLoadingContent(
                    loading = loading,
                    deferUntilEntry = false,
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
    if (count == 0 && !state.ready && state.error == null) {
        githubThreadPlaceholder(placeholder)
    }
    readingItems(count = count, key = key, contentType = { "thread-item" }) { index -> content(index) }
    if (state.after != null) boundary(ThreadDirection.Newer)
}
