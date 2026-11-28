package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import moe.yumeyuka.yumehub.ui.platform.rememberPageLinkHandler

internal val ReadingGroupShape: Shape
    @Composable get() = ContinuousRoundedRectangle(26.dp)

/** A receipt belongs to a mounted renderer, not just to data in a cache. */
internal class ReadingReceipt {
    var drawnBy by mutableStateOf<Any?>(null)
}

internal val LocalReadingReceipt = staticCompositionLocalOf<ReadingReceipt?> { null }

/** One document and its dependent controls. Null means there is no document to wait for. */
@Composable
fun ReadingUnit(documentKey: Any?, content: @Composable () -> Unit) {
    val receipt = if (documentKey == null) null else remember(documentKey) { ReadingReceipt() }
    CompositionLocalProvider(LocalReadingReceipt provides receipt, content = content)
}

@Composable
fun ReadingFooter(content: @Composable () -> Unit) {
    val receipt = LocalReadingReceipt.current
    if (receipt == null || receipt.drawnBy != null) content()
}

@Composable
fun ReadingGroup(
    modifier: Modifier = Modifier,
    onFooterClick: (() -> Unit)? = null,
    footerEnabled: Boolean = true,
    footer: @Composable () -> Unit = {},
    onHeaderClick: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    documentKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GlasmioTheme.colors
    val presses =
        if (onFooterClick != null || onHeaderClick != null) remember { MutableInteractionSource() }
        else null
    val background =
        if (presses?.collectIsPressedAsState()?.value == true) {
            colors.content.copy(alpha = 0.08f).compositeOver(colors.elevatedBackground)
        } else colors.elevatedBackground
    GlasmioGroupedList(
        modifier = modifier.clip(ReadingGroupShape).background(background),
        framed = false,
    ) {
        if (onHeaderClick != null) {
            Box(
                Modifier.clickable(
                    interactionSource = presses,
                    indication = null,
                    role = Role.Button,
                    onClick = onHeaderClick,
                ),
            ) {
                header()
            }
        } else header()
        ReadingUnit(documentKey) {
            content()
            if (onFooterClick != null) {
                ReadingFooter {
                    Box(
                        Modifier.clickable(
                            interactionSource = presses,
                            indication = null,
                            enabled = footerEnabled,
                            role = Role.Button,
                            onClick = onFooterClick,
                        ),
                    ) {
                        footer()
                    }
                }
            }
        }
    }
}

/** 页面只依赖此契约；官方 HTML / WebView 的实现由独立 feature 经 app 注入 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    pageUrl: String? = null,
    repositoryRootUrl: String? = null,
    html: String? = null,
    nodeId: String? = null,
    preview: Boolean = false,
    readme: Boolean = false,
    ref: String? = null,
    scrollToAnchor: String? = null,
    transparentBackground: Boolean = false,
    compactBodyTypography: Boolean = false,
    onTaskChanged: ((position: Int, checked: Boolean) -> Unit)? = null,
    onCommitSuggestion: ((suggestionId: String, previewHtml: String) -> Unit)? = null,
    onReady: (() -> Unit)? = null,
) {
    val renderer =
        requireNotNull(LocalMarkdownRenderer.current) {
            "MarkdownBody 必须由 app 注册官方 HTML 渲染模块"
        }
    renderer.Render(
        content =
            MarkdownContent(
                text = text,
                html = html.takeUnless { preview },
                nodeId = nodeId,
                pageUrl = pageUrl,
                repositoryRootUrl = repositoryRootUrl,
                preview = preview,
                readme = readme,
                ref = ref,
                scrollToAnchor = scrollToAnchor,
                transparentBackground = transparentBackground,
                selectionHosted = LocalMarkdownSelectionHosted.current,
                compactBodyTypography = compactBodyTypography,
            ),
        modifier = modifier,
        actions =
            MarkdownActions(
                onOpenLink = rememberPageLinkHandler(),
                onTaskChanged = onTaskChanged,
                onCommitSuggestion = onCommitSuggestion,
                onReady = onReady,
            ),
    )
}

/** 编辑器草稿预览。[text] 变化才重新请求官方 GFM 渲染；隐藏时保持放置，调用方以不透明层遮住， 重新显示时复用 WebView 和已有 DOM。 */
@Composable
fun MarkdownPreview(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    pageUrl: String? = null,
    repositoryRootUrl: String? = null,
    scrollToAnchor: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    transparentBackground: Boolean = false,
    html: String? = null,
    onOpenLink: ((String) -> Boolean)? = null,
) {
    // Do not create a renderer for an editor tab that has never been opened. Once opened,
    // retain the same mounted preview behind the editor and suspend its interactions.
    var visited by remember { mutableStateOf(false) }
    if (!visible && !visited) return
    if (visible && !visited) SideEffect { visited = true }
    val renderer =
        requireNotNull(LocalMarkdownRenderer.current) {
            "MarkdownPreview 必须由 app 注册官方 HTML 渲染模块"
        }
    val openLink = rememberPageLinkHandler()
    renderer.Preview(
        content =
            MarkdownContent(
                text = text,
                pageUrl = pageUrl,
                repositoryRootUrl = repositoryRootUrl,
                scrollToAnchor = scrollToAnchor,
                html = html,
                preview = html == null,
                transparentBackground = transparentBackground,
            ),
        visible = visible,
        contentPadding = contentPadding,
        modifier = modifier,
        actions =
            MarkdownActions(
                onOpenLink = { url -> if (onOpenLink?.invoke(url) != true) openLink(url) },
            ),
    )
}
