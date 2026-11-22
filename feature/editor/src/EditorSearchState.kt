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
import moe.yumeyuka.yumehub.ui.component.text.GithubCodeSearchHighlights
import moe.yumeyuka.yumehub.ui.component.text.GithubCodeSearchResult
import moe.yumeyuka.yumehub.ui.component.text.findGithubCodeMatches
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession

/** 查找框独占输入焦点，命中状态不借用正文的原生选区。 */
internal class EditorSearchState {
    var query by mutableStateOf(TextFieldValue())
    var result by mutableStateOf<GithubCodeSearchResult?>(null)
        private set

    var currentIndex by mutableIntStateOf(-1)
        private set

    var revealRequest by mutableIntStateOf(0)
        private set

    fun accept(next: GithubCodeSearchResult, caret: Int) {
        val offset = result?.matches?.getOrNull(currentIndex)?.start ?: caret
        result = next
        currentIndex = next.indexAtOrAfter(offset)
        revealRequest++
    }

    fun move(forward: Boolean) {
        currentIndex = result?.nextIndex(currentIndex, forward) ?: -1
        revealRequest++
    }

    fun highlights(source: String): GithubCodeSearchHighlights? =
        result
            ?.takeIf { it.source == source && it.query == query.text }
            ?.let { GithubCodeSearchHighlights(it, currentIndex, revealRequest) }
}

@Composable
internal fun rememberEditorSearchState(
    session: GithubEditorSession,
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
                findGithubCodeMatches(source, query, matchCase = false) { context.ensureActive() }
            }
        search.accept(next, session.markdownState.value.selection.start)
    }
    return search
}
