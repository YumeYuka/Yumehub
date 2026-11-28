package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import moe.yumeyuka.yumehub.ui.theme.HomeColors

/** 首页及仓库入口共用的图标槽。 */
@Composable
fun EntryIcon(
    imageVector: ImageVector,
    background: Color = Color.Unspecified,
    filled: Boolean = false,
    tint: Color = HomeColors.icon,
) {
    Box(
        Modifier.size(28.dp)
            .then(
                if (background == Color.Unspecified) Modifier
                else Modifier.background(background, RoundedCornerShape(8.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            imageVector,
            contentDescription = null,
            size = if (background == Color.Unspecified) 23.dp else 16.dp,
            tint = tint,
            filled = filled,
        )
    }
}
