package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.RepositoryCardPlaceholderContent
import moe.yumeyuka.yumehub.ui.theme.githubLanguageColor

/** 小头像标识仓库归属，名称为主信息，描述和统计使用次要色。 */
@Composable
fun RepositoryRow(
    repository: RepositoryDto,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    contributorsCount: Int? = null,
    compact: Boolean = false,
    showTopSeparator: Boolean = false,
    showCover: Boolean = true,
) {
    if (compact) {
        CompactRepositoryRow(repository, onClick, showTopSeparator)
        return
    }
    RepositoryLoadingCard(repository, onClick, action, contributorsCount, showCover = showCover)
}

/** 仓库占位与正文复用同一个卡片，描述按实际行数自然变化。 */
@Composable
fun RepositoryLoadingCard(
    repository: RepositoryDto?,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    contributorsCount: Int? = null,
    modifier: Modifier = Modifier,
    showCoverPlaceholder: Boolean = false,
    showCover: Boolean = true,
) {
    GithubLoadingCard(
        loading = repository == null,
        modifier = modifier,
        placeholder = { RepositoryCardPlaceholderContent(showCover && showCoverPlaceholder) },
    ) {
        if (repository != null) {
            RepositoryCardContent(repository, onClick, action, contributorsCount, showCover)
        }
    }
}

@Composable
private fun RepositoryCardContent(
    repository: RepositoryDto,
    onClick: (() -> Unit)?,
    action: (@Composable () -> Unit)?,
    contributorsCount: Int?,
    showCover: Boolean,
) {
    val colors = GlasmioTheme.colors
    val textStyles = GlasmioTheme.textStyles
    GithubActivityCardContent(
        cover = if (showCover) ({ GithubRepositoryCover(repository.openGraphImageUrl) }) else null,
        onClick = onClick,
        action = action,
        header = {
            Column(Modifier.fillMaxWidth()) {
                RepositoryCardHeading(repository)
                GithubCardField(repository.description?.takeIf(String::isNotBlank)) { description ->
                    Text(
                        description,
                        modifier = Modifier.padding(top = 14.dp),
                        style = repositoryCardBodyStyle(),
                        color = colors.secondaryContent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
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
                GithubCardField(repository.stargazersCount) { stars ->
                    Stat(Lucide.Star, stars.toString(), "星标 $stars")
                }
                GithubCardField(contributorsCount ?: repository.forksCount) { count ->
                    if (contributorsCount != null)
                        Stat(Lucide.UserRound, "$count 个贡献者", "$count 个贡献者")
                    else Stat(Lucide.BookCopy, count.toString(), "派生 $count")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    GithubCardField(repository.language?.takeIf(String::isNotBlank)) { language ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size(9.dp)
                                    .clip(CircleShape)
                                    .background(githubLanguageColor(language)),
                            )
                            Text(
                                language,
                                style =
                                    textStyles.footnote.copy(fontSize = 15.sp, lineHeight = 20.sp),
                                color = colors.secondaryContent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun RepositoryCardHeading(
    repository: RepositoryDto?,
    fallbackFullName: String? = null,
    number: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val owner = repository?.owner
    val name =
        (repository?.fullName ?: fallbackFullName ?: repository?.name.orEmpty()).replace("/", " / ")
    val style = repositoryCardHeadingStyle()
    val color = GlasmioTheme.colors.content
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GithubAvatar(owner?.avatarUrl, owner?.login, type = owner?.type, size = 18.dp)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f, fill = false),
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            number?.let { Text("#$it", style = style, color = color, maxLines = 1) }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun repositoryCardHeadingStyle() =
    GlasmioTheme.textStyles.body.copy(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    )

@Composable
internal fun repositoryCardBodyStyle() =
    GlasmioTheme.textStyles.body.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    )

@Composable
private fun Stat(icon: ImageVector, value: String, description: String) {
    val colors = GlasmioTheme.colors
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GithubIcon(icon, contentDescription = null, size = 17.dp, tint = colors.secondaryContent)
        Text(
            value,
            style = GlasmioTheme.textStyles.footnote.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = colors.secondaryContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 首页收藏：原生双行列表负责文本、头像、箭头、分隔线与点击反馈。 */
@Composable
private fun CompactRepositoryRow(
    repository: RepositoryDto,
    onClick: (() -> Unit)?,
    showTopSeparator: Boolean,
) {
    val owner =
        repository.owner?.login?.takeIf { it.isNotBlank() }
            ?: repository.fullName?.substringBefore('/')?.takeIf { it.isNotBlank() }
    GithubListRow(
        title =
            repository.name?.takeIf { it.isNotBlank() }
                ?: repository.fullName?.substringAfter('/')?.takeIf { it.isNotBlank() }
                ?: "未命名仓库",
        subtitle = owner,
        leading = {
            GithubAvatar(
                url = repository.owner?.avatarUrl,
                login = owner,
                type = repository.owner?.type,
                size = 32.dp,
            )
        },
        onClick = onClick,
        showTopSeparator = showTopSeparator,
    )
}
