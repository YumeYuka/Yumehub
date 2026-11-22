package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioMenuAnchorState
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction

/** 缺席的操作传 null;保留同一操作的身份,只有图标变化才替换前景。 */
@Composable
fun GithubToolbarActions(vararg actions: GlasmioHeaderAction?) {
    GithubToolbarMorph(actions.filterNotNull())
}

fun toolbarIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    key: Any = icon,
    menuAnchor: GlasmioMenuAnchorState? = null,
    onClick: () -> Unit,
) =
    GlasmioHeaderAction(
        key = key,
        visualKey = icon,
        contentDescription = label,
        enabled = enabled,
        menuAnchor = menuAnchor,
        onClick = onClick,
    ) {
        if (enabled) GithubIcon(icon, contentDescription = null)
        else GithubIcon(icon, contentDescription = null, tint = GlasmioTheme.colors.tertiaryContent)
    }
