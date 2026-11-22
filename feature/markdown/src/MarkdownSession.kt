package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.util.concurrent.atomic.AtomicBoolean
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository

@Composable
internal fun rememberMarkdownSession(repository: GithubMarkdownRepository): MarkdownSession {
    val revision by repository.sessionRevision.collectAsState()
    val attached = remember(repository, revision) { AtomicBoolean(true) }
    DisposableEffect(attached) { onDispose { attached.set(false) } }
    return remember(repository, revision, attached) {
        val snapshot = revision
        MarkdownSession(snapshot) { attached.get() && repository.sessionRevision.value == snapshot }
    }
}

internal class MarkdownSession(val revision: Long, val isCurrent: () -> Boolean)
