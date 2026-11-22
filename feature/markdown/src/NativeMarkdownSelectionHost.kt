package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalNativeMarkdownHostActive = staticCompositionLocalOf { true }

/** 选择状态属于当前列表帧；容器保持固定视口，正文由其内部真实列表自然滚动。 */
@Composable
internal fun NativeMarkdownSelectionHost(
    active: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val selection = rememberSelectionState()
    val latestContent = rememberUpdatedState(content)
    val frameContent = remember { movableContentOf { latestContent.value(Modifier) } }
    SideEffect { if (!active) selection.clear() }
    CompositionLocalProvider(LocalNativeMarkdownHostActive provides active) {
        SelectionContainer(
            state = selection,
            modifier =
                modifier
                    .fillMaxSize()
                    .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
        ) {
            // 同一帧显隐只改变选择环境，不能重建列表项的请求、解析与滚动状态。
            if (active) frameContent() else DisableSelection { frameContent() }
        }
    }
}
