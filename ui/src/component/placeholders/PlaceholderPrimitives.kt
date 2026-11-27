package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.githubAvatarShape

/** 与正文同形的静态色块。 */
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

/** 浅色和深色主题共用同一个内容色透明度。 */
@Composable
internal fun Modifier.placeholderFill(shape: Shape): Modifier =
    clip(shape).background(GlasmioTheme.colors.content.copy(alpha = PlaceholderTokens.ContentAlpha))
