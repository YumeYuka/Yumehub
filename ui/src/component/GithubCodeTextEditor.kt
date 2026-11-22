package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.ui.component.text.GithubCodeSearchHighlights
import moe.yumeyuka.yumehub.ui.component.text.GithubEditorSyntax
import moe.yumeyuka.yumehub.ui.component.text.GlasmioEditableText
import moe.yumeyuka.yumehub.ui.component.text.LocalGlasmioTextEditingHost
import moe.yumeyuka.yumehub.ui.component.text.rememberGlasmioCodeSyntaxPainter
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubEditorContentInsets
import moe.yumeyuka.yumehub.ui.theme.GithubEditorFontFamily

/** Glasmio 独占输入、滚动、光标和选区；Cursor 仅生成不改变原文偏移的文字样式。 */
@Composable
fun GithubCodeTextEditor(
    source: String,
    onSourceChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    language: String? = null,
    editable: Boolean = true,
    state: MarkdownEditorState? = null,
    wordWrap: Boolean = false,
    lineNumbers: Boolean = true,
    fontSize: Int = 14,
    searching: Boolean = false,
    searchHighlights: GithubCodeSearchHighlights? = null,
    contentPadding: PaddingValues? = null,
    viewportInsets: PaddingValues? = null,
    revealSelection: Boolean = !searching,
) {
    // 代码视口必须拥有可观察的 Glasmio 滚动宿主，不能退回不可观察的内部滚动。
    val host = LocalGlasmioTextEditingHost.current
    val rootView = LocalView.current.rootView
    if (host == null || host.windowView !== rootView) {
        GithubTextSelectionHost {
            GithubCodeTextEditor(
                source = source,
                onSourceChange = onSourceChange,
                modifier = modifier,
                language = language,
                editable = editable,
                state = state,
                wordWrap = wordWrap,
                lineNumbers = lineNumbers,
                fontSize = fontSize,
                searching = searching,
                searchHighlights = searchHighlights,
                contentPadding = contentPadding,
                viewportInsets = viewportInsets,
                revealSelection = revealSelection,
            )
        }
        return
    }
    val editor = state ?: remember { MarkdownEditorState(source) }
    val contentInsets = viewportInsets ?: LocalGithubEditorContentInsets.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusReady = LocalGithubEditorFocusReady.current
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(editable, focusReady, placed, searching) {
        if (editable && focusReady && placed && !searching) {
            withFrameNanos {}
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val context = LocalContext.current.applicationContext
    val highlighter = remember(editor, context) { GithubTextMateHighlighter(context) }
    val highlightingLock = remember(highlighter) { Mutex() }
    val colors = GlasmioTheme.colors
    val text = editor.value.text
    val editorTextStyle =
        GlasmioTheme.textStyles.body.copy(
            color = colors.content,
            fontFamily = GithubEditorFontFamily,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 24f / 14f).sp,
        )
    val syntaxPainter =
        key(editor, language, colors.isDark) { rememberGlasmioCodeSyntaxPainter(editorTextStyle) }
    // TextMate 在后台生成 token；纯文本和颜色共用可见行绘制，不改变原生输入布局。
    var syntax by
        remember(editor, language, colors.isDark) {
            mutableStateOf(GithubEditorSyntax(text, emptyList()))
        }
    LaunchedEffect(source) { editor.syncExternal(source) }
    LaunchedEffect(text, language, colors.isDark) {
        delay(150)
        val result =
            withContext(Dispatchers.Default) {
                val jobContext = currentCoroutineContext()
                highlightingLock.withLock {
                    highlighter.highlight(text, language, colors.isDark) { jobContext.isActive }
                }
            }
        ensureActive()
        syntax = GithubEditorSyntax(text, result)
    }
    val selectionMenu = rememberGithubMarkdownSelectionModifier(editor, editable, onSourceChange)
    GlasmioEditableText(
        value = editor.value,
        onValueChange = { next ->
            if (editable) {
                val previous = editor.value.text
                editor.edit(next)
                if (previous != next.text) onSourceChange(next.text)
            } else {
                // 只读页面仍保存 Glasmio 的选区，不把选区变更误当作正文编辑。
                editor.edit(next.copy(text = editor.value.text))
            }
        },
        modifier =
            modifier
                .focusRequester(focus)
                .onGloballyPositioned { placed = true }
                .then(selectionMenu),
        readOnly = !editable,
        textStyle = editorTextStyle,
        singleLine = false,
        maxLines = Int.MAX_VALUE,
        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
        syntaxOverlay = syntax,
        syntaxPainter = syntaxPainter,
        revealSelection = revealSelection,
        searchHighlights = searchHighlights,
        wordWrap = wordWrap,
        lineNumbers = lineNumbers,
        topViewportInset = contentInsets.calculateTopPadding(),
        bottomViewportInset = contentInsets.calculateBottomPadding(),
        contentPadding =
            contentPadding
                ?: PaddingValues(
                    top = contentInsets.calculateTopPadding() + 4.dp,
                    bottom = contentInsets.calculateBottomPadding() + 16.dp,
                ),
    )
}
