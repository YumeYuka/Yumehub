package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField

@Composable
fun GithubCommitSummary(
    message: String,
    author: String,
    avatarUrl: String?,
    time: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            style = GlasmioTheme.textStyles.body,
            color = GlasmioTheme.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GithubAvatar(avatarUrl, author, size = 24.dp)
            Text(
                author,
                Modifier.weight(1f),
                style = GlasmioTheme.textStyles.body,
                color = GlasmioTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GithubCardField(time) {
                Text(
                    it,
                    style = GlasmioTheme.textStyles.footnote,
                    color = GlasmioTheme.colors.secondaryContent,
                    maxLines = 1,
                )
            }
        }
    }
}
