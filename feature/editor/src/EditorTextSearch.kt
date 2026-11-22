package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSearchToolbar
import io.github.yumeyucca.lucide.ChevronDown
import io.github.yumeyucca.lucide.ChevronUp
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubEditorDock
import moe.yumeyuka.yumehub.ui.component.GithubEditorDockActionSize
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession

/** 沿用 MeiloX 的 Glasmio 原生底部悬浮搜索栏；系统和键盘 inset 由编辑视口统一消费。 */
@Composable
internal fun EditorTextSearch(
    session: GithubEditorSession,
    search: EditorSearchState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalGithubStrings.current.EditorSearch
    val colors = GlasmioTheme.colors
    val available = !session.submitting && !session.closeRequested
    val highlights = search.highlights(session.source)
    val count = highlights?.result?.matches?.size ?: 0
    val position = if (count == 0) 0 else search.currentIndex + 1
    val resultText =
        when {
            highlights == null -> strings.Searching
            count == 0 -> strings.NoMatches
            else -> strings.MatchCount(position, count)
        }
    val buttonColor =
        if (GlasmioTheme.appearance.reduceTransparency) colors.container else Color.Transparent
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onDismiss()
    }
    GithubBackHandler(enabled = available, onBack = dismiss)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (search.query.text.isNotEmpty()) {
            GithubEditorDock {
                BasicText(
                    text = resultText,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = GlasmioTheme.textStyles.footnote.copy(color = colors.content),
                )
                GlasmioIconButton(
                    onClick = { search.move(forward = false) },
                    enabled = available && count > 0,
                    size = GithubEditorDockActionSize,
                    navigationSurfaceColor = buttonColor,
                    sampleBackdrop = false,
                ) {
                    GithubIcon(
                        Lucide.ChevronUp,
                        contentDescription = strings.Previous,
                        size = 20.dp,
                    )
                }
                GlasmioIconButton(
                    onClick = { search.move(forward = true) },
                    enabled = available && count > 0,
                    size = GithubEditorDockActionSize,
                    navigationSurfaceColor = buttonColor,
                    sampleBackdrop = false,
                ) {
                    GithubIcon(Lucide.ChevronDown, contentDescription = strings.Next, size = 20.dp)
                }
            }
        }
        GlasmioSearchToolbar(
            query = search.query,
            onQueryChange = { search.query = it },
            onSearch = { if (available && count > 0) search.move(forward = true) },
            onCancel = dismiss,
            cancelLabel = strings.Close,
            placeholder = strings.Placeholder,
            enabled = available,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
