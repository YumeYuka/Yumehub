package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.yumeyucca.lucide.EllipsisVertical
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField

private val CommentAvatarSize = 34.dp

@Composable
private fun GithubCommentMenu(
    enabled: Boolean,
    items: List<GlasmioCascadingMenuItem>,
) {
    val strings = LocalGithubStrings.current
    val anchor = rememberGlasmioMenuAnchorState()
    var expanded by remember(enabled) { mutableStateOf(false) }
    Box {
        Box(
            modifier =
                Modifier.size(width = 48.dp, height = CommentAvatarSize)
                    .glasmioMenuAnchor(anchor)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = { expanded = true },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            GithubIcon(
                Lucide.EllipsisVertical,
                contentDescription = strings.WorkDetail.CommentActions,
                tint = GlasmioTheme.colors.secondaryContent,
            )
        }
        if (expanded) {
            GlasmioCascadingMenu(
                anchorBounds = anchor.bounds,
                items = items,
                title = strings.WorkDetail.CommentActions,
                expandedDescription = strings.EditorMenu.Expanded,
                collapsedDescription = strings.EditorMenu.Collapsed,
                onDismiss = { expanded = false },
                useAccentIcons = false,
                morphFromAnchor = false,
            )
        }
    }
}

@Composable
fun GithubCommentAuthorHeader(
    author: String?,
    avatarUrl: String?,
    metadata: String?,
    role: String? = null,
    menuItems: List<GlasmioCascadingMenuItem> = emptyList(),
    menuEnabled: Boolean = true,
) {
    DisableSelection {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = if (menuItems.isEmpty()) 16.dp else 4.dp,
                        bottom = 8.dp,
                    ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GithubAvatar(avatarUrl, author, size = CommentAvatarSize)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        author ?: LocalGithubStrings.current.Workspaces.UnknownAuthor,
                        modifier = Modifier.weight(1f),
                        style = GlasmioTheme.textStyles.subheadline,
                        color = GlasmioTheme.colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GithubCardField(role) {
                        Text(
                            it,
                            style = GlasmioTheme.textStyles.caption1,
                            color = GlasmioTheme.colors.secondaryContent,
                        )
                    }
                }
                GithubCardField(metadata?.takeIf(String::isNotBlank)) {
                    Text(
                        it,
                        style = GlasmioTheme.textStyles.caption1,
                        color = GlasmioTheme.colors.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (menuItems.isNotEmpty()) {
                GithubCommentMenu(enabled = menuEnabled, items = menuItems)
            }
        }
    }
}
