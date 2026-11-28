package moe.yumeyuka.yumehub.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.yumeyucca.lucide.FaceSlightlySmiling
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.locale.ProfileStrings
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder

@Composable
internal fun ProfileHeader(
    profile: ProfileDto?,
    strings: ProfileStrings,
    onEditStatus: () -> Unit,
    onOpenFollowing: () -> Unit,
    showEditStatus: Boolean = true,
    fallbackAvatarUrl: String? = null,
    fallbackName: String? = null,
    fallbackOrganization: Boolean = false,
) {
    val colors = GlasmioTheme.colors
    val emoji by rememberStatusEmoji(profile?.status?.emoji.orEmpty())
    val name = profile?.name?.takeIf(String::isNotBlank) ?: profile?.login.orEmpty()
    val organization = profile?.isOrganization ?: fallbackOrganization
    val avatarType = if (organization) "Organization" else null
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(100.dp)) {
            val avatarUrl = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackAvatarUrl
            UserAvatar(
                avatarUrl,
                profile?.login ?: fallbackName,
                type = avatarType,
                size = 100.dp,
                placeholder = { AvatarPlaceholder(100.dp, type = avatarType) },
            )
        }
        LoadingContent(
            loading = profile == null && fallbackName == null,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            placeholder = {
                Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                    TextPlaceholder(height = 20.dp, fraction = 0.38f)
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (profile == null) fallbackName.orEmpty() else name,
                    modifier = Modifier.weight(1f, fill = false),
                    style =
                        GlasmioTheme.textStyles.title3.copy(
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                        ),
                    fontWeight = FontWeight.Medium,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showEditStatus && profile != null)
                    Box(
                        modifier =
                            Modifier.size(28.dp)
                                .semantics { contentDescription = strings.EditStatus }
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    role = Role.Button,
                                    onClickLabel = strings.EditStatus,
                                    onClick = onEditStatus,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emoji.isNotBlank()) {
                            Text(emoji, style = GlasmioTheme.textStyles.body.copy(fontSize = 18.sp))
                        } else {
                            AppIcon(
                                Lucide.FaceSlightlySmiling,
                                contentDescription = null,
                                size = 20.dp,
                            )
                        }
                    }
            }
        }
        if (!organization && (profile == null || profile.audienceResolved))
            LoadingContent(
                loading = profile?.audienceResolved != true,
                modifier = Modifier.fillMaxWidth().height(18.dp),
                placeholder = {
                    Box(
                        Modifier.fillMaxWidth().height(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextPlaceholder(height = 13.dp, fraction = 0.48f)
                    }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (profile != null && profile.audienceResolved) {
                        Text(
                            strings.Audience(profile.followers, profile.following),
                            modifier = Modifier.clickable(onClick = onOpenFollowing),
                            style =
                                GlasmioTheme.textStyles.footnote.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                ),
                            color = colors.secondaryContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
    }
}
