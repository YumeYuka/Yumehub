package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/** 比真实 28dp 小按钮略窄、略矮，避免占位块看起来比标签还重。 */
private val WorkHeaderChipWidths = listOf(68.dp, 52.dp, 84.dp, 60.dp, 72.dp)

/** 议题／拉取请求标题下的属性卡占位：用胶囊块保留状态与多行标签的真实轮廓。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkHeaderPlaceholder() {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkHeaderChipWidths.forEach { width ->
            Spacer(Modifier.width(width).height(24.dp).placeholderFill(CircleShape))
        }
    }
}
