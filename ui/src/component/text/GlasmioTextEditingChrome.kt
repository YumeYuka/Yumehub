package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextRange
import kotlin.math.sqrt

internal data class TextCaretTarget(val rect: Rect, val active: Boolean, val direct: Boolean)

/** 只插值内容坐标；滚动仍由文本布局直接带动，拖动和重新聚焦时立即定位。 */
internal class TextCaretMotion {
    private val position = Animatable(Offset.Zero, Offset.VectorConverter)
    private var previous: TextCaretTarget? = null
    private var ready by mutableStateOf(false)

    fun reset() {
        previous = null
        ready = false
    }

    suspend fun update(target: TextCaretTarget) {
        val last = previous
        previous = target
        if (
            ready &&
                last?.active == true &&
                target.active &&
                !last.direct &&
                !target.direct &&
                last.rect.size == target.rect.size
        ) {
            // 对应 VS Code 的 transition: all 80ms，采用 CSS 默认 ease 曲线。
            position.animateTo(
                target.rect.topLeft,
                tween(80, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)),
            )
        } else {
            position.snapTo(target.rect.topLeft)
        }
        ready = true
    }

    fun rect(target: Rect, direct: Boolean): Rect =
        if (!ready || direct) target else Rect(position.value, target.size)
}

internal fun editingCaretRect(
    cursor: Rect,
    width: Float,
    maxHeight: Float,
    contentLeft: Float,
    contentRight: Float,
): Rect {
    val height = maxHeight.coerceAtMost(cursor.height)
    val left =
        (cursor.center.x - width / 2f).coerceIn(
            contentLeft,
            (contentRight - width).coerceAtLeast(contentLeft),
        )
    return Rect(Offset(left, cursor.center.y - height / 2f), Size(width, height))
}

internal enum class EditingHandleShape {
    Cursor,
    SelectionLeft,
    SelectionRight,
}

internal fun editingHandleShape(selection: TextRange, start: Boolean): EditingHandleShape =
    when {
        selection.collapsed -> EditingHandleShape.Cursor
        start != selection.reversed -> EditingHandleShape.SelectionLeft
        else -> EditingHandleShape.SelectionRight
    }

/** 三种手柄均以文字下边界为尖端；触摸区居中于水滴的圆头。 */
internal fun editingHandleTip(rect: Rect): Offset = Offset(rect.center.x, rect.bottom)

internal fun editingHandleHeadOffset(shape: EditingHandleShape, radius: Float): Offset =
    when (shape) {
        EditingHandleShape.Cursor -> Offset(0f, sqrt(2f) * radius)
        EditingHandleShape.SelectionLeft -> Offset(-radius, radius)
        EditingHandleShape.SelectionRight -> Offset(radius, radius)
    }

/** 以 (0, 0) 为尖端；插入点为对称水滴，选区两端为朝外的四分之三圆。 */
internal fun editingHandlePath(shape: EditingHandleShape, radius: Float): Path =
    Path().apply {
        moveTo(0f, 0f)
        when (shape) {
            EditingHandleShape.Cursor -> {
                val shoulder = radius / sqrt(2f)
                val centerY = sqrt(2f) * radius
                lineTo(shoulder, shoulder)
                arcTo(
                    Rect(-radius, centerY - radius, radius, centerY + radius),
                    -45f,
                    270f,
                    false,
                )
            }
            EditingHandleShape.SelectionLeft -> {
                lineTo(-radius, 0f)
                arcTo(Rect(-2f * radius, 0f, 0f, 2f * radius), -90f, -270f, false)
            }
            EditingHandleShape.SelectionRight -> {
                lineTo(radius, 0f)
                arcTo(Rect(0f, 0f, 2f * radius, 2f * radius), -90f, 270f, false)
            }
        }
        close()
    }
