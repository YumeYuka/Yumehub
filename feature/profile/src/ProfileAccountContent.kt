package moe.yumeyuka.yumehub.feature.profile

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.locale.GithubStrings_Profile
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.*
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler

/** 静态居中资料头部、三个操作卡、资料卡、主页列表入口与直接渲染的 README。 */
internal fun LazyListScope.ProfileAccountContent(
    state: ProfileUiState,
    strings: GithubStrings_Profile,
    onRetryProfile: () -> Unit,
    onRetryReadme: () -> Unit,
    onEditStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenRepositories: (String, Boolean) -> Unit,
    onOpenStarred: (String) -> Unit,
    onOpenOrganizations: (String) -> Unit,
) {
    val content = state as? ProfileUiState.Content
    val profile = content?.profile
    if (state != ProfileUiState.Error) {
        item(key = "profile-header") { ProfileHeader(profile, strings, onEditStatus, onOpenFollowing) }
    }
    item(key = "profile-actions") { ProfileActionCards(strings, onOpenSettings) }
    if (state == ProfileUiState.Error || content?.refreshFailed == true) {
        item(key = "profile-error") { ProfileRetryRow(strings.LoadFailed, onRetryProfile) }
    }
    if (state != ProfileUiState.Error) {
        item(key = "profile-details") { ProfileDetailsSection(profile, strings) }
        item(key = "profile-collections") {
            ProfileCollectionsCard(profile, strings, onOpenRepositories, onOpenStarred, onOpenOrganizations)
        }
        readingItem(key = "profile-readme") { ProfileReadmeContent(profile, onRetryReadme) }
    }
}

/** 三个操作卡：设置头像、编辑信息、设置，横向等分。 */
@Composable
private fun ProfileActionCards(
    strings: GithubStrings_Profile,
    onOpenSettings: () -> Unit,
) {
    val openExternal = rememberExternalUriHandler()
    val profileEditUrl = "https://github.com/settings/profile"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileActionButton(
            label = strings.SetAvatar,
            icon = Lucide.Camera,
            modifier = Modifier.weight(1f),
            onClick = { openExternal(profileEditUrl) },
        )
        ProfileActionButton(
            label = strings.EditProfile,
            icon = Lucide.Pencil,
            modifier = Modifier.weight(1f),
            onClick = { openExternal(profileEditUrl) },
        )
        ProfileActionButton(
            label = strings.Settings,
            icon = Lucide.Settings,
            modifier = Modifier.weight(1f),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun ProfileActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlasmioButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 58.dp),
    ) {
            Column(
                Modifier.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GithubIcon(icon, contentDescription = null)
                Text(
                    label,
                    style = GlasmioTheme.textStyles.footnote.copy(fontSize = 12.sp),
                    maxLines = 1,
                )
        }
    }
}

/** 资料卡统一 60dp 行高，图标放左侧，组织在第一行、地点在第二行。 */
@Composable
internal fun ProfileDetailsSection(
    profile: ProfileDto?,
    strings: GithubStrings_Profile,
) {
    // 外壳保留；已知资料按实际行数测量，未知时的估算仅用于加载阶段。
    GithubLoadingCard(
        loading = profile == null,
        deferUntilEntry = false,
        placeholder = { ProfileDetailsPlaceholder() },
    ) {
        if (profile != null) ProfileDetailsRows(profile, strings)
    }
}

@Composable
private fun ProfileDetailsRows(profile: ProfileDto, strings: GithubStrings_Profile) {
    val headline = profile.bio?.trim().orEmpty()
    val organization = profile.company?.trim().orEmpty()
    val location = profile.location?.trim().orEmpty()
    Column {
        if (headline.isNotEmpty()) {
            ProfileDetailRow(headline, strings.Biography, false, Lucide.User)
        }
        ProfileDetailRow(
            profile.login,
            strings.Username,
            headline.isNotEmpty(),
            Lucide.AtSign,
        )
        if (organization.isNotEmpty() || location.isNotEmpty()) {
            ProfileDetailRow(
                title = organization.ifBlank { location },
                subtitle = location.takeIf { organization.isNotEmpty() && it.isNotEmpty() },
                showSeparator = true,
                icon = Lucide.MapPin,
            )
        }
    }
}

/** README 正文：官方 HTML 渲染，走共享阅读分组。 */
@Composable
internal fun ProfileReadmeContent(profile: ProfileDto?, onRetry: () -> Unit) {
    val readme = profile?.readmeHtml?.takeIf(String::isNotBlank)
    val repositoryUrl = profile?.let {
        "https://github.com/${it.login}/${if (it.isOrganization) ".github" else it.login}"
    }
    val pageUrl = profile?.let {
        val path = it.readmePath ?: if (it.isOrganization) "profile/README.md" else "README.md"
        "$repositoryUrl/blob/HEAD/${Uri.encode(path, "/")}"
    }
    val strings = LocalGithubStrings.current.Profile
    GithubReadingGroup(documentKey = readme) {
        when {
            profile == null -> ReadmePlaceholder()
            readme != null -> GithubMarkdown(
                text = "",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                pageUrl = pageUrl,
                repositoryRootUrl = repositoryUrl,
                html = readme,
                readme = true,
                transparentBackground = true,
            )
            !profile.readmeResolved -> Unit
            else -> GlasmioListRow(title = strings.CollectionEmpty(strings.Readme), systemName = null)
        }
        if (profile?.readmeResolved == false) ProfileRetryRow(strings.ReadmeLoadFailed, onRetry)
    }
}

@Composable
internal fun ProfileRetryRow(message: String, retry: () -> Unit) {
    GlasmioGroupedList {
        GlasmioListRow(title = message, detail = LocalGithubStrings.current.Common.Retry, systemName = null, onClick = retry)
    }
}

/** 只调整资料行文字大小，卡片圆角、底色和分隔线仍使用 Glasmio。 */
@Composable
private fun ProfileDetailRow(
    title: String,
    subtitle: String?,
    showSeparator: Boolean,
    icon: ImageVector,
) {
    val colors = GlasmioTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GithubIcon(icon, contentDescription = null, size = 22.dp, tint = colors.secondaryContent)
        Column(
            Modifier.weight(1f)
                .fillMaxHeight()
                .glasmioGroupedListSeparator(colors.separator, visible = showSeparator),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                title,
                style = GlasmioTheme.textStyles.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style =
                        GlasmioTheme.textStyles.footnote.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = colors.secondaryContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
