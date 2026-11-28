package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.ActivityCard
import moe.yumeyuka.yumehub.ui.component.feedDetailTitleStyle
import moe.yumeyuka.yumehub.ui.component.repositoryCardBodyStyle
import moe.yumeyuka.yumehub.ui.component.repositoryCardHeadingStyle

/** 与活动头像和真实预览卡片同宽、同内边距，不再用窄小的普通列表行占位。 */
@Composable
fun FeedActivityPlaceholder() {
    val styles = GlasmioTheme.textStyles
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarPlaceholder(30.dp)
            TextLinePlaceholder(styles.footnote, Modifier.weight(1f), 0.7f)
        }
        ActivityCard(
            header = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarPlaceholder(18.dp)
                    TextLinePlaceholder(repositoryCardHeadingStyle(), fraction = 0.65f)
                }
            },
            summary = {
                Column {
                    TextLinePlaceholder(feedDetailTitleStyle(), fraction = 0.9f)
                    Spacer(Modifier.height(4.dp))
                    FeedPreviewPlaceholder()
                }
            },
            action = { TextLinePlaceholder(styles.body, fraction = 0.45f) },
        )
    }
}

@Composable
fun FeedPreviewPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(3) { index ->
            TextLinePlaceholder(
                repositoryCardBodyStyle(),
                fraction = if (index == 2) 0.55f else 0.94f,
            )
        }
    }
}
