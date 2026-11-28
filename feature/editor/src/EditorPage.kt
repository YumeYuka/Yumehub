package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.CodeTextEditor
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ReadingTextPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.EditorSessionKind
import moe.yumeyuka.yumehub.ui.navigation.EditorSession
import moe.yumeyuka.yumehub.ui.navigation.EditorFileReadStatus
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.LocalEditorBackdropSource
import moe.yumeyuka.yumehub.ui.navigation.LocalEditorContentInsets
import moe.yumeyuka.yumehub.ui.navigation.LocalEditorHeaderHeight
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost

@Composable
fun EditorPage(session: EditorSession) {
    key(session) { EditorSessionPage(session) }
}

@Composable
private fun EditorSessionPage(session: EditorSession) {
    val host = requireNotNull(LocalAppPageHost.current)
    val commonStrings = LocalAppStrings.current.Common
    val markdown = session.markdownState
    var searching by remember(session) { mutableStateOf(false) }
    val search = rememberEditorSearchState(session, searching)
    val dismissSearch = {
        search.highlights(session.source)?.current?.let { match ->
            markdown.edit(markdown.value.copy(selection = TextRange(match.start)))
        }
        searching = false
    }
    val fileReadStatus = session.fileReadStatus
    val fileReady = fileReadStatus == null
    val enabled = fileReady && session.editable && !session.submitting && !session.closeRequested
    LoadFailureToast(session.error, session.error.orEmpty())

    LaunchedEffect(session.closeRequested, session.submitting, host.backEnabled) {
        if (session.closeRequested && !session.submitting && host.backEnabled) host.onBack()
    }

    AppPage(
        title = session.title.ifBlank { commonStrings.Edit },
        locked = session.submitting,
        editorTitle =
            if (session.kind == EditorSessionKind.Code) {
                { EditorFileTitle(session) }
            } else null,
        actions = {
            EditorTopBarActions(
                session = session,
                onSearch = { if (searching) dismissSearch() else searching = true },
            )
        },
        editorContent = {
            val contentInsets = LocalEditorContentInsets.current
            val hasHeaderContent = session.headerContent != null
            val density = LocalDensity.current
            var searchHeight by remember(session) { mutableIntStateOf(0) }
            val searchBottomPadding = if (searching) with(density) { searchHeight.toDp() } else 0.dp
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize()
                        .then(
                            if (hasHeaderContent)
                                Modifier.padding(top = LocalEditorHeaderHeight.current)
                            else Modifier,
                        ),
                ) {
                    CompositionLocalProvider(
                        LocalEditorContentInsets provides
                            PaddingValues(
                                top =
                                    if (hasHeaderContent) 0.dp
                                    else contentInsets.calculateTopPadding(),
                                bottom =
                                    contentInsets.calculateBottomPadding() + searchBottomPadding,
                            ),
                    ) {
                        session.headerContent?.invoke()
                        when (session.kind) {
                            EditorSessionKind.Markdown ->
                                EditorMarkdownContent(
                                    session = session,
                                    state = markdown,
                                    fileReadStatus = fileReadStatus,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            EditorSessionKind.Code -> {
                                EditorFileReadContent(
                                    status = fileReadStatus,
                                    modifier =
                                        Modifier.weight(1f)
                                            .fillMaxSize()
                                            .then(LocalEditorBackdropSource.current),
                                ) {
                                    CodeTextEditor(
                                        source = session.source,
                                        onSourceChange = session::changeSource,
                                        modifier = Modifier.fillMaxSize(),
                                        language = session.language,
                                        editable = enabled,
                                        state = markdown,
                                        wordWrap = session.wordWrap,
                                        lineNumbers = session.lineNumbers,
                                        fontSize = session.fontSize,
                                        searching = searching,
                                        searchHighlights =
                                            if (searching) search.highlights(session.source)
                                            else null,
                                    )
                                }
                            }
                        }
                    }
                }
                if (fileReady && session.kind == EditorSessionKind.Code && searching) {
                    EditorTextSearch(
                        session = session,
                        search = search,
                        onDismiss = dismissSearch,
                        modifier =
                            Modifier.align(Alignment.BottomCenter).onSizeChanged {
                                searchHeight = it.height
                            },
                    )
                }
            }
        },
    ) {}
    session.submissionSheet?.invoke(session)
}

@Composable
internal fun EditorFileReadContent(
    status: EditorFileReadStatus?,
    modifier: Modifier = Modifier,
    showEditor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val contentInsets = LocalEditorContentInsets.current
    LoadingContent(
        loading = status == EditorFileReadStatus.Loading,
        modifier = modifier,
        placeholder = { ReadingTextPlaceholder(Modifier.padding(contentInsets).padding(16.dp)) },
    ) {
        when (status) {
            is EditorFileReadStatus.Failed -> {
                GlasmioGroupedList(Modifier.padding(contentInsets).padding(16.dp)) {
                    GlasmioListRow(title = status.message, systemName = null)
                    status.retry?.let { retry ->
                        GlasmioListRow(
                            title = LocalAppStrings.current.Common.Retry,
                            onClick = retry,
                        )
                    }
                }
            }
            null -> if (showEditor) content() else Unit
            EditorFileReadStatus.Loading -> Unit
        }
    }
}
