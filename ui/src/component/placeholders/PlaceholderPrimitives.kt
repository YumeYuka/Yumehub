package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.githubAvatarShape
import moe.yumeyuka.yumehub.ui.component.skeleton.LocalPlaceholderSweep
import moe.yumeyuka.yumehub.ui.component.skeleton.LocalSkeletonShimmerEnabled
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonTiming
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled

/** 与正文同形的色块：只画位置，一条光带扫过整屏占位，不闪烁，也不出现又消失的加载行。 */
@Composable
fun TextPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    fraction: Float = 0.6f,
    corner: Dp = PlaceholderTokens.BlockCorner,
) {
    Spacer(
        modifier.fillMaxWidth(fraction).height(height).placeholderFill(RoundedCornerShape(corner)),
    )
}

/** 行槽高度来自正文样式，内部色块只占字面高度。 */
@Composable
fun TextLinePlaceholder(
    style: TextStyle,
    modifier: Modifier = Modifier,
    fraction: Float = 0.6f,
) {
    val density = LocalDensity.current
    Box(
        modifier.fillMaxWidth().height(with(density) { style.lineHeight.toDp() }),
        contentAlignment = Alignment.CenterStart,
    ) {
        TextPlaceholder(
            height = with(density) { style.fontSize.toDp() * 0.7f },
            fraction = fraction,
        )
    }
}

/** 头像占位与真实头像共用轮廓，组织圆角随尺寸缩放。 */
@Composable
fun AvatarPlaceholder(size: Dp, modifier: Modifier = Modifier, type: String? = null) {
    Spacer(modifier.size(size).placeholderFill(githubAvatarShape(type, size)))
}

/** 列表行左侧的图标底块，尺寸与真实图标容器一致。 */
@Composable
fun IconTilePlaceholder(size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Spacer(modifier.size(size).placeholderFill(RoundedCornerShape(corner)))
}

/** 占位底色加一条线性扫光。底色与高光都取自内容色，只差透明度，所以浅色与深色主题共用； 相位在绘制阶段读取，动画不触发重组。 */
@Composable
internal fun Modifier.placeholderFill(shape: Shape): Modifier {
    val base = GlasmioTheme.colors.content.copy(alpha = PlaceholderTokens.ContentAlpha)
    if (!LocalGithubCardMotionEnabled.current || !LocalSkeletonShimmerEnabled.current)
        return clip(shape).background(base)
    val sweep = LocalPlaceholderSweep.current ?: rememberFallbackSweep()
    val highlight = GlasmioTheme.colors.content.copy(alpha = PlaceholderTokens.HighlightAlpha)
    return clip(shape).drawWithCache {
        val band = size.width.coerceAtLeast(1f)
        onDrawBehind {
            val start = -band + (size.width + band) * sweep.value
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + band, 0f),
                ),
            )
        }
    }
}

/** 没有共享宿主的零散占位自建时钟；列表页由 skeleton 包的 SkeletonShimmerHost 统一提供。 */
@Composable
private fun rememberFallbackSweep(): State<Float> {
    val clock = rememberInfiniteTransition(label = "placeholder-shimmer")
    return clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = SkeletonTiming.ShimmerSpec,
                repeatMode = RepeatMode.Restart,
            ),
        label = "placeholder-sweep",
    )
}
