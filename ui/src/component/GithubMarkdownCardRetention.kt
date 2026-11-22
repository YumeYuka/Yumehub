package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer

internal val LocalMarkdownCardRetention = staticCompositionLocalOf<MarkdownCardRetention?> { null }

/** 只在当前列表的组合中保留最近可见的正文，不把 View、DOM 或句柄存进路由缓存。 */
internal class MarkdownCardRetention {
    private val recent = LinkedHashMap<Any, PinnableContainer.PinnedHandle>()

    fun visit(owner: Any, item: PinnableContainer) {
        recent[owner] = recent.remove(owner) ?: item.pin()
        if (recent.size > 4) remove(recent.keys.first())
    }

    fun remove(owner: Any) {
        recent.remove(owner)?.release()
    }
}

@Composable
fun RetainVisibleMarkdownCard(visibleAndReady: Boolean) {
    val retention = LocalMarkdownCardRetention.current ?: return
    val item = LocalPinnableContainer.current ?: return
    val owner = remember(retention, item) { Any() }
    DisposableEffect(owner) { onDispose { retention.remove(owner) } }
    LaunchedEffect(owner, visibleAndReady) { if (visibleAndReady) retention.visit(owner, item) }
}
