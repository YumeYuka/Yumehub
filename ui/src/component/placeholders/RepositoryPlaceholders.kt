package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButtonDefaults
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import moe.yumeyuka.yumehub.ui.component.ActivityCardContent
import moe.yumeyuka.yumehub.ui.component.ActivityCardSpacing
import moe.yumeyuka.yumehub.ui.component.RepositoryCoverShape

/** 卡片内部内容：18dp 头像、标题、两行描述与三个统计槽。 */
@Composable
fun RepositoryCardPlaceholderContent(showCover: Boolean = false) {
    val styles = GlasmioTheme.textStyles
    ActivityCardContent(
        cover =
            if (showCover) {
                {
                    Spacer(
                        Modifier.fillMaxWidth()
                            .padding(bottom = ActivityCardSpacing)
                            .aspectRatio(1.91f)
                            .placeholderFill(RepositoryCoverShape),
                    )
                }
            } else null,
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvatarPlaceholder(18.dp)
                TextLinePlaceholder(
                    styles.body.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    fraction = 0.6f,
                )
            }
        },
        summary = {
            Column {
                repeat(2) { index ->
                    TextLinePlaceholder(
                        styles.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                        fraction = if (index == 0) 0.9f else 0.55f,
                    )
                }
            }
        },
        metadata = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextLinePlaceholder(
                    styles.footnote.copy(lineHeight = 20.sp),
                    Modifier.weight(1f),
                    0.5f,
                )
                TextLinePlaceholder(
                    styles.footnote.copy(lineHeight = 20.sp),
                    Modifier.weight(1f),
                    0.5f,
                )
                TextLinePlaceholder(
                    styles.footnote.copy(lineHeight = 20.sp),
                    Modifier.weight(1f),
                    0.8f,
                )
            }
        },
    )
}

/** 链接占位撑满余宽，星标和复刻占位贴右，与真实按钮的布局一致。 */
@Composable
fun RepositoryHeaderPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 20.dp, end = 2.dp, bottom = 2.dp)
            .height(GlasmioButtonDefaults.MediumHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillPlaceholder(Modifier.weight(1f))
        PillPlaceholder(Modifier.width(62.dp))
        PillPlaceholder(Modifier.width(62.dp))
    }
}

/** 入口卡占位：与真实分组同外壳，默认显示代码、议题、PR、发行版与「更多」。 */
@Composable
fun RepositoryEntryRowsPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(5) { index -> RepositoryEntryRowPlaceholder(showTopSeparator = index > 0) }
    }
}

@Composable
private fun RepositoryEntryRowPlaceholder(showTopSeparator: Boolean) {
    val horizontalPadding = GlasmioListRowDefaults.horizontalPadding
    Row(
        Modifier.fillMaxWidth()
            .height(PlaceholderTokens.ListRowHeight)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTilePlaceholder(28.dp)
        TextPlaceholder(
            modifier = Modifier.weight(1f),
            height = 13.dp,
            fraction = if (showTopSeparator) 0.42f else 0.3f,
        )
        TextPlaceholder(Modifier.width(20.dp), height = 11.dp, fraction = 1f)
    }
}

/** 元信息按钮的紧凑占位。 */
@Composable
private fun PillPlaceholder(modifier: Modifier = Modifier) {
    Spacer(modifier.height(28.dp).placeholderFill(RoundedCornerShape(14.dp)))
}
