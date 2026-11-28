package moe.yumeyuka.yumehub.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.Copy
import io.github.yumeyucca.lucide.Ellipsis
import io.github.yumeyucca.lucide.FileArchive
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Package
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText

fun downloadFileIcon(sourceArchive: Boolean): ImageVector =
    if (sourceArchive) Lucide.FileArchive else Lucide.Package

@Composable
fun DownloadFileRow(
    name: String,
    bytes: Long,
    digest: String?,
    separator: Boolean,
    enabled: Boolean,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    onClick: (() -> Unit)?,
    leadingIcon: ImageVector? = null,
    detail: String? = null,
    actionEnabled: Boolean = true,
) {
    val strings = LocalAppStrings.current
    val tint = GlasmioTheme.colors.secondaryContent
    val copy = rememberCopyText()
    var menu by remember { mutableStateOf(false) }
    GlasmioListRow(
        title = name,
        subtitle = Formatter.formatShortFileSize(LocalContext.current, bytes),
        detail = detail,
        leading =
            leadingIcon?.let { icon ->
                { AppIcon(icon, contentDescription = null, tint = tint) }
            },
        showTopSeparator = separator,
        showDisclosure = false,
        enabled = enabled,
        onClick = onClick ?: { menu = true },
        trailing = {
            GlasmioMenu(
                expanded = menu && enabled,
                onExpandedChange = { menu = it },
                itemCount = 2,
                anchor = {
                    Box(
                        modifier =
                            Modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button) {
                                menu = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            Lucide.Ellipsis,
                            contentDescription = strings.EditorMenu.More,
                            tint = tint,
                        )
                    }
                },
            ) { backdrop, close ->
                GlasmioMenuItem(
                    title = actionLabel,
                    enabled = enabled && actionEnabled,
                    backdrop = backdrop,
                    icon = {
                        AppIcon(actionIcon, contentDescription = null, size = 20.dp, tint = tint)
                    },
                    onClick = {
                        close()
                        onAction()
                    },
                )
                GlasmioMenuItem(
                    title = strings.Release.CopyDigest,
                    enabled = digest != null,
                    backdrop = backdrop,
                    icon = {
                        AppIcon(
                            Lucide.Copy,
                            contentDescription = null,
                            size = 20.dp,
                            tint = tint,
                        )
                    },
                    onClick = {
                        close()
                        digest?.let { copy(strings.Release.CopyDigest, it) }
                    },
                )
            }
        },
    )
}
