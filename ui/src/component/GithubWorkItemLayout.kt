package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme

internal val GithubActivityAuthorAvatarSize = 34.dp

@Composable
internal fun GithubActivityCardSummary(
    title: String,
    bodyPreview: String?,
    titleStyle: TextStyle = githubWorkItemTitleStyle(),
    bodyStyle: TextStyle = GlasmioTheme.textStyles.footnote,
) {
    val colors = GlasmioTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = titleStyle,
            color = colors.content,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        bodyPreview?.takeIf(String::isNotBlank)?.let { preview ->
            Text(
                preview,
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
