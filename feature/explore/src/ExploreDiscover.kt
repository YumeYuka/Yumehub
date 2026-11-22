package moe.yumeyuka.yumehub.feature.explore

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
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.theme.AccentPalette

@Composable
internal fun ExploreDiscoverSection(
    onOpenTrending: () -> Unit,
    onOpenFeatured: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Explore
    GithubSection(strings.Discover) {
        GlasmioListRow(
            title = strings.Trending,
            leading = { DiscoverIcon(Lucide.Flame, AccentPalette[4]) },
            showDisclosure = true,
            onClick = onOpenTrending,
        )
        GlasmioListRow(
            title = strings.Featured,
            leading = { DiscoverIcon(Lucide.LayoutGrid, AccentPalette[5]) },
            showDisclosure = true,
            onClick = onOpenFeatured,
        )
    }
}

@Composable
private fun DiscoverIcon(imageVector: ImageVector, color: Color) {
    Box(
        modifier = Modifier.size(28.dp).background(color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        GithubIcon(imageVector, contentDescription = null, size = 16.dp, tint = Color.White)
    }
}
