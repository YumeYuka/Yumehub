package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** 由 app 装配官方渲染 feature，ui 和页面不反向依赖具体实现 */
@Immutable
data class MarkdownContent(
    val text: String,
    val html: String? = null,
    val nodeId: String? = null,
    val pageUrl: String? = null,
    val repositoryRootUrl: String? = null,
    val preview: Boolean = false,
    val readme: Boolean = false,
    val ref: String? = null,
    val scrollToAnchor: String? = null,
    val transparentBackground: Boolean = false,
    val selectionHosted: Boolean = false,
    val compactBodyTypography: Boolean = false,
)

data class MarkdownActions(
    val onOpenLink: (String) -> Unit,
    val onTaskChanged: ((position: Int, checked: Boolean) -> Unit)? = null,
    val onCommitSuggestion: ((suggestionId: String, previewHtml: String) -> Unit)? = null,
    val onReady: (() -> Unit)? = null,
)

interface MarkdownRenderer {
    /** 只有支持父列表选择作用域的实现才接入固定 Host，旧实现保持原列表树。 */
    val supportsHostedSelection: Boolean
        get() = false

    /** 每个列表 frame 各自调用；选区状态由实现记在本次组合中，不放入 renderer 单例。 */
    @Composable
    fun SelectionHost(
        active: Boolean,
        modifier: Modifier = Modifier,
        content: @Composable (Modifier) -> Unit,
    ) {
        content(modifier)
    }

    @Composable
    fun Render(
        content: MarkdownContent,
        modifier: Modifier,
        actions: MarkdownActions,
    )

    /**
     * 编辑器预览：铺满给定区域、在 WebView 内部滚动。[visible] 为 false 时仍保持放置和绘制， 由调用方用 z 序遮住，渲染器只暂停媒体，不暂停 WebView。
     */
    @Composable
    fun Preview(
        content: MarkdownContent,
        visible: Boolean,
        contentPadding: PaddingValues,
        modifier: Modifier,
        actions: MarkdownActions,
    )
}

val LocalMarkdownRenderer = staticCompositionLocalOf<MarkdownRenderer?> { null }

/** 是否已处于页面固定选择 Host；与当前 item 是否允许选择无关。 */
val LocalMarkdownSelectionHosted = staticCompositionLocalOf { false }
