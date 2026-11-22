package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.GithubWorkStatusTokens

object NotificationRowTokens {
    // 通知卡与议题／拉取请求卡共用同一正文层级，状态图标对齐标题首行。
    val TitleSize = 16.sp
    val TitleLineHeight = 22.sp
    val MetadataSize = 13.sp
    val MetadataLineHeight = 18.sp
    val AvatarSize = 16.dp
    val ContentGap = 10.dp
}

/** 通知行占位：左侧状态图标，右侧标题、仓库与时间、操作者与摘要。 */
@Composable
fun NotificationRowPlaceholder(modifier: Modifier = Modifier) {
    val styles = GlasmioTheme.textStyles
    val metadata =
        styles.footnote.copy(
            fontSize = NotificationRowTokens.MetadataSize,
            lineHeight = NotificationRowTokens.MetadataLineHeight,
        )
    val titleStyle =
        styles.body.copy(
            fontSize = NotificationRowTokens.TitleSize,
            lineHeight = NotificationRowTokens.TitleLineHeight,
            fontWeight = FontWeight.Medium,
        )
    val statusSlotSize =
        maxOf(
            GithubWorkStatusTokens.IconSize,
            with(LocalDensity.current) { titleStyle.lineHeight.toDp() },
        )
    Box(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NotificationRowTokens.ContentGap),
            ) {
                Box(
                    Modifier.width(statusSlotSize).height(statusSlotSize),
                    contentAlignment = Alignment.Center,
                ) {
                    AvatarPlaceholder(GithubWorkStatusTokens.IconSize)
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Column {
                        TextLinePlaceholder(titleStyle, fraction = 0.92f)
                        TextLinePlaceholder(titleStyle, fraction = 0.48f)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextLinePlaceholder(metadata, Modifier.weight(1f), 0.72f)
                        TextPlaceholder(Modifier.width(30.dp), height = 11.dp, fraction = 1f)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AvatarPlaceholder(NotificationRowTokens.AvatarSize)
                        TextLinePlaceholder(metadata, Modifier.weight(1f), 0.78f)
                    }
                }
            }
        }
    }
}

/** 通知摘要占位：与摘要正文共用头像、行高和横向间距。 */
@Composable
fun NotificationPreviewPlaceholder(modifier: Modifier = Modifier) {
    val metadataStyle =
        GlasmioTheme.textStyles.footnote.copy(
            fontSize = NotificationRowTokens.MetadataSize,
            lineHeight = NotificationRowTokens.MetadataLineHeight,
        )
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AvatarPlaceholder(NotificationRowTokens.AvatarSize)
        TextLinePlaceholder(metadataStyle, Modifier.weight(1f), fraction = 0.78f)
    }
}
