package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator

/** 单标题列表行的内容占位；由调用方保留分组外壳。 */
@Composable
fun TitleRowPlaceholder(showTopSeparator: Boolean = false) {
    Row(
        Modifier.fillMaxWidth()
            .height(PlaceholderTokens.ListRowHeight)
            .padding(horizontal = GlasmioListRowDefaults.horizontalPadding)
            .glasmioGroupedListSeparator(GlasmioTheme.colors.separator, visible = showTopSeparator),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextLinePlaceholder(GlasmioTheme.textStyles.body)
    }
}

/** 标题／副标题列表行占位：与 GlasmioListRow 的 68dp 双行槽一致。 */
@Composable
fun ListRowPlaceholder(modifier: Modifier = Modifier) {
    val styles = GlasmioTheme.textStyles
    val horizontalPadding = GlasmioListRowDefaults.horizontalPadding
    GlasmioGroupedList(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextLinePlaceholder(styles.body, fraction = 0.62f)
                TextLinePlaceholder(styles.footnote, fraction = 0.4f)
            }
            TextPlaceholder(Modifier.width(28.dp), height = 12.dp, fraction = 1f)
        }
    }
}

/** 正文覆盖占位：只画行块，外壳由 `ReadingGroup` 提供。 */
@Composable
fun ReadingTextPlaceholder(modifier: Modifier = Modifier, lines: Int = 5) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(lines) { index ->
            val fraction =
                when (index % 4) {
                    0 -> 0.92f
                    1 -> 0.78f
                    2 -> 0.86f
                    else -> 0.48f
                }
            TextPlaceholder(height = 12.dp, fraction = fraction)
        }
    }
}
