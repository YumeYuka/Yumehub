package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/** DrawScope 属于整篇正文，不能用它的尺寸作为屏幕大小。返回正文坐标系中的裁剪区域。 */
internal fun codeTextDrawViewport(
    contentSize: Size,
    viewportSize: IntSize,
    scrollX: Int,
    scrollY: Int,
    topPadding: Int,
    textViewportWidth: Int,
): Rect {
    if (viewportSize.width <= 0 || viewportSize.height <= 0 || textViewportWidth <= 0) {
        return Rect.Zero
    }
    val left = scrollX.toFloat()
    val top = scrollY.toFloat() - topPadding
    val clipped =
        Rect(
                left,
                top,
                left + minOf(textViewportWidth, viewportSize.width),
                top + viewportSize.height,
            )
            .intersect(Rect(0f, 0f, contentSize.width, contentSize.height))
    return if (clipped.isEmpty) Rect.Zero else clipped
}
