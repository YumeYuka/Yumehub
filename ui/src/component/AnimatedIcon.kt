package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGroupedListIconColor

/** Lucide 图标状态变化时复用探索页星标操作的淡入与弹性缩放动画。 */
@Composable
fun AnimatedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 23.dp,
    filled: Boolean = false,
    tint: Color = LocalGroupedListIconColor.current ?: GlasmioTheme.colors.content,
) {
    AnimatedContent(
        targetState = imageVector to filled,
        transitionSpec = {
            (fadeIn(tween(200)) +
                    scaleIn(
                        initialScale = 0.55f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
                    ))
                .togetherWith(
                    fadeOut(tween(120)) + scaleOut(targetScale = 1.35f, animationSpec = tween(120)),
                )
        },
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
        label = "github-icon",
    ) { (currentImage, currentFilled) ->
        AppIcon(
            imageVector = currentImage,
            contentDescription = contentDescription,
            size = size,
            filled = currentFilled,
            tint = tint,
        )
    }
}
