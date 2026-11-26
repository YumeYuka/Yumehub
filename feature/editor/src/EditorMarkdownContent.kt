package moe.yumeyuka.yumehub.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Eye
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Pencil
import kotlinx.coroutines.flow.first
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAnimatedIcon
import moe.yumeyuka.yumehub.ui.component.GithubEditorDock
import moe.yumeyuka.yumehub.ui.component.GithubEditorDockActionSize
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownEditorToolbar
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownPreview
import moe.yumeyuka.yumehub.ui.component.LocalGithubEditorFocusReady
import moe.yumeyuka.yumehub.ui.component.MarkdownEditAction
import moe.yumeyuka.yumehub.ui.component.MarkdownEditorState
import moe.yumeyuka.yumehub.ui.component.rememberGithubMarkdownSelectionModifier
import moe.yumeyuka.yumehub.ui.component.text.GlasmioEditableText
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession
import moe.yumeyuka.yumehub.ui.navigation.GithubFileReadStatus
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubEditorBackdropSource
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubEditorContentInsets
import moe.yumeyuka.yumehub.ui.theme.GithubEditorFontFamily

@Composable
internal fun EditorMarkdownContent(
    session: GithubEditorSession,
    state: MarkdownEditorState,
    fileReadStatus: GithubFileReadStatus?,
    modifier: Modifier = Modifier,
) {
    val colors = GlasmioTheme.colors
    val contentInsets = LocalGithubEditorContentInsets.current
    val editorContentHorizontalPadding = 16.dp
    val commonStrings = LocalGithubStrings.current.Common
    val markdownStrings = LocalGithubStrings.current.Markdown
    var preview by rememberSaveable(session.id, session.pageUrl) { mutableStateOf(session.previewOnOpen) }
    var previousEditable by remember(session) { mutableStateOf(session.editable) }
    val focusRequester = remember(session) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusReady = LocalGithubEditorFocusReady.current
    var editorPlaced by remember(session) { mutableStateOf(false) }
    var editorFocused by remember(session) { mutableStateOf(false) }
    val fileReady = fileReadStatus == null
    val enabled = fileReady && session.editable && !session.submitting && !session.closeRequested
    val selectionMenu =
        rememberGithubMarkdownSelectionModifier(state, enabled, session::changeSource)
    LaunchedEffect(session, session.editable) {
        if (!previousEditable && session.editable && preview) {
            preview = false
        }
        previousEditable = session.editable
    }
    LaunchedEffect(session, enabled, preview, focusReady, editorPlaced) {
        if (!preview && enabled && focusReady && editorPlaced) {
            focusRequester.requestFocus()
            snapshotFlow { editorFocused }.first { it }
            keyboard?.show()
        }
    }
    val applyFormatting: (MarkdownEditAction) -> Unit = { action ->
        if (enabled && !preview) {
            state.apply(action)
            session.changeSource(state.value.text)
        }
    }

    val dockBackdrop = rememberLayerBackdrop()
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val dockHeight = GithubEditorDockActionSize
    // 外层统一消费键盘和导航栏 inset；间距不随可见性切换，避免收起末段跳变。
    val dockBottomPadding = 12.dp
    val dockClearance = dockHeight + dockBottomPadding + 16.dp
    Box(modifier) {
        // 顶栏与底栏仅采样原生编辑器和背景；WebView 是两个采样层之外的兄弟节点。
        EditorFileReadContent(
            status = fileReadStatus,
            showEditor = !preview,
            modifier =
                Modifier.matchParentSize()
                    .then(LocalGithubEditorBackdropSource.current)
                    .glassBackdropSource(dockBackdrop)
                    .trackBackdropPosition(dockBackdrop)
                    .background(colors.elevatedBackground),
        ) {
            DisposableEffect(focusRequester) {
                onDispose { editorPlaced = false }
            }
                GlasmioEditableText(
                    value = state.value,
                    onValueChange = {
                        if (enabled && !preview) {
                            state.edit(it)
                            session.changeSource(state.value.text)
                        }
                    },
                    modifier =
                        Modifier.fillMaxSize()
                            .focusRequester(focusRequester)
                            .onGloballyPositioned { editorPlaced = true }
                            .onFocusChanged { editorFocused = it.isFocused }
                            .then(selectionMenu),
                    enabled = !session.submitting,
                    readOnly = !session.editable || session.closeRequested || preview,
                    textStyle =
                        GlasmioTheme.textStyles.body.copy(
                            color = GlasmioTheme.colors.content,
                            fontFamily = GithubEditorFontFamily,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                        ),
                    singleLine = false,
                    maxLines = Int.MAX_VALUE,
                    topViewportInset = contentInsets.calculateTopPadding(),
                    bottomViewportInset = dockClearance,
                    contentPadding =
                        PaddingValues(
                            start = editorContentHorizontalPadding,
                            end = editorContentHorizontalPadding,
                            top = contentInsets.calculateTopPadding() + 16.dp,
                            bottom = dockClearance,
                        ),
                )
        }
        val source = session.source
        val html = session.readHtml
        if (fileReady && preview && remember(source, html) { source.isBlank() && html.isNullOrBlank() }) {
            GlasmioListRow(
                title = LocalGithubStrings.current.Repository.ContentEmpty,
                modifier = Modifier.padding(top = contentInsets.calculateTopPadding()),
                systemName = null,
            )
        } else if (fileReady && preview) {
            GithubMarkdownPreview(
                text = source,
                html = html,
                visible = true,
                modifier =
                    Modifier.matchParentSize().zIndex(1f).layout {
                        measurable,
                        constraints ->
                        // 外层按键盘逐帧收缩；预览按无键盘高度固定测量，避免收起键盘时逐帧
                        // resize WebView 产生空白帧。超出部分位于键盘区域之下。
                        val keyboardHeight =
                            (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
                                .coerceAtLeast(0)
                        val height = constraints.maxHeight + keyboardHeight
                        val placeable =
                            measurable.measure(
                                constraints.copy(minHeight = height, maxHeight = height),
                            )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(0, 0)
                        }
                    },
                pageUrl = session.pageUrl,
                repositoryRootUrl = session.repositoryRootUrl,
                scrollToAnchor = session.scrollToAnchor,
                onOpenLink = session::openMarkdownLink,
                contentPadding =
                    PaddingValues(
                        start = editorContentHorizontalPadding,
                        end = editorContentHorizontalPadding,
                        top = contentInsets.calculateTopPadding() + 16.dp,
                        bottom = dockClearance,
                    ),
            )
        }
        CompositionLocalProvider(LocalGlassBackdrop provides dockBackdrop) {
            Row(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .fillMaxWidth()
                        .padding(
                            start = editorContentHorizontalPadding,
                            end = editorContentHorizontalPadding,
                            bottom = dockBottomPadding,
                        ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GithubEditorDock(height = dockHeight) {
                    GlasmioIconButton(
                        onClick = {
                            val nextPreview = !preview
                            if (nextPreview && editorFocused) {
                                // 先释放输入焦点，再组合预览 WebView，避免 IME/选区同帧跳变。
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                            }
                            preview = nextPreview
                        },
                        enabled = fileReady && !session.submitting && !session.closeRequested,
                        navigationSurfaceColor =
                            if (GlasmioTheme.appearance.reduceTransparency) colors.container
                            else Color.Transparent,
                        sampleBackdrop = false,
                        size = dockHeight,
                    ) {
                        GithubAnimatedIcon(
                            imageVector = if (preview) Lucide.Pencil else Lucide.Eye,
                            contentDescription =
                                when {
                                    !preview -> markdownStrings.Preview
                                    session.editable -> commonStrings.Edit
                                    else -> markdownStrings.ViewSource
                                },
                            size = 20.dp,
                            tint = colors.content,
                        )
                    }
                }
                GithubEditorDock(modifier = Modifier.weight(1f), height = dockHeight) {
                    GithubMarkdownEditorToolbar(
                        hasSelection = !state.value.selection.collapsed,
                        enabled = enabled && !preview,
                        onAction = applyFormatting,
                        onCode = { applyFormatting(MarkdownEditAction.CodeBlock) },
                        modifier = Modifier.fillMaxHeight(),
                        actionSize = dockHeight,
                        onEmoji = { emoji ->
                            if (enabled && !preview) {
                                val value = state.value
                                val start = value.selection.min
                                val end = value.selection.max
                                state.edit(
                                    value.copy(
                                        text = value.text.replaceRange(start, end, emoji),
                                        selection = TextRange(start + emoji.length),
                                        composition = null,
                                    ),
                                )
                                session.changeSource(state.value.text)
                            }
                        },
                    )
                }
            }
        }
    }
}
