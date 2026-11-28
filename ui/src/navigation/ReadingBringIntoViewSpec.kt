package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.gestures.BringIntoViewSpec

/** 仅把可阅读区域换算到上游坐标，滚动、动画及选区仍由 Foundation 管理。 */
internal class ReadingBringIntoViewSpec(
    private val upstream: BringIntoViewSpec,
    private val topPaddingPx: () -> Float,
    private val bottomPaddingPx: () -> Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val top = topPaddingPx()
        val bottom = bottomPaddingPx()
        val usableSize = containerSize - top - bottom
        val readableOffset = offset - top
        if (
            !offset.isFinite() ||
                !size.isFinite() ||
                size < 0f ||
                !containerSize.isFinite() ||
                !top.isFinite() ||
                top < 0f ||
                !bottom.isFinite() ||
                bottom < 0f ||
                !usableSize.isFinite() ||
                usableSize <= 0f ||
                !readableOffset.isFinite()
        ) {
            // 无有效阅读区域时无法完成定位，不向上游伪造可用尺寸或发起滚动。
            return 0f
        }
        return upstream.calculateScrollDistance(readableOffset, size, usableSize)
    }
}
