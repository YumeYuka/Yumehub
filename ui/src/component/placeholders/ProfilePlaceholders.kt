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
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** 用户卡片的行槽：48dp 头像加名称、账号与简介三行。 */
@Composable
fun UserRowPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.45f)
            TextLinePlaceholder(GlasmioTheme.textStyles.footnote, fraction = 0.3f)
            TextLinePlaceholder(GlasmioTheme.textStyles.footnote, fraction = 0.7f)
        }
    }
}

/** 个人页顶部三个操作卡：图标与单行标签居中。 */
@Composable
fun ProfileActionPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AvatarPlaceholder(23.dp)
        TextLinePlaceholder(
            GlasmioTheme.textStyles.footnote.copy(fontSize = 12.sp),
            fraction = 0.6f,
        )
    }
}

/** 资料卡沿用 60dp 行高，图标、文本列与分隔线位置与真实资料一致。 */
@Composable
fun ProfileDetailsPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(3) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(60.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarPlaceholder(22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextLinePlaceholder(
                        GlasmioTheme.textStyles.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        fraction = if (index == 0) 0.64f else 0.45f,
                    )
                    if (index < 2)
                        TextLinePlaceholder(
                            GlasmioTheme.textStyles.footnote.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            ),
                            fraction = 0.28f,
                        )
                }
            }
        }
    }
}

/** 主页列表入口卡：28dp 图标块、标题与行末计数。 */
@Composable
fun ProfileCollectionsPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTilePlaceholder(28.dp)
                TextLinePlaceholder(
                    GlasmioTheme.textStyles.body,
                    modifier = Modifier.weight(1f),
                    fraction = 0.4f,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextPlaceholder(Modifier.width(24.dp), height = 14.dp, fraction = 1f)
                    TextPlaceholder(Modifier.width(8.dp), height = 14.dp, fraction = 1f)
                }
            }
        }
    }
}

/** 组织列表行：40dp 方形头像加名称、账号两行。 */
@Composable
fun OrganizationRowPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(40.dp, type = "Organization")
        Column(Modifier.weight(1f)) {
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.45f)
            TextLinePlaceholder(GlasmioTheme.textStyles.subheadline, fraction = 0.3f)
        }
    }
}

/** README 请求阶段与正文首帧共用同形骨架，外壳由 `ReadingGroup` 提供。 */
@Composable
fun ReadmePlaceholder(modifier: Modifier = Modifier) {
    MarkdownPlaceholder(modifier.padding(16.dp), compact = true)
}
