package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** 文件列表沿用真实文件头的 48dp 行槽，卡片外壳由调用方保留。 */
@Composable
fun DiffFileHeaderPlaceholder() {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTilePlaceholder(16.dp, corner = 2.dp)
        Spacer(Modifier.width(16.dp))
        TextLinePlaceholder(GlasmioTheme.textStyles.body, Modifier.weight(1f), fraction = 0.62f)
        Box(Modifier.width(64.dp)) {
            TextLinePlaceholder(GlasmioTheme.textStyles.subheadline, fraction = 0.85f)
        }
    }
}

/** 解析／高亮期间使用与代码相同的文字行槽，不用加载文案占位。 */
@Composable
fun DiffCodePlaceholder(
    style: TextStyle,
    modifier: Modifier = Modifier,
    gutterWidth: Dp = 88.dp,
    rowCount: Int = 6,
) {
    Column(modifier.fillMaxWidth()) {
        repeat(rowCount) { index ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Box(Modifier.width(gutterWidth)) { TextLinePlaceholder(style, fraction = 0.7f) }
                TextLinePlaceholder(
                    style,
                    modifier = Modifier.weight(1f),
                    fraction = if (index % 3 == 0) 0.9f else 0.58f,
                )
            }
        }
    }
}
