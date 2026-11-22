package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButtonDefaults
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import moe.yumeyuka.yumehub.ui.component.GithubActivityCardContent
import moe.yumeyuka.yumehub.ui.component.RepositoryCoverShape
import moe.yumeyuka.yumehub.ui.component.RepositoryLoadingCard

/** 列表里的仓库卡片占位：直接复用真实卡片外壳。 */
@Composable
fun RepositoryCardPlaceholder(modifier: Modifier = Modifier) {
    RepositoryLoadingCard(repository = null, modifier = modifier)
}

/** 卡片内部内容：18dp 头像、标题、两行描述与三个统计槽。 */
@Composable
fun RepositoryCardPlaceholderContent(showCover: Boolean = false) {
    val styles = GlasmioTheme.textStyles
    GithubActivityCardContent(
        cover =
            if (showCover) {
                {
                    Spacer(
                        Modifier.fillMaxWidth()
                            .aspectRatio(1.91f)
                            .background(
                                GlasmioTheme.colors.content.copy(
                                    alpha = PlaceholderTokens.ContentAlpha,
                                ),
                                RepositoryCoverShape,
                            ),
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

/** 仓库头部占位：两行简介加元信息胶囊，与真实头部共用同一段间距，不加卡片背景。 */
@Composable
fun RepositoryHeaderPlaceholder(modifier: Modifier = Modifier) {
    val styles = GlasmioTheme.textStyles
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextLinePlaceholder(styles.body, fraction = 0.92f)
            TextLinePlaceholder(styles.body, fraction = 0.58f)
        }
        // 上间距与真实胶囊一致，即使没有简介也保留。
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillPlaceholder(Modifier.weight(1f))
            PillPlaceholder(Modifier.width(74.dp))
            PillPlaceholder(Modifier.width(74.dp))
        }
    }
}

/** 入口卡占位：与真实分组同外壳，默认显示代码、议题、PR、发行版与「更多」。 */
@Composable
fun RepositoryEntryRowsPlaceholder(modifier: Modifier = Modifier) {
    GlasmioGroupedList(modifier = modifier) {
        Column {
            repeat(5) { index -> RepositoryEntryRowPlaceholder(showTopSeparator = index > 0) }
        }
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

/** 玻璃胶囊占位：高度与 GlasmioButton Medium 一致，宽度由调用方约束。 */
@Composable
private fun PillPlaceholder(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(GlasmioButtonDefaults.MediumHeight)
            .clip(RoundedCornerShape(17.dp))
            .background(GlasmioTheme.colors.content.copy(alpha = PlaceholderTokens.ContentAlpha)),
    )
}
