package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler

/** 文件信息属于中间的标题菜单，与右侧编辑动作分开。 */
@Composable
internal fun EditorFileTitle(session: GithubEditorSession) {
    var expanded by remember(session) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val openExternalUri = rememberExternalUriHandler()
    val available = !session.submitting && !session.closeRequested
    val path = session.filePath?.takeIf { it.isNotBlank() } ?: session.title
    val url = session.pageUrl?.takeIf { it.isNotBlank() }
    GlasmioMenu(
        expanded = expanded && available,
        onExpandedChange = { expanded = it },
        itemCount = 2,
        menuWidth = 320.dp,
        forceBelowAnchor = true,
        centerHorizontally = true,
        anchor = { toggle ->
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(
                        enabled = available,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "查看文件路径",
                        onClick = toggle,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    session.title,
                    style =
                        GlasmioTheme.textStyles.headline.copy(
                            color = GlasmioTheme.colors.content,
                            fontSize = 16.sp,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { backdrop, close ->
        GlasmioMenuItem(
            title = path,
            enabled = available,
            onClick = {
                clipboard.setText(AnnotatedString(path))
                expanded = false
                close()
            },
            backdrop = backdrop,
        )
        GlasmioMenuItem(
            title = "在浏览器打开",
            enabled = available && url != null,
            onClick = {
                expanded = false
                close()
                if (url != null) openExternalUri(url)
            },
            backdrop = backdrop,
        )
    }
}
