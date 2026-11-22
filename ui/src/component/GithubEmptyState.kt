package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme

@Composable
fun GithubEmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    verticalBias: Float = -0.1f,
    iconContainerSize: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    iconContainerColor: Color = GlasmioTheme.colors.container,
) {
    val colors = GlasmioTheme.colors
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = BiasAlignment(0f, verticalBias),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier.size(iconContainerSize).background(iconContainerColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GithubIcon(
                    icon,
                    contentDescription = null,
                    size = iconSize,
                    tint = colors.content,
                )
            }
            Text(
                text = title,
                style = GlasmioTheme.textStyles.subheadline,
                color = colors.content,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = GlasmioTheme.textStyles.footnote,
                    color = colors.secondaryContent,
                )
            }
        }
    }
}
