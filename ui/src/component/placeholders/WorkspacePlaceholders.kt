package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator

/** 提交卡占位：上方单行提交说明，下方 24dp 头像、提交人与右侧时间。 */
@Composable
fun WorkspaceCommitPlaceholder(modifier: Modifier = Modifier) {
    val styles = GlasmioTheme.textStyles
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextLinePlaceholder(styles.body, fraction = 0.85f)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarPlaceholder(24.dp)
            TextLinePlaceholder(styles.body, Modifier.weight(1f), 0.35f)
            Box(Modifier.width(56.dp)) { TextPlaceholder(height = 10.dp, fraction = 0.8f) }
        }
    }
}

/** 目录条目占位：20dp 图标、名称与右侧更新时间。 */
@Composable
fun WorkspacePlaceholderRow(showTopSeparator: Boolean) {
    val horizontalPadding = GlasmioListRowDefaults.horizontalPadding
    Row(
        Modifier.fillMaxWidth()
            .height(PlaceholderTokens.ListRowHeight)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTilePlaceholder(20.dp, corner = 4.dp)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f)
                .fillMaxHeight()
                .glasmioGroupedListSeparator(
                    color = GlasmioTheme.colors.separator,
                    visible = showTopSeparator,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextPlaceholder(
                modifier = Modifier.weight(1f),
                height = 12.dp,
                fraction = 0.45f,
            )
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(64.dp)) { TextPlaceholder(height = 10.dp, fraction = 0.75f) }
        }
    }
}
