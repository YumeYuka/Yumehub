package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonDefaults
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Tag
import moe.yumeyuka.yumehub.ui.theme.readableForeground

/** 可操作标签沿用原生小按钮，内容与只读标签共用排版。 */
@Composable
fun GithubLabelChip(
    name: String,
    color: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlasmioButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        sampleBackdrop = false,
        size = GlasmioButtonSize.Small,
    ) {
        GithubLabelChipContent(name, color)
    }
}

/** 列表与详情共用的只读标签；复用 Glasmio 材质，不创建按钮点击语义。 */
@Composable
fun GithubLabelTag(name: String, color: String?, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier.heightIn(min = GlasmioButtonDefaults.SmallHeight),
        shape = Capsule(),
        sampleBackdrop = false,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GithubLabelChipContent(name, color)
        }
    }
}

@Composable
private fun RowScope.GithubLabelChipContent(name: String, color: String?) {
    val textStyle = GlasmioTheme.textStyles.footnote
    val iconSize = with(LocalDensity.current) { textStyle.fontSize.toDp() }
    val colors = GlasmioTheme.colors
    val labelColor =
        readableForeground(githubLabelColor(color), colors.elevatedBackground, colors.content)
    GithubIcon(Lucide.Tag, contentDescription = null, size = iconSize, tint = labelColor)
    Text(
        text = name,
        modifier = Modifier.weight(1f, fill = false),
        style = textStyle,
        color = colors.content,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun githubLabelColor(color: String?): Color =
    color
        ?.takeIf { value ->
            value.length == 6 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
        ?.toLongOrNull(16)
        ?.let { Color((it or 0xFF000000).toInt()) } ?: GlasmioTheme.colors.secondaryContent
