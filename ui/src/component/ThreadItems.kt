package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
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
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.MarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.navigation.readingItems

/**
 * Matches the real author header and the renderer's pending body inside the same reading surface.
 */
@Composable
fun ThreadContentPlaceholder(showTitle: Boolean = false) {
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
        MarkdownPlaceholder(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            compact = true,
        )
    }
}

/** The pending thread occupies one passive reading slot. */
fun LazyListScope.threadPlaceholder(placeholder: @Composable () -> Unit) {
    readingItem(key = "thread-initial") {
        Column(Modifier.clearAndSetSemantics {}) { placeholder() }
    }
}

fun <T> LazyListScope.threadItems(
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
    threadRows(
        state,
        state.items.size,
        { "thread:${key(state.items[it])}" },
        load,
        placeholder,
        pageControl,
    ) { index ->
        content(state.items[index], index)
    }
}

/** 保留线程游标与重试边界，同时按调用方提供的连续分组虚拟化条目。 */
fun <T, G> LazyListScope.threadGroupedItems(
    state: ThreadWindowState<T>,
    groups: List<G>,
    key: (G) -> String,
    load: (ThreadDirection) -> Unit,
    placeholder: @Composable () -> Unit,
    content: @Composable (G) -> Unit,
) {
    threadRows(
        state,
        groups.size,
        { "thread-group:${key(groups[it])}" },
        load,
        placeholder,
        null,
    ) {
        content(groups[it])
    }
}

private fun LazyListScope.threadRows(
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
            val strings = LocalAppStrings.current
            val host = requireNotNull(LocalAppPageHost.current)
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
                LoadingContent(
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
    if (count == 0 && !state.ready && state.error == null) {
        threadPlaceholder(placeholder)
    }
    readingItems(count = count, key = key, contentType = { "thread-item" }) { index ->
        content(index)
    }
    if (state.after != null) boundary(ThreadDirection.Newer)
}
