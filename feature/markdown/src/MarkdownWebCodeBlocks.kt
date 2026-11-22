package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.NativeHtmlDocument
import moe.yumeyuka.markdown.core.GithubHtmlParser
import moe.yumeyuka.markdown.core.HtmlDocument

internal data class MarkdownWebCodeBlock(
    val id: Int,
    val html: String,
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val clip: Rect = Rect.Zero,
)

/** HTML 只保留占位和排版；代码沿用 markdown 库的高亮、选择和横向滚动。 */
@Composable
internal fun MarkdownWebCodeBlocks(
    blocks: List<MarkdownWebCodeBlock>,
    style: HtmlStyles,
    modifier: Modifier,
    scroll: IntOffset,
    interactive: Boolean,
    scrollState: ScrollableState,
    onHeight: (id: Int, heightCssPx: Float) -> Unit,
    onFailure: () -> Unit,
) {
    val density = LocalDensity.current.density
    Box(modifier) {
        blocks.forEach { block ->
            // 内联 lambda 里不能提前 return：Kotlin 会生成 $$$$$NON_LOCAL_RETURN$$$$$ 标记，
            // 当前 R8 无法解析该类，release 的 dex 阶段会直接失败。
            if (block.width > 0f) {
                key(block.id, block.html) {
                    var document by remember { mutableStateOf<HtmlDocument?>(null) }
                    LaunchedEffect(block.html) {
                        try {
                            document =
                                withContext(Dispatchers.Default) {
                                    GithubHtmlParser.parse(block.html)
                                }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        }
                    }
                    val content = document
                    if (content != null) {
                        val shape = remember(block.clip, density) { CodeClip(block.clip, density) }
                        NativeHtmlDocument(
                            content = content,
                            style = style,
                            modifier =
                                Modifier.absoluteOffset {
                                        IntOffset(
                                            (block.left * density).roundToInt() - scroll.x,
                                            (block.top * density).roundToInt() - scroll.y,
                                        )
                                    }
                                    .width(block.width.dp)
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .onSizeChanged { onHeight(block.id, it.height / density) }
                                    .clip(shape)
                                    .scrollable(
                                        state = scrollState,
                                        orientation = Orientation.Vertical,
                                        enabled = interactive,
                                    )
                                    .testTag("markdown-native-code:${block.id}"),
                        )
                    }
                }
            }
        }
    }
}

private class CodeClip(private val bounds: Rect, private val density: Float) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline =
        Outline.Rectangle(
            Rect(
                left = (bounds.left * this.density).coerceIn(0f, size.width),
                top = (bounds.top * this.density).coerceIn(0f, size.height),
                right = (bounds.right * this.density).coerceIn(0f, size.width),
                bottom = (bounds.bottom * this.density).coerceIn(0f, size.height),
            ),
        )
}
