package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.BookMarked
import io.github.yumeyucca.lucide.Building2
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Star
import java.text.NumberFormat
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.locale.GithubStrings_Profile
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.ProfileCollectionsPlaceholder
import moe.yumeyuka.yumehub.ui.theme.GithubProfileColors

@Composable
internal fun ProfileCollectionsCard(
    profile: ProfileDto?,
    strings: GithubStrings_Profile,
    onOpenRepositories: (String, Boolean) -> Unit,
    onOpenStarred: (String) -> Unit,
    onOpenOrganizations: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GithubLoadingCard(
        loading = profile == null,
        deferUntilEntry = false,
        modifier = modifier,
        placeholder = { ProfileCollectionsPlaceholder() },
    ) {
        if (profile != null) {
            Column {
                ProfileCollectionRow(
                    title = strings.Repositories,
                    count = profile.repositoriesCount,
                    icon = Lucide.BookMarked,
                    color = GithubProfileColors.repositoriesBackground,
                    showSeparator = false,
                    onClick = { onOpenRepositories(profile.login, profile.isOrganization) },
                )
                if (!profile.isOrganization) {
                    ProfileCollectionRow(
                        title = strings.Starred,
                        count = profile.starredRepositoriesCount,
                        icon = Lucide.Star,
                        color = GithubProfileColors.starredBackground,
                        filled = true,
                        onClick = { onOpenStarred(profile.login) },
                    )
                    ProfileCollectionRow(
                        title = strings.Organizations,
                        count = profile.organizationsCount,
                        icon = Lucide.Building2,
                        color = GithubProfileColors.organizationsBackground,
                        onClick = { onOpenOrganizations(profile.login) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCollectionRow(
    title: String,
    count: Int?,
    icon: ImageVector,
    color: Color,
    filled: Boolean = false,
    showSeparator: Boolean = true,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = title,
        detail = count?.let { NumberFormat.getIntegerInstance().format(it) },
        leading = {
            Box(
                Modifier.size(28.dp).background(color, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                GithubIcon(
                    icon,
                    contentDescription = null,
                    size = 16.dp,
                    filled = filled,
                    tint = GithubProfileColors.collectionIcon,
                )
            }
        },
        showTopSeparator = showSeparator,
        showDisclosure = true,
        onClick = onClick,
    )
}
