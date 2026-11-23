package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.GithubStrings_Repository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubEntryIcon
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder
import moe.yumeyuka.yumehub.ui.theme.GithubHomeColors

@Composable
internal fun RepositoryHeaderDescription(description: String) {
    Text(
        text = description,
        style = GlasmioTheme.textStyles.body.copy(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = GlasmioTheme.colors.secondaryContent,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun RepositoryHeaderCard(
    repository: RepositoryDto,
    state: RepositoryOverviewState,
    enabled: Boolean,
    onToggleStar: () -> Unit,
    onFork: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current.Repository
    val overview = state.overview
    // 链接优先取仓库设置里的网站地址，未填写时回落到仓库自身的 GitHub 地址。
    val link =
        overview?.homepage?.takeIf(String::isNotBlank)
            ?: repository.fullName?.takeIf(String::isNotBlank)?.let { "https://github.com/$it" }
    // 计数缺失时保持未知，不补 0；胶囊内先放同字号的占位块。
    val stars = overview?.stars ?: repository.stargazersCount
    val forks = overview?.forks ?: repository.forksCount
    val starred = overview?.starred == true
    RepositoryMetadataBar(
        link,
        starred,
        stars,
        forks,
        strings,
        enabled,
        onToggleStar,
        onFork,
        onOpenLink,
        // 星标请求进行中只禁用这颗胶囊，其余入口保持可点。
        starEnabled = enabled && !state.busy,
    )
}

/** 仓库设置里的网站地址转成展示用的主机名，去掉协议与末尾斜杠。 */
private fun metadataHost(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/')

/** 元信息胶囊：网站链接、标星与复刻计数，玻璃按钮材质与顶栏操作一致。 */
@Composable
private fun RepositoryMetadataBar(
    link: String?,
    starred: Boolean,
    stars: Int?,
    forks: Int?,
    strings: GithubStrings_Repository,
    enabled: Boolean,
    onToggleStar: () -> Unit,
    onFork: () -> Unit,
    onOpenLink: (String) -> Unit,
    starEnabled: Boolean,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            // 两侧预留外描边空间，避免加载容器的尺寸动画裁掉胶囊边缘。
            // 上间距固定，即使没有简介也保留。
            .padding(start = 2.dp, top = 20.dp, end = 2.dp, bottom = 2.dp),
    ) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = GlasmioTheme.textStyles.footnote.copy(fontWeight = FontWeight.Bold)
        val linkHost = link?.let(::metadataHost)
        // 与 GlasmioButton Medium 的内部间距保持一致，用来估算胶囊实际占用宽度。
        val pillHorizontalPadding = 12.dp
        val iconSize = 14.dp
        val iconTextGap = 4.dp
        val pillGap = 8.dp
        fun textWidth(text: String): Dp =
            with(density) { textMeasurer.measure(text, labelStyle, maxLines = 1).size.width.toDp() }
        fun pillWidth(text: String, iconCount: Int = 1): Dp =
            pillHorizontalPadding * 2 + (iconSize + iconTextGap) * iconCount + textWidth(text)
        // 未知计数按两位数字预留宽度，数字到达后胶囊宽度不变。
        val unknownDigits = "00"
        val starValue = stars?.toString()
        val forkValue = forks?.toString()
        val starLabel = stars?.let { "$it ${strings.Stars}" }
        val forkLabel = forks?.let { "$it ${strings.Forks}" }
        val gapCount = if (linkHost != null) 2 else 1
        // 链接优先：完整保留域名后仍放得下带文字的统计胶囊才显示文字，否则只留数字。
        val showLabels =
            (linkHost?.let { pillWidth(it) } ?: 0.dp) +
                pillGap * gapCount +
                pillWidth(starLabel ?: "$unknownDigits ${strings.Stars}") +
                pillWidth(forkLabel ?: "$unknownDigits ${strings.Forks}") <= maxWidth
        val starDisplay = if (showLabels) starLabel else starValue
        val forkDisplay = if (showLabels) forkLabel else forkValue
        val starPlaceholderWidth =
            textWidth(if (showLabels) "$unknownDigits ${strings.Stars}" else unknownDigits)
        val forkPlaceholderWidth =
            textWidth(if (showLabels) "$unknownDigits ${strings.Forks}" else unknownDigits)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // 胶囊依次靠在一起，不做两端撑开。
            horizontalArrangement = Arrangement.spacedBy(pillGap),
        ) {
            if (link != null && linkHost != null) {
                GlasmioButton(
                    onClick = { onOpenLink(link) },
                    enabled = enabled,
                    size = GlasmioButtonSize.Medium,
                    // 撑满剩余宽度，统计胶囊紧贴其后；域名过长时在胶囊内截断。
                    modifier = Modifier.weight(1f),
                ) {
                    GithubIcon(
                        Lucide.Link,
                        contentDescription = null,
                        size = iconSize,
                    )
                    Text(
                        linkHost,
                        style = labelStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(pillGap),
            ) {
                GlasmioButton(
                    onClick = onToggleStar,
                    enabled = starEnabled,
                    size = GlasmioButtonSize.Medium,
                ) {
                    GithubIcon(
                        Lucide.Star,
                        contentDescription = null,
                        size = iconSize,
                        tint = GithubHomeColors.starred,
                        filled = starred,
                    )
                    if (starDisplay == null) {
                        TextPlaceholder(
                            modifier = Modifier.width(starPlaceholderWidth),
                            height = 12.dp,
                            fraction = 1f,
                        )
                    } else {
                        Text(starDisplay, style = labelStyle, maxLines = 1)
                    }
                }
                GlasmioButton(
                    onClick = onFork,
                    enabled = enabled,
                    size = GlasmioButtonSize.Medium,
                ) {
                    GithubIcon(
                        Lucide.BookCopy,
                        contentDescription = strings.Forks,
                        size = iconSize,
                    )
                    if (forkDisplay == null) {
                        TextPlaceholder(
                            modifier = Modifier.width(forkPlaceholderWidth),
                            height = 12.dp,
                            fraction = 1f,
                        )
                    } else {
                        Text(forkDisplay, style = labelStyle, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RepositoryEntryCard(
    repository: RepositoryDto,
    state: RepositoryOverviewState,
    enabled: Boolean,
    onBrowseFiles: () -> Unit,
    onOpenIssues: (() -> Unit)?,
    onOpenPullRequests: (() -> Unit)?,
    onExternal: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Repository
    val overview = state.overview
    val hasDiscussions = overview?.discussionsEnabled == true
    val hasMoreEntries =
        overview != null &&
            (overview.hasProjects ||
                overview.hasWorkflows ||
                hasDiscussions ||
                overview.hasContributors ||
                overview.watchers > 0 ||
                overview.license != null)
    val open = moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler()
    fun openActivity(path: String) {
        repository.fullName?.let { open("https://github.com/$it/$path") }
    }
    var expanded by rememberSaveable(repository.fullName) { mutableStateOf(false) }
    GlasmioGroupedList {
        Column(Modifier.animateContentSize()) {
            RepositoryEntry(
                strings.Code,
                Lucide.CodeXml,
                GithubHomeColors.repositories,
                detail = repository.defaultBranch,
                separator = false,
                enabled = enabled,
                onClick = onBrowseFiles,
            )
            if (overview != null && overview.issuesEnabled && onOpenIssues != null)
                RepositoryEntry(
                    strings.Issues,
                    Lucide.CircleDot,
                    GithubHomeColors.issues,
                    detail = overview.issues.toString(),
                    enabled = enabled,
                    onClick = onOpenIssues,
                )
            if (overview != null && onOpenPullRequests != null)
                RepositoryEntry(
                    strings.PullRequests,
                    Lucide.GitPullRequest,
                    GithubHomeColors.pullRequests,
                    detail = overview.pullRequests.toString(),
                    enabled = enabled,
                    onClick = onOpenPullRequests,
                )
            if (overview != null && overview.releases > 0)
                RepositoryEntry(
                    strings.Releases,
                    Lucide.Tag,
                    GithubHomeColors.repositories,
                    detail = overview.releases.toString(),
                    enabled = enabled,
                    onClick = { onExternal("releases") },
                )
            if (overview != null && expanded) {
                if (overview.hasProjects)
                    RepositoryEntry(
                        strings.Projects,
                        Lucide.Columns3,
                        GithubHomeColors.projects,
                        enabled = enabled,
                        onClick = { openActivity("projects") },
                    )
                if (overview.hasWorkflows)
                    RepositoryEntry(
                        strings.ActionsTitle,
                        Lucide.CirclePlay,
                        GithubHomeColors.starred,
                        enabled = enabled,
                        onClick = { openActivity("actions") },
                    )
                if (hasDiscussions)
                    RepositoryEntry(
                        strings.Discussions,
                        Lucide.MessagesSquare,
                        GithubHomeColors.discussions,
                        detail = overview.discussions.toString(),
                        enabled = enabled,
                        onClick = { openActivity("discussions") },
                    )
                if (overview.hasContributors)
                    RepositoryEntry(
                        strings.Contributors,
                        Lucide.UsersRound,
                        GithubHomeColors.contributors,
                        enabled = enabled,
                        onClick = { openActivity("graphs/contributors") },
                    )
                if (overview.watchers > 0)
                    RepositoryEntry(
                        strings.Watchers,
                        Lucide.Eye,
                        GithubHomeColors.watchers,
                        detail = overview.watchers.toString(),
                        enabled = enabled,
                        onClick = { openActivity("watchers") },
                    )
                // 没有声明许可证时不显示该行。
                overview.license?.let { license ->
                    RepositoryEntry(
                        strings.License,
                        Lucide.Scale,
                        GithubHomeColors.license,
                        detail = license,
                        enabled = enabled,
                        onClick = onOpenLicense,
                    )
                }
            } else if (hasMoreEntries) {
                // 展开后不再显示入口行，因此不需要「收起」。
                GlasmioListRow(
                    title = strings.More,
                    systemName = null,
                    leading = { GithubEntryIcon(Lucide.Ellipsis, GithubHomeColors.projects) },
                    enabled = enabled,
                    onClick = { expanded = true },
                )
            }
        }
    }
}

@Composable
private fun RepositoryEntry(
    title: String,
    icon: ImageVector,
    color: Color,
    detail: String? = null,
    separator: Boolean = true,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = title,
        detail = detail,
        systemName = null,
        leading = { GithubEntryIcon(icon, color) },
        showTopSeparator = separator,
        enabled = enabled,
        onClick = onClick,
    )
}
