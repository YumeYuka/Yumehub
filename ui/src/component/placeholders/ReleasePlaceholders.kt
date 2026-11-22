package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator

@Composable
fun ReleaseMetadataPlaceholder(modifier: Modifier = Modifier) {
    GlasmioGroupedList(modifier = modifier) {
        ReleasePlaceholderRow(68.dp, false) {
            AvatarPlaceholder(32.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextPlaceholder(height = 15.dp, fraction = 0.46f)
                TextPlaceholder(height = 11.dp, fraction = 0.28f)
            }
        }
        ReleasePlaceholderRow(PlaceholderTokens.ListRowHeight, true) {
            IconTilePlaceholder(23.dp)
            Column(Modifier.weight(1f)) { TextPlaceholder(Modifier.width(40.dp), fraction = 1f) }
            TextPlaceholder(Modifier.width(76.dp), fraction = 1f)
        }
    }
}

@Composable
fun ReleaseVersionPlaceholder(showTopSeparator: Boolean) {
    ReleasePlaceholderRow(68.dp, showTopSeparator) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextPlaceholder(height = 15.dp, fraction = 0.53f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextPlaceholder(Modifier.width(56.dp), height = 11.dp, fraction = 1f)
                TextPlaceholder(Modifier.width(44.dp), height = 11.dp, fraction = 1f)
            }
        }
    }
}

@Composable
fun ReleaseAssetPlaceholder(showTopSeparator: Boolean) {
    ReleasePlaceholderRow(68.dp, showTopSeparator) {
        IconTilePlaceholder(23.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextPlaceholder(height = 15.dp, fraction = 0.72f)
            TextPlaceholder(height = 11.dp, fraction = 0.23f)
        }
        TextPlaceholder(Modifier.width(20.dp), height = 5.dp, fraction = 1f)
    }
}

@Composable
private fun ReleasePlaceholderRow(
    height: Dp,
    separator: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val inset = GlasmioListRowDefaults.horizontalPadding
    Row(
        Modifier.fillMaxWidth()
            .height(height)
            .padding(horizontal = inset)
            .glasmioGroupedListSeparator(GlasmioTheme.colors.separator, visible = separator),
        horizontalArrangement = Arrangement.spacedBy(inset),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
