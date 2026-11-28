package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.locale.LocalAppStrings

/** 仅提供评论目标的上下文，正文、预览与提交统一由共享 Markdown 编辑器承载。 */
@Composable
internal fun CollaborationEditorHeader(viewModel: CollaborationDetailViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = LocalAppStrings.current.WorkDetail
    state.reviewLine?.let { line ->
        Text(
            strings.LineContext(line.path, line.line, line.side == "RIGHT"),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = GlasmioTheme.textStyles.footnote,
            color = GlasmioTheme.colors.secondaryContent,
        )
    }
}
