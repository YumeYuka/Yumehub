package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.ui.component.text.CodeSearchHighlights
import moe.yumeyuka.yumehub.ui.component.text.CodeSearchResult
import moe.yumeyuka.yumehub.ui.component.text.findCodeMatches
import moe.yumeyuka.yumehub.ui.navigation.EditorSession

/** 查找框独占输入焦点，命中状态不借用正文的原生选区。 */
internal class EditorSearchState {
    var query by mutableStateOf(TextFieldValue())
    var result by mutableStateOf<CodeSearchResult?>(null)
        private set

    var currentIndex by mutableIntStateOf(-1)
        private set

    var revealRequest by mutableIntStateOf(0)
        private set

    fun accept(next: CodeSearchResult, caret: Int) {
        val offset = result?.matches?.getOrNull(currentIndex)?.start ?: caret
        result = next
        currentIndex = next.indexAtOrAfter(offset)
        revealRequest++
    }

    fun move(forward: Boolean) {
        currentIndex = result?.nextIndex(currentIndex, forward) ?: -1
        revealRequest++
    }

    fun highlights(source: String): CodeSearchHighlights? =
        result
            ?.takeIf { it.source == source && it.query == query.text }
            ?.let { CodeSearchHighlights(it, currentIndex, revealRequest) }
}

@Composable
internal fun rememberEditorSearchState(
    session: EditorSession,
    visible: Boolean,
): EditorSearchState {
    val search = remember(session) { EditorSearchState() }
    val source = session.source
    val query = search.query.text
    LaunchedEffect(session, source, query, visible) {
        if (!visible) return@LaunchedEffect
        if (query.isNotEmpty()) delay(100)
        val next =
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                findCodeMatches(source, query, matchCase = false) { context.ensureActive() }
            }
        search.accept(next, session.markdownState.value.selection.start)
    }
    return search
}
