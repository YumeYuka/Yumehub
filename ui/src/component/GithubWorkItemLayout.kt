package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField

internal val GithubActivityAuthorAvatarSize = 34.dp

/** 卡片统计行的统一最小高度：有无参与者头像堆叠，行高都一致。 */
internal val GithubWorkItemMetaHeight = 24.dp

@Composable
internal fun GithubActivityCardSummary(
    title: String,
    bodyPreview: String?,
    titleStyle: TextStyle = githubWorkItemTitleStyle(),
    bodyStyle: TextStyle = GlasmioTheme.textStyles.footnote,
    titleMaxLines: Int = 2,
) {
    val colors = GlasmioTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = titleStyle,
            color = colors.content,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        GithubCardField(bodyPreview?.takeIf(String::isNotBlank)) { preview ->
            Text(
                preview,
                modifier = Modifier.padding(top = 4.dp),
                style = bodyStyle,
                fontWeight = FontWeight.Normal,
                color = colors.secondaryContent,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun githubWorkItemTitleStyle() =
    GlasmioTheme.textStyles.body.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    )
