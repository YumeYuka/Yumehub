package moe.yumeyuka.yumehub.ui.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.*

@Composable
fun GithubSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescription: String = "发送",
) {
    val background = LocalGlassColors.current.accent.copy(alpha = 1f)
    GlasmioIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        style = GlassSurfaceStyle.Navigation,
        emphasis = if (enabled) GlassEmphasis.Prominent else GlassEmphasis.Regular,
        navigationSurfaceColor = background.takeIf { enabled },
        navigationSurfaceAlphaMultiplier = 1f,
        sampleBackdrop = false,
        size = size,
    ) {
        GithubIcon(
            Lucide.SendHorizontal,
            contentDescription = contentDescription,
            size = 20.dp,
            // 继承 Glasmio 按钮依据实际强调色提供的前景色，不自行固定黑白。
            tint = LocalContentColor.current,
        )
    }
}
