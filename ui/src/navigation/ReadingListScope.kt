package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** 只隔离普通列表文字，不增加布局或改变 LazyColumn 的 key、类型与实际 item 作用域。 */
@OptIn(ExperimentalFoundationApi::class)
internal class ReadingLazyListScope(
    private val delegate: LazyListScope,
    private val upstreamBringIntoViewSpec: BringIntoViewSpec,
) : LazyListScope {
    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        delegate.item(key, contentType) {
            val itemScope = this
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides this@ReadingLazyListScope.upstreamBringIntoViewSpec,
            ) {
                DisableSelection { content(itemScope) }
            }
        }
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        delegate.items(count, key, contentType) { index ->
            val itemScope = this
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides this@ReadingLazyListScope.upstreamBringIntoViewSpec,
            ) {
                DisableSelection { itemContent(itemScope, index) }
            }
        }
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        delegate.stickyHeader(key, contentType) { globalIndex ->
            val itemScope = this
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides this@ReadingLazyListScope.upstreamBringIntoViewSpec,
            ) {
                DisableSelection { content(itemScope, globalIndex) }
            }
        }
    }

    fun readingItem(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        delegate.item(key, contentType) {
            val itemScope = this
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides this@ReadingLazyListScope.upstreamBringIntoViewSpec,
            ) {
                // frame 的失活禁选由固定 Host 统一提供，不能在这里分支重挂正文。
                content(itemScope)
            }
        }
    }

    fun readingItems(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        delegate.items(count, key, contentType) { index ->
            val itemScope = this
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides this@ReadingLazyListScope.upstreamBringIntoViewSpec,
            ) {
                itemContent(itemScope, index)
            }
        }
    }
}

/** 原生 Host 下显式允许正文选择；旧 renderer 或普通 LazyListScope 直接保留原 item。 */
fun LazyListScope.readingItem(
    key: Any? = null,
    contentType: Any? = null,
    content: @Composable LazyItemScope.() -> Unit,
) {
    if (this is ReadingLazyListScope) this.readingItem(key, contentType, content)
    else item(key, contentType, content)
}

/** 与 count 版 items 保持同一 paging/key/type 契约，不提前读取任何条目。 */
fun LazyListScope.readingItems(
    count: Int,
    key: ((Int) -> Any)? = null,
    contentType: (Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(Int) -> Unit,
) {
    if (this is ReadingLazyListScope) this.readingItems(count, key, contentType, itemContent)
    else items(count, key, contentType, itemContent)
}
