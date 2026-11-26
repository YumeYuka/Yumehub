package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardField
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.UserRowPlaceholder

/** 用户卡片和占位共用头像、名称、账号与简介行槽，不持有业务或分页状态。 */
@Composable
fun GithubUserCard(
    loading: Boolean,
    login: String?,
    name: String?,
    avatarUrl: String?,
    bio: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val styles = GlasmioTheme.textStyles
    val colors = GlasmioTheme.colors
    GithubLoadingCard(loading, modifier, placeholder = { UserRowPlaceholder() }) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GithubAvatar(avatarUrl, login, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: login ?: LocalGithubStrings.current.Activity.Unavailable,
                    style = styles.body,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GithubCardField(login) {
                    Text(
                        "@$it",
                        modifier = Modifier.padding(top = 4.dp),
                        style = styles.footnote,
                        color = colors.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GithubCardField(bio?.takeIf(String::isNotBlank)) {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 4.dp),
                        style = styles.footnote,
                        color = colors.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
