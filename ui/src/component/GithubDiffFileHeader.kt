package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.File
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto

@Composable
internal fun GithubDiffFileHeader(
    file: PullRequestFileDto,
    modifier: Modifier = Modifier,
) {
    val colors = GlasmioTheme.colors
    val filename = file.filename.substringAfterLast('/')
    val directory = file.filename.substringBeforeLast('/', "")
    val title = if (directory.isEmpty()) filename else "$filename  /$directory"
    val diffColors = githubDiffColors(colors.isDark)

    GlasmioListRow(
        title = title,
        modifier = modifier.height(48.dp),
        systemName = null,
        showTopSeparator = false,
        leading = {
            GithubIcon(
                Lucide.File,
                contentDescription = null,
                size = 16.dp,
                tint = colors.secondaryContent,
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "+${file.additions}",
                    style = GlasmioTheme.textStyles.subheadline,
                    color = diffColors.additionForeground,
                    maxLines = 1,
                )
                Text(
                    "-${file.deletions}",
                    style = GlasmioTheme.textStyles.subheadline,
                    color = diffColors.deletionForeground,
                    maxLines = 1,
                )
            }
        },
    )
}
