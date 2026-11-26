/*
 * Copyright 2026 NEORUAA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from NEORUAA/Glasmio, commit c739324f4b01ed8189ffee80752d43cf5d25cd41
 * Local patches: package/import adaptation, capsule loupe, caret/selection handles,
 * and gesture-scoped IME reopening. See README.md in this directory.
 */
package moe.yumeyuka.yumehub.ui.component.text

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.PathHitTester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.glass.rememberCrossWindowBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioOverscrollHost
import io.github.neoruaa.glasmio.ui.GlasmioLoupeMotion
import io.github.neoruaa.glasmio.ui.GlasmioMagnifier
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CapsuleLoupeHeight = 48.dp

// 光标居中于文本行；水滴手柄的尖端与实际绘制的光标共用坐标。
private val CaretWidth = 2.dp
private val CaretHeight = 24.dp
private val HandleRadius = 9.dp

internal val LocalGlasmioTextEditingHost =
    staticCompositionLocalOf<GlasmioTextEditingHost?> { null }

/**
 * Window-local editing chrome. Native text input, selection and IME ownership stay in Foundation.
 */
internal class GlasmioTextEditingHost(val windowView: View, val recording: LayerBackdrop) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var active by mutableStateOf<GlasmioTextEditor?>(null)
    var menuOwnsFocus by mutableStateOf(false)
    val magnifying: Boolean
        get() =
            active?.let {
                it.chromeVisible &&
                    (it.magnifierSource != null || it.magnifierRetainedSource != null)
            } == true

    fun activate(editor: GlasmioTextEditor) {
        if (active !== editor) active?.dismiss()
        active = editor
    }
}

@Composable
internal fun rememberGlasmioTextEditingHost(): GlasmioTextEditingHost {
    val windowView = LocalView.current.rootView
    val recording = rememberLayerBackdrop()
    return remember(windowView, recording) { GlasmioTextEditingHost(windowView, recording) }
}

/** A modal must own its chrome in its own window, rather than using the activity overlay. */
@Composable
internal fun GlasmioTextEditingWindow(backdrop: Backdrop, content: @Composable () -> Unit) {
    if (LocalGlasmioTextEditingHost.current == null) content()
    else
        GlasmioTextSelectionHost(
            backdrop = rememberCrossWindowBackdrop(backdrop),
            content = content,
        )
}

/**
 * A plain editable text surface with window-local iOS editing chrome inside
 * [GlasmioTextSelectionHost]. Single-line fields scroll horizontally. Multiline fields use
 * [wordWrap] to choose wrapping or an independent horizontal axis; [lineNumbers] adds a fixed
 * logical-line gutter to the same vertical scroll body. Without a matching host, Foundation renders
 * its native editing chrome.
 *
 * [onVisibleTextRange] 在 composition 之外回报当前原文版本的 UTF-16 半开区间。
 * 换行模式包含完整逻辑行和预取范围；不换行模式还按水平视口裁剪。连续区间可能包含中间整行， 换行模式的超长单逻辑行也可能回报全文；这不是文本布局虚拟化。调用方保留完整原文和全局选区， 仅限制颜色
 * annotations。已有预取范围内的滚动和单独更换回调不会重复回报。 无 host 时保留原生 BasicTextField，只回报初始视图及布局/尺寸变化；无法读取其内部滚动位置。
 */
@Composable
fun GlasmioEditableText(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = IosTypography.body,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    outputTransformation: OutputTransformation? = null,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions =
        if (password) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        else KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    cursorBrush: Brush = SolidColor(LocalGlassColors.current.accent),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
    revealSelection: Boolean = false,
    wordWrap: Boolean = true,
    lineNumbers: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    topViewportInset: Dp = 0.dp,
    bottomViewportInset: Dp = 0.dp,
    onVisibleTextRange: ((TextRange) -> Unit)? = null,
    syntaxOverlay: GithubEditorSyntax? = null,
    syntaxPainter: moe.yumeyuka.yumehub.ui.component.text.GlasmioCodeSyntaxPainter? = null,
    searchHighlights: GithubCodeSearchHighlights? = null,
) {
    require(maxLines > 0) { "maxLines must be positive." }
    require(!password || singleLine) { "Secure fields must be single-line." }
    require(!password || outputTransformation == null) { "Secure fields own their output transformation." }
    val inputModifier = modifier.swipeBackExcluded(enabled)
    val host = LocalGlasmioTextEditingHost.current
    val view = LocalView.current
    val source = value.text
    val latestSource by rememberUpdatedState(source)
    val visibleCallback by rememberUpdatedState(onVisibleTextRange)
    val formatter = remember(outputTransformation, singleLine) {
        outputTransformation?.let { EditorOutputFormatter(it, singleLine) }
    }
    val plainPresentation = remember(source, password, singleLine) {
        EditorTextPresentation(source, password, singleLine)
    }
    var measuredPresentation by remember(formatter) { mutableStateOf<EditorTextPresentation?>(null) }
    val presentation = measuredPresentation?.takeIf { it.source == source } ?: plainPresentation
    var visibleLayout by remember { mutableStateOf<VisibleTextLayout?>(null) }
    // 不换行且无文本变换时，Compose 布局的视觉行就是逻辑行，行号直接读布局，
    // 不再为全文另行扫描行首表；换行或存在变换时才需要独立索引。
    val needsLineNumbers = !singleLine && lineNumbers
    val needsLineIndex = needsLineNumbers && (wordWrap || formatter != null)
    val generatedIndex by
        produceState<TextLineIndex?>(null, source, needsLineIndex) {
            this.value =
                if (needsLineIndex) withContext(Dispatchers.Default) { buildTextLineIndex(source) }
                else null
        }
    val lineIndex = generatedIndex?.takeIf { it.source == source }
    val captureLayout: (TextLayoutResult, EditorTextPresentation?) -> Unit = { result, formatted ->
        val measured = formatted ?: plainPresentation
        if (measured.displayText == result.layoutInput.text.text) {
            measuredPresentation = formatted
            if (onVisibleTextRange != null) {
                visibleLayout = VisibleTextLayout(measured.source, result, measured)
            }
        }
        onTextLayout(result)
    }
    if (host == null || host.windowView !== view.rootView) {
        var fallbackSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        if (onVisibleTextRange != null) {
            LaunchedEffect(source, singleLine, wordWrap) {
                var reported: TextRange? = null
                snapshotFlow {
                    visibleLayout
                        ?.takeIf { it.source == source && latestSource == source }
                        ?.let {
                            if (fallbackSize.width <= 0 || fallbackSize.height <= 0) null
                            else {
                                visibleTextWindow(
                                    it,
                                    0f,
                                    minOf(fallbackSize.height, view.height).toFloat(),
                                    0f,
                                    fallbackSize.width.toFloat(),
                                    !singleLine && wordWrap,
                                )
                            }
                        }
                }
                    .collect { window ->
                        if (
                            window != null && latestSource == source && !window.coveredBy(reported)
                        ) {
                            reported = window.expanded
                            visibleCallback?.invoke(window.expanded)
                        }
                    }
            }
        }
        NativeEditorField(
            value = value,
            onValueChange = onValueChange,
            modifier = if (onVisibleTextRange != null) inputModifier.onSizeChanged { fallbackSize = it }
                else inputModifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine
                else TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
            password = password,
            formatter = formatter,
            onTextLayout = captureLayout,
            cursorBrush = cursorBrush,
            decorator = decorationBox,
        )
        return
    }
    val realWindow = LocalWindowInfo.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible by rememberUpdatedState(WindowInsets.ime.getBottom(density) > 0)
    val focusRequester = remember { FocusRequester() }
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val bringIntoView = remember { BringIntoViewRequester() }
    val toolbar = LocalTextToolbar.current
    val modern = LocalTextContextMenuToolbarProvider.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val editor =
        remember(host, scroll, toolbar, modern, clipboard) {
            GlasmioTextEditor(host, scope, scroll, toolbar, modern, clipboard, value)
        }
    val colors = LocalGlassColors.current
    SideEffect {
        if (editor.value.text != value.text) {
            editor.showHandles = false
            editor.dismissMenu()
        }
        editor.value = value
        editor.change = onValueChange
        editor.mapping = presentation
        editor.enabled = enabled
        editor.readOnly = readOnly
        editor.password = password
        editor.realWindowFocused = realWindow.isWindowFocused
        editor.density = density
        editor.singleLine = singleLine
        editor.reverseScroll = singleLine && layoutDirection == LayoutDirection.Rtl
        editor.topViewportInsetPx = with(density) { topViewportInset.toPx() }
        editor.horizontalScroll = if (!singleLine && !wordWrap) horizontalScroll else null
    }
    DisposableEffect(editor) {
        onDispose {
            editor.dismiss()
            if (host.active === editor) host.active = null
        }
    }
    LaunchedEffect(
        editor,
        enabled,
        editor.focused,
        realWindow.isWindowFocused,
        host.menuOwnsFocus,
    ) {
        val ownedMenu = host.menuOwnsFocus && host.active === editor
        if (!enabled) editor.dismiss()
        else if (!ownedMenu && (!editor.focused || !realWindow.isWindowFocused)) {
            // Closing our popup and restoring its owner window can land in the same frame.
            withFrameNanos {}
            if (!editor.chromeVisible) editor.dismiss()
        }
    }
    LaunchedEffect(editor, singleLine) {
        editor.dismiss()
        scroll.scrollTo(0)
    }
    LaunchedEffect(editor, value.text, value.selection, editor.focused) {
        editor.blink = true
        while (isActive && editor.focused) {
            delay(500)
            editor.blink = !editor.blink
        }
    }
    val displayedText by rememberUpdatedState(presentation.displayText)
    LaunchedEffect(editor, textStyle, density, password, enabled, readOnly) {
        editor.caretMotion.reset()
        snapshotFlow {
            // 输入回调先于新布局到达；等待匹配布局，不用旧字符几何重启动画。
            if (
                editor.value.text != latestSource ||
                    editor.layout?.layoutInput?.text?.text != displayedText
            )
                null
            else
                editor.caretRect(editor.value.selection.end)?.let { rect ->
                    TextCaretTarget(
                        rect,
                        editor.chromeVisible && !readOnly && editor.value.selection.collapsed,
                        editor.pointerScreen != null || editor.draggingHandle != null,
                    )
                }
        }
            .filterNotNull()
            .collectLatest { editor.caretMotion.update(it) }
    }
    // 仅原文或真实几何变化请求显露；颜色 spans 和相同布局结果不触发横滚。
    LaunchedEffect(
        editor,
        value.text,
        value.selection,
        editor.focused,
        revealSelection,
        wordWrap,
        lineNumbers,
        editor.viewportSize,
        topViewportInset,
        bottomViewportInset,
    ) {
        if ((editor.focused || revealSelection) && enabled && editor.pointerScreen == null) {
            withFrameNanos {}
            // 首次聚焦或换文档时等待匹配的布局，不能因布局尚未返回就丢弃定位请求。
            snapshotFlow { editor.layout?.layoutInput?.text?.text == value.text }.first { it }
            if (editor.pointerScreen == null) {
                editor.cursorRect(value.selection.end)?.let { rect ->
                    bringIntoView.bringIntoView(
                        rect.copy(
                            top = rect.top - editor.topViewportInsetPx,
                            bottom = rect.bottom + with(density) { bottomViewportInset.toPx() },
                        ),
                    )
                }
            }
        }
    }
    val searchMatch = searchHighlights?.takeIf { it.result.source == value.text }?.current
    // 搜索显露复用 Glasmio 的 bringIntoView，不抢查找框焦点，也不生成原生选区。
    LaunchedEffect(
        editor,
        value.text,
        searchMatch,
        searchHighlights?.revealRequest,
        editor.viewportSize,
        textStyle,
        wordWrap,
        topViewportInset,
        bottomViewportInset,
    ) {
        if (searchMatch == null || !enabled) return@LaunchedEffect
        withFrameNanos {}
        snapshotFlow { editor.layout?.layoutInput?.text?.text == value.text }.first { it }
        val layout = editor.layout ?: return@LaunchedEffect
        val firstLine = layout.getLineForOffset(searchMatch.start)
        val end = minOf(searchMatch.end, layout.getLineEnd(firstLine, visibleEnd = true))
        if (end > searchMatch.start) {
            val rect =
                layout
                    .getPathForRange(searchMatch.start, end)
                    .getBounds()
                    .translate(editor.textOrigin)
            // 长关键字首先显露开头；请求宽度不得超过正文视口，否则无法完整放入。
            val width = editor.viewportSize.width.toFloat().coerceAtLeast(1f)
            bringIntoView.bringIntoView(
                rect.copy(
                    top = rect.top - editor.topViewportInsetPx,
                    bottom = rect.bottom + with(density) { bottomViewportInset.toPx() },
                    right = minOf(rect.right, rect.left + width),
                ),
            )
        }
    }
    LaunchedEffect(editor, editor.draggingHandle) {
        while (isActive && editor.draggingHandle != null && editor.chromeVisible) {
            withFrameNanos {}
            editor.scrollAtEdge()
        }
    }
    val fieldModifier =
        inputModifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (editor.focused != it.isFocused) {
                    editor.focused = it.isFocused
                    if (it.isFocused) host.activate(editor)
                    else if (!host.menuOwnsFocus) editor.dismiss()
                }
            }
            .onGloballyPositioned { editor.decoration = it }
            .pointerInput(editor) {
                var previousUp: Long? = null
                var previousPosition = Offset.Zero
                var pendingKeyboard: Job? = null
                try {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        if (!editor.enabled || down.type == PointerType.Mouse)
                            return@awaitEachGesture
                        val interval = previousUp?.let { down.uptimeMillis - it }
                        val doubleTap =
                            interval != null &&
                                interval in
                                    viewConfiguration.doubleTapMinTimeMillis..viewConfiguration
                                            .doubleTapTimeoutMillis &&
                                (down.position - previousPosition).getDistance() <=
                                    viewConfiguration.touchSlop * 2f
                        pendingKeyboard?.cancel()
                        pendingKeyboard = null
                        previousUp = null
                        val screen =
                            editor.decoration
                                ?.takeIf { it.isAttached }
                                ?.localToScreen(down.position)
                        val preserveTap =
                            !doubleTap &&
                                !editor.readOnly &&
                                !keyboardVisible &&
                                screen != null &&
                                editor.selectionContains(screen)
                        // Observe DOWN without consuming it: Foundation must still receive double
                        // taps,
                        // long presses and scrolling. Only a pending single tap protects its
                        // existing
                        // range.
                        editor.preservedTapSelection =
                            if (preserveTap) editor.value.selection else null
                        editor.beginTouch()
                        var finishedNormally = false
                        var point = down.position
                        var releasedAt: Long? = null
                        var moved = false
                        var longPress = false
                        try {
                            val completed =
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                                ?: return@withTimeoutOrNull true
                                        point = change.position
                                        if (
                                            event.changes.any { it.id != down.id && it.pressed } ||
                                                (point - down.position).getDistance() >
                                                    viewConfiguration.touchSlop
                                        ) {
                                            moved = true
                                            editor.preservedTapSelection = null
                                            return@withTimeoutOrNull true
                                        }
                                        if (!change.pressed) {
                                            releasedAt = change.uptimeMillis
                                            return@withTimeoutOrNull true
                                        }
                                    }
                                    @Suppress("UNREACHABLE_CODE") true
                                }
                            if (completed == null) {
                                longPress = true
                                editor.preservedTapSelection = null
                                editor.decoration?.let {
                                    editor.updatePointer(it.localToScreen(point))
                                }
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    editor.decoration?.let {
                                        editor.updatePointer(it.localToScreen(change.position))
                                    }
                                }
                            }
                            if (releasedAt != null && !moved && !longPress) {
                                // Finish Main before applying our code-aware word boundary or
                                // releasing the
                                // single-tap guard. No native event is swallowed or synthetically
                                // replayed.
                                awaitPointerEvent(PointerEventPass.Final)
                                if (doubleTap) {
                                    editor.preservedTapSelection = null
                                    editor.decoration?.let {
                                        editor.selectWord(it.localToScreen(point))
                                    }
                                } else {
                                    previousUp = releasedAt
                                    previousPosition = point
                                    if (preserveTap) {
                                        pendingKeyboard = scope.launch {
                                            delay(viewConfiguration.doubleTapTimeoutMillis)
                                            if (
                                                editor.enabled &&
                                                    editor.focused &&
                                                    host.active === editor &&
                                                    editor.coordinates?.isAttached == true
                                            ) {
                                                focusRequester.requestFocus()
                                                keyboard?.show()
                                            }
                                        }
                                    }
                                }
                            }
                            editor.preservedTapSelection = null
                            editor.pointerScreen = null
                            editor.magnifierSource = null
                            editor.finishTouch(
                                showHandles = !moved,
                                showMenu = longPress || doubleTap,
                            )
                            finishedNormally = true
                        } finally {
                            editor.preservedTapSelection = null
                            if (!finishedNormally) editor.cancelTouch()
                        }
                    }
                } finally {
                    pendingKeyboard?.cancel()
                    editor.preservedTapSelection = null
                }
            }
    CompositionLocalProvider(
        LocalTextToolbar provides editor.legacyAdapter,
        LocalTextContextMenuToolbarProvider provides editor.modernAdapter,
        // Hide the native selection drawing without hiding real window focus from the IME.
        LocalTextSelectionColors provides TextSelectionColors(Color.Transparent, Color.Transparent),
    ) {
        NativeEditorField(
            value = value,
            onValueChange = { next ->
                val textEdited = editor.value.text != next.text
                editor.value = next
                if (!editor.touching && editor.draggingHandle == null) {
                    if (textEdited) {
                        editor.showHandles = false
                        editor.dismissMenu()
                    } else {
                        editor.showHandles = !next.selection.collapsed
                    }
                }
                onValueChange(next)
            },
            inputTransformation = remember(editor) {
                InputTransformation {
                    val preserved = editor.preservedTapSelection
                    if (preserved != null && changes.changeCount == 0 && selection.collapsed) {
                        selection = preserved
                    }
                }
            },
            modifier = fieldModifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            // The decoration owns scrolling and must see the entire text layout.
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            password = password,
            formatter = formatter,
            onTextLayout = { result, formatted ->
                editor.layout = result
                captureLayout(result, formatted)
            },
            cursorBrush = SolidColor(Color.Transparent),
            decorator = { nativeInner ->
                decorationBox {
                    val maximumHeight =
                        if (!singleLine && maxLines != Int.MAX_VALUE) {
                            // Match Foundation's reference-font line cap without clipping its inner
                            // layout.
                            val paragraph =
                                rememberTextMeasurer()
                                    .measure(
                                        AnnotatedString("HHHHHHHHHH\nHHHHHHHHHH\nHHHHHHHHHH"),
                                        style = textStyle,
                                    )
                                    .multiParagraph
                            val height =
                                paragraph.getLineHeight(0) +
                                    paragraph.getLineHeight(1) * maxOf(maxLines - 2, 0) +
                                    paragraph.getLineHeight(2) * minOf(maxLines - 1, 1)
                            with(density) { height.roundToInt().toDp() }
                        } else Dp.Infinity
                    val textMeasurer = rememberTextMeasurer()
                    val gutterSnapshot =
                        remember(editor, password, wordWrap, textStyle) {
                            mutableStateOf<TextGutterSnapshot?>(null)
                        }
                    // 栏宽沿用最近一份与正文匹配的完整快照；重建期间不缩回 1 位。
                    var gutterDigits by remember(editor) { mutableIntStateOf(1) }
                    val gutterLayout = editor.layout
                    SideEffect {
                        if (!needsLineNumbers) {
                            gutterSnapshot.value = null
                        } else if (
                            (lineIndex != null || !needsLineIndex) &&
                                gutterLayout != null &&
                                gutterLayout.layoutInput.text.text == presentation.displayText
                        ) {
                            val current =
                                if (lineIndex != null) {
                                    lineIndex.lineAt(value.selection.end)
                                } else {
                                    gutterLayout.getLineForOffset(
                                        presentation
                                            .originalToTransformed(value.selection.end)
                                            .coerceIn(0, gutterLayout.layoutInput.text.length),
                                    )
                                }
                            gutterSnapshot.value =
                                TextGutterSnapshot(
                                    gutterLayout,
                                    lineIndex,
                                    presentation,
                                    current,
                                )
                            gutterDigits =
                                lineIndex?.lineNumberDigits
                                    ?: gutterLayout.lineCount.toString().length
                        }
                    }
                    val gutterWidth =
                        if (lineNumbers && !singleLine) {
                            textMeasurer.measure("9".repeat(gutterDigits), textStyle).size.width +
                                with(density) { 24.dp.roundToPx() }
                        } else 0
                    BoxWithConstraints(
                        Modifier.heightIn(max = maximumHeight),
                        propagateMinConstraints = true,
                    ) {
                        val minimumWidth =
                            if (constraints.hasBoundedWidth) constraints.maxWidth
                            else constraints.minWidth
                        val minimumHeight = constraints.minHeight
                        // 行号仅占视口高度；未限高的父布局也不能退回全文高度。
                        val viewportHeight =
                            if (constraints.hasBoundedHeight) constraints.maxHeight
                            else view.height.coerceAtLeast(0)
                        val topPaddingPx =
                            with(density) { contentPadding.calculateTopPadding().roundToPx() }
                        val bottomPaddingPx =
                            with(density) { contentPadding.calculateBottomPadding().roundToPx() }
                        val startPaddingPx =
                            with(density) {
                                contentPadding.calculateStartPadding(layoutDirection).roundToPx()
                            }
                        val endPaddingPx =
                            with(density) {
                                contentPadding.calculateEndPadding(layoutDirection).roundToPx()
                            }
                        val textWidth =
                            (minimumWidth - gutterWidth - startPaddingPx - endPaddingPx)
                                .coerceAtLeast(0)
                        SideEffect { editor.gutterWidthPx = gutterWidth + startPaddingPx }
                        if (onVisibleTextRange != null) {
                            LaunchedEffect(
                                editor,
                                source,
                                singleLine,
                                wordWrap,
                                topPaddingPx,
                                bottomPaddingPx,
                                startPaddingPx,
                                endPaddingPx,
                                gutterWidth,
                            ) {
                                var reported: TextRange? = null
                                snapshotFlow {
                                    visibleLayout
                                        ?.takeIf { it.source == source && latestSource == source }
                                        ?.let { measured ->
                                            val viewport = editor.viewportSize
                                            if (viewport.width <= 0 || viewport.height <= 0) null
                                            else {
                                                val top =
                                                    if (singleLine) 0f else scroll.value.toFloat()
                                                val left =
                                                    if (singleLine) {
                                                        if (editor.reverseScroll)
                                                            (scroll.maxValue - scroll.value)
                                                                .toFloat()
                                                        else scroll.value.toFloat()
                                                    } else if (!wordWrap)
                                                        horizontalScroll.value.toFloat()
                                                    else 0f
                                                visibleTextWindow(
                                                    measured,
                                                    top + editor.topViewportInsetPx -
                                                        topPaddingPx -
                                                        editor.textOrigin.y,
                                                    top + viewport.height -
                                                        bottomPaddingPx -
                                                        topPaddingPx -
                                                        editor.textOrigin.y,
                                                    left - editor.textOrigin.x,
                                                    left + viewport.width -
                                                        startPaddingPx -
                                                        gutterWidth -
                                                        endPaddingPx -
                                                        editor.textOrigin.x,
                                                    !singleLine && wordWrap,
                                                )
                                            }
                                        }
                                }
                                    .collect { window ->
                                        if (
                                            window != null &&
                                                latestSource == source &&
                                                !window.coveredBy(reported)
                                        ) {
                                            reported = window.expanded
                                            visibleCallback?.invoke(window.expanded)
                                        }
                                    }
                            }
                        }
                        val viewportContent: @Composable () -> Unit = {
                            // The host supplies the same tracked Miuix factory used by the demo.
                            val overscrollFactory = LocalOverscrollFactory.current
                            val overscroll =
                                remember(overscrollFactory) {
                                    overscrollFactory?.createOverscrollEffect()
                                }
                            val scrolling =
                                if (singleLine) {
                                    Modifier.horizontalScroll(scroll, overscrollEffect = overscroll)
                                } else
                                    Modifier.verticalScroll(scroll, overscrollEffect = overscroll)
                            Layout(
                                // 在滚动布局外侧捕获有限视口，避免使用整篇文本的高度。
                                modifier =
                                    Modifier.onGloballyPositioned(editor::updateViewportCoordinates)
                                        .then(scrolling),
                                content = {
                                    if (gutterWidth > 0) {
                                        Canvas(Modifier.clipToBounds()) {
                                            val snapshot = gutterSnapshot.value ?: return@Canvas
                                            val result = snapshot.layout
                                            val visibleTop =
                                                (scroll.value - topPaddingPx - editor.textOrigin.y)
                                                    .coerceAtLeast(0f)
                                            val visibleBottom =
                                                (scroll.value + size.height -
                                                        topPaddingPx -
                                                        editor.textOrigin.y)
                                                    .coerceAtLeast(0f)
                                            val firstVisual =
                                                result.getLineForVerticalPosition(visibleTop)
                                            val lastVisual =
                                                result.getLineForVerticalPosition(visibleBottom)
                                            // 不换行且无变换时视觉行即逻辑行，直接读布局，
                                            // 不需要行首表；换行时才按行首表映射回逻辑行。
                                            val index = snapshot.index
                                            val firstLogical: Int
                                            val lastLogical: Int
                                            if (index == null) {
                                                firstLogical = firstVisual
                                                lastLogical = lastVisual
                                            } else {
                                                val lineStarts = index.starts
                                                val firstOffset =
                                                    snapshot.mapping.transformedToOriginal(
                                                        result.getLineStart(firstVisual),
                                                    )
                                                val lastOffset =
                                                    snapshot.mapping.transformedToOriginal(
                                                        result.getLineEnd(lastVisual),
                                                    )
                                                firstLogical =
                                                    lineStarts.binarySearch(firstOffset).let {
                                                        if (it >= 0) it else -it - 1
                                                    }
                                                lastLogical =
                                                    lineStarts
                                                        .binarySearch(lastOffset)
                                                        .let { if (it >= 0) it else -it - 2 }
                                                        .coerceAtMost(lineStarts.lastIndex)
                                            }
                                            for (line in firstLogical..lastLogical) {
                                                val visualLine =
                                                    if (index == null) {
                                                        line
                                                    } else {
                                                        val start = index.starts[line]
                                                        val transformed =
                                                            snapshot.mapping
                                                                .originalToTransformed(start)
                                                                .coerceIn(
                                                                    0,
                                                                    result.layoutInput.text.length,
                                                                )
                                                        result.getLineForOffset(transformed)
                                                    }
                                                val label =
                                                    textMeasurer.measure(
                                                        (line + 1).toString(),
                                                        textStyle.copy(
                                                            color =
                                                                if (line == snapshot.currentLine)
                                                                    colors.content
                                                                else colors.secondaryContent,
                                                        ),
                                                    )
                                                drawText(
                                                    label,
                                                    topLeft =
                                                        Offset(
                                                            size.width -
                                                                12.dp.toPx() -
                                                                label.size.width,
                                                            topPaddingPx +
                                                                editor.textOrigin.y +
                                                                result.getLineBaseline(visualLine) -
                                                                label.firstBaseline -
                                                                scroll.value,
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                    Box(
                                        if (!singleLine && !wordWrap)
                                            Modifier.horizontalScroll(horizontalScroll)
                                        else Modifier,
                                    ) {
                                        Box(
                                            Modifier.layout { measurable, incoming ->
                                                    // Never pass the viewport's minHeight into
                                                    // CoreTextField:
                                                    // its native text layout must retain its actual
                                                    // origin.
                                                    val placeable =
                                                        measurable.measure(
                                                            incoming.copy(
                                                                minWidth = textWidth,
                                                                minHeight = 0,
                                                                maxWidth =
                                                                    if (singleLine || !wordWrap)
                                                                        Constraints.Infinity
                                                                    else incoming.maxWidth,
                                                                maxHeight = Constraints.Infinity,
                                                            ),
                                                        )
                                                    val baseline = placeable[FirstBaseline]
                                                    val textBaseline = editor.layout?.firstBaseline
                                                    editor.textOrigin =
                                                        if (
                                                            baseline != AlignmentLine.Unspecified &&
                                                                textBaseline != null
                                                        ) {
                                                            Offset(
                                                                0f,
                                                                (baseline -
                                                                        textBaseline.roundToInt())
                                                                    .toFloat(),
                                                            )
                                                        } else Offset.Zero
                                                    layout(placeable.width, placeable.height) {
                                                        placeable.place(0, 0)
                                                    }
                                                }
                                                .onGloballyPositioned(
                                                    editor::updateContentCoordinates,
                                                )
                                                .bringIntoViewRequester(bringIntoView)
                                                .drawWithContent {
                                                    val result = editor.layout
                                                    if (
                                                        syntaxPainter != null &&
                                                            !singleLine &&
                                                            formatter == null && !password &&
                                                            result != null &&
                                                            result.layoutInput.text.text ==
                                                                value.text
                                                    ) {
                                                        val viewport =
                                                            codeTextDrawViewport(
                                                                size,
                                                                editor.viewportSize,
                                                                if (wordWrap) 0
                                                                else horizontalScroll.value,
                                                                scroll.value,
                                                                topPaddingPx,
                                                                textWidth,
                                                            )
                                                        clipRect(
                                                            viewport.left,
                                                            viewport.top,
                                                            viewport.right,
                                                            viewport.bottom,
                                                        ) {
                                                            editor.drawSelection(
                                                                this,
                                                                colors.accent,
                                                            )
                                                            syntaxPainter.draw(
                                                                scope = this,
                                                                layout = result,
                                                                syntax = syntaxOverlay,
                                                                source = value.text,
                                                                viewportTop = viewport.top,
                                                                viewportBottom = viewport.bottom,
                                                                viewportLeft = viewport.left,
                                                                viewportRight = viewport.right,
                                                                textOrigin = editor.textOrigin,
                                                                composition = value.composition,
                                                                searchHighlights = searchHighlights,
                                                                searchColor = colors.accent,
                                                                searchBorderColor = colors.content,
                                                            )
                                                        }
                                                        // nativeInner 继续测量与处理输入，但不再重复
                                                        // 绘制全文；无 token 时 painter 仍绘制可见纯文本。
                                                    } else {
                                                        editor.drawSelection(this, colors.accent)
                                                        drawContent()
                                                    }
                                                    if (
                                                        editor.chromeVisible &&
                                                            !readOnly &&
                                                            value.selection.collapsed &&
                                                            (editor.blink ||
                                                                editor.touching ||
                                                                editor.draggingHandle != null)
                                                    ) {
                                                        editor.drawnCaretRect()?.let { rect ->
                                                            drawRect(
                                                                cursorBrush,
                                                                rect.topLeft,
                                                                rect.size,
                                                            )
                                                        }
                                                    }
                                                },
                                        ) {
                                            nativeInner()
                                        }
                                    }
                                },
                            ) { measurables, incoming ->
                                val text =
                                    measurables
                                        .last()
                                        .measure(
                                            incoming.copy(
                                                minWidth = textWidth,
                                                maxWidth =
                                                    if (singleLine) Constraints.Infinity
                                                    else textWidth,
                                                minHeight = 0,
                                                maxHeight = Constraints.Infinity,
                                            ),
                                        )
                                val height =
                                    maxOf(
                                        minimumHeight,
                                        topPaddingPx + text.height + bottomPaddingPx,
                                    )
                                val gutter =
                                    if (gutterWidth > 0)
                                        measurables
                                            .first()
                                            .measure(
                                                Constraints.fixed(gutterWidth, viewportHeight),
                                            )
                                    else null
                                layout(
                                    startPaddingPx + gutterWidth + text.width + endPaddingPx,
                                    height,
                                ) {
                                    // 抵消正文滚动，保留共同的 overscroll 位移；绘制时再减去 scroll。
                                    gutter?.place(startPaddingPx, scroll.value)
                                    text.place(startPaddingPx + gutterWidth, topPaddingPx)
                                }
                            }
                        }
                        if (singleLine) viewportContent()
                        else GlasmioOverscrollHost { viewportContent() }
                    }
                }
            },
        )
    }
}

@Suppress("DEPRECATION")
internal class GlasmioTextEditor(
    val host: GlasmioTextEditingHost,
    private val scope: CoroutineScope,
    private val scroll: ScrollState,
    private val toolbar: TextToolbar,
    private val modern: TextContextMenuProvider?,
    private val clipboard: ClipboardManager,
    initial: TextFieldValue,
) {
    var value by mutableStateOf(initial)
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var preservedTapSelection: TextRange? = null
    var textOrigin by mutableStateOf(Offset.Zero)
    var horizontalScroll: ScrollState? = null
    var coordinates: LayoutCoordinates? = null
    var decoration: LayoutCoordinates? = null
    var viewport: LayoutCoordinates? = null
    var gutterWidthPx by mutableIntStateOf(0)
    var topViewportInsetPx by mutableStateOf(0f)
    var viewportSize by mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    var positionVersion by mutableIntStateOf(0)
    private var contentScreenBounds: Rect? = null
    private var viewportScreenBounds: Rect? = null
    var focused by mutableStateOf(false)
    var realWindowFocused by mutableStateOf(true)
    var enabled = true
    var readOnly = false
    var password = false
    var density: Density = Density(1f)
    var singleLine = true
    var reverseScroll = false
    var mapping = EditorTextPresentation(initial.text, password = false, singleLine = false)
    var change: (TextFieldValue) -> Unit = {}
    var touching = false
    var blink by mutableStateOf(true)
    val caretMotion = TextCaretMotion()
    var showHandles by mutableStateOf(false)
    var pointerScreen by mutableStateOf<Offset?>(null)
    var magnifierSource by mutableStateOf<Offset?>(null)
    /** Last magnified point, retained while the dismissal spring runs after the gesture ends. */
    var magnifierRetainedSource by mutableStateOf<Offset?>(null)
    var draggingHandle by mutableStateOf<Boolean?>(null)
    private var magnifierOrigin: Size? = null
    private var fixedHandleOffset: Int? = null
    val draggingSelection: Boolean
        get() = fixedHandleOffset != null

    private var nativeProvider: TextContextMenuDataProvider? = null
    private var nativeLegacy: (() -> Unit)? = null
    private var menuJob: Job? = null
    private var settleJob: Job? = null
    private var pendingMenuJob: Job? = null
    private var interactionGeneration = 0L
    val chromeVisible: Boolean
        get() =
            enabled &&
                ((focused && realWindowFocused) || (host.menuOwnsFocus && host.active === this))

    fun updateContentCoordinates(node: LayoutCoordinates) {
        coordinates = node
        val bounds =
            Rect(
                node.localToScreen(Offset.Zero),
                node.localToScreen(Offset(node.size.width.toFloat(), node.size.height.toFloat())),
            )
        if (contentScreenBounds != bounds) {
            contentScreenBounds = bounds
            positionVersion++
        }
    }

    fun updateViewportCoordinates(node: LayoutCoordinates) {
        viewport = node
        viewportSize = node.size
        val bounds =
            node
                .boundsInWindow()
                .translate(node.localToScreen(Offset.Zero) - node.localToWindow(Offset.Zero))
        // Layout callbacks also run when only the selection overlay changes. Invalidating
        // that overlay again at the same geometry creates a composition/layout feedback loop.
        if (viewportScreenBounds != bounds) {
            viewportScreenBounds = bounds
            positionVersion++
        }
    }

    val legacyAdapter =
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = toolbar.status

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
            ) =
                showMenu(
                    rect,
                    onCopyRequested,
                    onPasteRequested,
                    onCutRequested,
                    onSelectAllRequested,
                    null,
                )

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
                onAutofillRequested: (() -> Unit)?,
            ) {
                val selectAll: (() -> Unit)? =
                    if (onSelectAllRequested != null) ({ this@GlasmioTextEditor.selectAll() })
                    else null
                nativeLegacy = {
                    toolbar.showMenu(
                        rect,
                        onCopyRequested,
                        onPasteRequested,
                        onCutRequested,
                        selectAll,
                        onAutofillRequested,
                    )
                }
                if (chromeVisible) nativeLegacy?.invoke()
            }

            // The editor owns menu dismissal through its focus, touch and disposal lifecycle.
            override fun hide() = Unit
        }
    val modernAdapter =
        object : TextContextMenuProvider {
            override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
                nativeProvider =
                    object : TextContextMenuDataProvider by dataProvider {
                        override fun data() =
                            TextContextMenuData(
                                dataProvider.data().components.map { component ->
                                    if (
                                        component is TextContextMenuItem &&
                                            component.key == TextContextMenuKeys.SelectAllKey
                                    ) {
                                        TextContextMenuItem(
                                            component.key,
                                            component.label,
                                            component.leadingIcon,
                                        ) {
                                            this@GlasmioTextEditor.selectAll()
                                        }
                                    } else component
                                },
                            )
                    }
                if (chromeVisible) showNativeMenu()
                // Keep the data provider alive while Foundation owns the request.
                awaitCancellation()
            }
        }

    /** 保留原生选区路径的逐行轮廓，不能把包含换行或双向文本的路径压成包围矩形。 */
    fun drawSelection(scope: DrawScope, color: Color) =
        with(scope) {
            val result = layout ?: return@with
            if (value.selection.collapsed) return@with
            val from =
                mapping
                    .originalToTransformed(value.selection.min)
                    .coerceIn(0, result.layoutInput.text.length)
            val to =
                mapping
                    .originalToTransformed(value.selection.max)
                    .coerceIn(from, result.layoutInput.text.length)
            if (from == to) return@with
            val node = coordinates?.takeIf { it.isAttached } ?: return@with
            val viewport = viewportBounds() ?: return@with
            val top = (node.screenToLocal(viewport.topLeft) - textOrigin).y
            val bottom = (node.screenToLocal(viewport.bottomRight) - textOrigin).y
            val first = maxOf(result.getLineForOffset(from), result.getLineForVerticalPosition(top))
            val last =
                minOf(
                    result.getLineForOffset((to - 1).coerceAtLeast(from)),
                    result.getLineForVerticalPosition(bottom),
                )
            if (first > last) return@with
            val start = maxOf(from, result.getLineStart(first))
            val end = minOf(to, result.getLineEnd(last))
            if (start >= end) return@with
            val path = result.getPathForRange(start, end)
            translate(textOrigin.x, textOrigin.y) { drawPath(path, color.copy(alpha = 0.30f)) }
        }

    fun selectWord(screen: Offset) {
        val node = coordinates?.takeIf { it.isAttached } ?: return
        val result = layout ?: return
        if (value.text.isEmpty()) return
        val transformed =
            result
                .getOffsetForPosition(node.screenToLocal(screen) - textOrigin)
                .coerceIn(0, result.layoutInput.text.length)
        val boundary = result.getWordBoundary(transformed)
        var start = mapping.transformedToOriginal(boundary.min).coerceIn(0, value.text.length)
        var end = mapping.transformedToOriginal(boundary.max).coerceIn(start, value.text.length)
        val hit = mapping.transformedToOriginal(transformed).coerceIn(0, value.text.lastIndex)
        // Expand only ASCII code identifiers; CJK and punctuation retain native word boundaries.
        fun identifier(char: Char) =
            char == '_' || char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
        if (identifier(value.text[hit])) {
            start = hit
            end = hit + 1
            while (start > 0 && identifier(value.text[start - 1])) start--
            while (end < value.text.length && identifier(value.text[end])) end++
        }
        val next = value.copy(selection = TextRange(start, end))
        value = next
        change(next)
        showHandles = true
    }

    fun cursorRect(offset: Int): Rect? {
        val result = layout ?: return null
        val transformed =
            mapping
                .originalToTransformed(offset.coerceIn(0, value.text.length))
                .coerceIn(0, result.layoutInput.text.length)
        return result.getCursorRect(transformed).translate(textOrigin)
    }

    /** 在内容边界内居中绘制；不按视口钳制，避免滚动时把离屏光标吸到屏幕边缘。 */
    fun caretRect(offset: Int): Rect? {
        val base = cursorRect(offset) ?: return null
        return editingCaretRect(
            base,
            with(density) { CaretWidth.toPx() },
            with(density) { CaretHeight.toPx() },
            textOrigin.x,
            textOrigin.x + (layout?.size?.width ?: 0),
        )
    }

    fun drawnCaretRect(): Rect? =
        caretRect(value.selection.end)?.let { target ->
            caretMotion.rect(target, pointerScreen != null || draggingHandle != null)
        }

    fun drawnCaretHandleTip(root: LayoutCoordinates): Offset? {
        val node = coordinates?.takeIf { it.isAttached } ?: return null
        val rect = drawnCaretRect() ?: return null
        val tip = node.localToScreen(editingHandleTip(rect))
        val viewport = viewportBounds()
        val visibleTip =
            if (draggingHandle != null && viewport != null) {
                Offset(
                    tip.x.coerceIn(viewport.left, viewport.right),
                    tip.y.coerceIn(viewport.top, viewport.bottom),
                )
            } else tip
        return root.screenToLocal(visibleTip)
    }

    fun viewportBounds(): Rect? {
        positionVersion
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val bounds =
            view
                .boundsInWindow()
                .translate(view.localToScreen(Offset.Zero) - view.localToWindow(Offset.Zero))
        // 排除固定行号区，保留裁剪后的右边界及上下可视边界。
        val textLeft = view.localToScreen(Offset(gutterWidthPx.toFloat(), 0f)).x
        return Rect(
            maxOf(bounds.left, textLeft).coerceAtMost(bounds.right),
            (bounds.top + topViewportInsetPx).coerceAtMost(bounds.bottom),
            bounds.right,
            bounds.bottom,
        )
    }

    fun handleRect(start: Boolean): Rect? {
        positionVersion
        val node = coordinates?.takeIf { it.isAttached } ?: return null
        val rect =
            if (value.selection.collapsed) caretRect(value.selection.end)
            else cursorRect(if (start) value.selection.start else value.selection.end)
        if (rect == null) return null
        return Rect(node.localToScreen(rect.topLeft), node.localToScreen(rect.bottomRight))
    }

    fun selectionBounds(): Rect? {
        val start = handleRect(true) ?: return null
        val end = handleRect(false) ?: return null
        val viewport = viewportBounds() ?: return null
        val bounds =
            if (!singleLine && !value.selection.collapsed) {
                val result = layout ?: return null
                val node = coordinates?.takeIf { it.isAttached } ?: return null
                val firstLine =
                    result.getLineForVerticalPosition(
                        (node.screenToLocal(viewport.topLeft) - textOrigin).y,
                    )
                val lastLine =
                    result.getLineForVerticalPosition(
                        (node.screenToLocal(viewport.bottomRight) - textOrigin).y,
                    )
                val from =
                    maxOf(
                        mapping.originalToTransformed(value.selection.min),
                        result.getLineStart(firstLine),
                    )
                val to =
                    minOf(
                        mapping.originalToTransformed(value.selection.max),
                        result.getLineEnd(lastLine),
                    )
                if (from >= to) return null
                val local = result.getPathForRange(from, to).getBounds().translate(textOrigin)
                Rect(node.localToScreen(local.topLeft), node.localToScreen(local.bottomRight))
                    .intersect(viewport)
            } else {
                Rect(
                    minOf(start.left, end.left),
                    minOf(start.top, end.top),
                    maxOf(start.right, end.right),
                    maxOf(start.bottom, end.bottom),
                )
            }
        val handleHeight =
            if (showHandles) {
                val shape = editingHandleShape(value.selection, false)
                val radius = with(density) { HandleRadius.toPx() }
                editingHandleHeadOffset(shape, radius).y + radius
            } else 0f
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom + handleHeight)
    }

    /** 命中原生选区路径，换行和双向文本留下的空白不算选区内部。 */
    fun selectionContains(screen: Offset): Boolean {
        if (value.selection.collapsed) return false
        val node = coordinates?.takeIf { it.isAttached } ?: return false
        val result = layout ?: return false
        if (viewportBounds()?.contains(screen) != true) return false
        val point = node.screenToLocal(screen) - textOrigin
        val line = result.getLineForVerticalPosition(point.y)
        val start =
            maxOf(mapping.originalToTransformed(value.selection.min), result.getLineStart(line))
        val end = minOf(mapping.originalToTransformed(value.selection.max), result.getLineEnd(line))
        return start < end && point in PathHitTester(result.getPathForRange(start, end))
    }

    fun updatePointer(screen: Offset) {
        pointerScreen = screen
        val node = coordinates?.takeIf { it.isAttached } ?: return
        val viewport = viewportBounds() ?: return
        val point =
            Offset(
                screen.x.coerceIn(viewport.left, viewport.right),
                screen.y.coerceIn(viewport.top, viewport.bottom),
            )
        val local = node.screenToLocal(point) - textOrigin
        val result = layout ?: return
        val offset = result.getOffsetForPosition(local)
        val cursor = result.getCursorRect(offset.coerceIn(0, result.layoutInput.text.length))
        magnifierSource =
            node
                .localToScreen(Offset(local.x, cursor.center.y) + textOrigin)
                .copy(
                    x = point.x,
                    y =
                        node
                            .localToScreen(cursor.center + textOrigin)
                            .y
                            .coerceIn(viewport.top, viewport.bottom),
                )
        magnifierOrigin = cursor.size
        magnifierRetainedSource = magnifierSource
    }

    /**
     * Size of the shape the lens grows out of: the caret rect, as UIKit's `originShape` does. The
     * layout reports a zero-width caret because only the drawn stroke has width, so the shape keeps
     * the 2 dp the cursor is painted with.
     */
    fun magnifierOriginSize(): Size? {
        val size = magnifierOrigin ?: return null
        return with(density) {
            Size(
                maxOf(size.width, 2.dp.toPx()).toDp().value,
                maxOf(size.height, 1.dp.toPx()).toDp().value,
            )
        }
    }

    /** UIKit refuses to open a loupe whose selection widget is taller than the lens body. */
    fun allowsMagnifier(): Boolean {
        val size = magnifierOrigin ?: return false
        return with(density) { size.height.toDp() } <= CapsuleLoupeHeight
    }

    /** Clear the retained point once the dismissal spring has landed. */
    fun clearRetainedMagnifier() {
        if (magnifierSource == null) magnifierRetainedSource = null
    }

    fun dragHandle(start: Boolean, screen: Offset) {
        val node = coordinates?.takeIf { it.isAttached } ?: return
        val result = layout ?: return
        updatePointer(screen)
        val viewport = viewportBounds() ?: return
        val point =
            Offset(
                screen.x.coerceIn(viewport.left, viewport.right),
                screen.y.coerceIn(viewport.top, viewport.bottom),
            )
        val transformed = result.getOffsetForPosition(node.screenToLocal(point) - textOrigin)
        val original =
            mapping
                .transformedToOriginal(transformed.coerceIn(0, result.layoutInput.text.length))
                .coerceIn(0, value.text.length)
        val selection =
            if (draggingHandle != null) {
                val fixed = fixedHandleOffset
                if (fixed == null) TextRange(original)
                else if (start) TextRange(original, fixed) else TextRange(fixed, original)
            } else if (value.selection.collapsed) TextRange(original)
            else if (start) TextRange(original, value.selection.end)
            else TextRange(value.selection.start, original)
        val next = value.copy(selection = selection)
        value = next
        change(next)
        blink = true
    }

    fun beginHandle(start: Boolean) {
        dismissMenu()
        fixedHandleOffset =
            if (value.selection.collapsed) null
            else if (start) value.selection.end else value.selection.start
        draggingHandle = start
    }

    fun endHandle(released: Boolean) {
        if (released)
            draggingHandle?.let { handle -> pointerScreen?.let { dragHandle(handle, it) } }
        draggingHandle = null
        fixedHandleOffset = null
        pointerScreen = null
        magnifierSource = null
        if (released && chromeVisible) {
            showHandles = true
            showMenuAfterGesture(fromHandle = true)
        }
    }

    fun beginTouch() {
        dismissMenu()
        touching = true
    }

    fun cancelTouch() {
        touching = false
        pointerScreen = null
        magnifierSource = null
        dismissMenu()
    }

    fun finishTouch(showHandles: Boolean, showMenu: Boolean) {
        val generation = interactionGeneration
        settleJob?.cancel()
        settleJob = scope.launch {
            // Initial-pass observation sees UP before Foundation handles it on Main.
            withFrameNanos {}
            if (generation != interactionGeneration || !chromeVisible) return@launch
            touching = false
            this@GlasmioTextEditor.showHandles = showHandles
            if (showMenu) showMenuAfterGesture()
        }
    }

    suspend fun scrollAtEdge() {
        val handle = draggingHandle ?: return
        val pointer = pointerScreen ?: return
        // Placement catches up with the preceding scroll on this frame, including at the limit.
        dragHandle(handle, pointer)
        val bounds = viewportBounds() ?: return
        val edge = with(density) { 24.dp.toPx() }
        val position = if (singleLine) pointer.x else pointer.y
        val minimum = if (singleLine) bounds.left else bounds.top
        val maximum = if (singleLine) bounds.right else bounds.bottom
        val speed = with(density) { 12.dp.toPx() }
        val delta =
            when {
                position < minimum + edge ->
                    -((minimum + edge - position) / edge).coerceIn(0f, 1f) * speed
                position > maximum - edge ->
                    ((position - maximum + edge) / edge).coerceIn(0f, 1f) * speed
                else -> 0f
            }
        if (delta != 0f) scroll.scrollBy(if (reverseScroll) -delta else delta)
        horizontalScroll?.let { horizontal ->
            val dx =
                when {
                    pointer.x < bounds.left + edge ->
                        -((bounds.left + edge - pointer.x) / edge).coerceIn(0f, 1f) * speed
                    pointer.x > bounds.right - edge ->
                        ((pointer.x - bounds.right + edge) / edge).coerceIn(0f, 1f) * speed
                    else -> 0f
                }
            if (dx != 0f) horizontal.scrollBy(dx)
        }
    }

    fun dismissMenu() {
        interactionGeneration++
        settleJob?.cancel()
        settleJob = null
        pendingMenuJob?.cancel()
        pendingMenuJob = null
        menuJob?.cancel()
        menuJob = null
        toolbar.hide()
    }

    fun dismiss() {
        dismissMenu()
        touching = false
        pointerScreen = null
        magnifierSource = null
        magnifierRetainedSource = null
        showHandles = false
        draggingHandle = null
        fixedHandleOffset = null
    }

    fun showMenuAfterGesture(fromHandle: Boolean = false) {
        val generation = interactionGeneration
        pendingMenuJob?.cancel()
        pendingMenuJob = scope.launch {
            withFrameNanos {}
            if (
                generation != interactionGeneration ||
                    !chromeVisible ||
                    touching ||
                    draggingHandle != null
            )
                return@launch
            if (nativeProvider != null) showNativeMenu()
            else if (!fromHandle && nativeLegacy != null) nativeLegacy?.invoke()
            else showFallbackMenu()
        }
    }

    private fun showNativeMenu() {
        val provider = nativeProvider ?: return
        val target = modern ?: return
        if (menuJob?.isActive == true) return
        menuJob = scope.launch { target.showTextContextMenu(provider) }
    }

    private fun selectAll() {
        // Selection belongs to this editor; native requests can outlive the gesture that
        // produced them while our menu closes. Read the current value and callback here.
        val next = value.copy(selection = TextRange(0, value.text.length))
        value = next
        change(next)
        showHandles = true
        showMenuAfterGesture(fromHandle = true)
    }

    private fun showFallbackMenu() {
        val bounds = handleRect(false) ?: return
        val root = coordinates?.takeIf { it.isAttached }?.let { it.findRootCoordinates() } ?: return
        val rect = Rect(root.screenToLocal(bounds.topLeft), root.screenToLocal(bounds.bottomRight))
        val selection = value.selection
        val hasSelection = !selection.collapsed
        fun selectedText() = value.text.substring(value.selection.min, value.selection.max)
        fun replace(text: String) {
            val start = value.selection.min
            val next =
                TextFieldValue(
                    value.text.replaceRange(start, value.selection.max, text),
                    TextRange(start + text.length),
                )
            value = next
            change(next)
            showHandles = false
        }
        toolbar.showMenu(
            rect,
            onCopyRequested =
                if (hasSelection && !password)
                    ({
                        clipboard.setText(AnnotatedString(selectedText()))
                        dismissMenu()
                    })
                else null,
            onPasteRequested =
                if (!readOnly && clipboard.hasText())
                    ({
                        clipboard.getText()?.let { replace(it.text) }
                        dismissMenu()
                    })
                else null,
            onCutRequested =
                if (!readOnly && hasSelection && !password)
                    ({
                        clipboard.setText(AnnotatedString(selectedText()))
                        replace("")
                        dismissMenu()
                    })
                else null,
            onSelectAllRequested =
                if (value.text.isNotEmpty() && selection.length < value.text.length) ::selectAll
                else null,
        )
    }
}

@Composable
internal fun GlasmioTextEditingOverlay(host: GlasmioTextEditingHost, backdrop: Backdrop) {
    val editor = host.active ?: return
    val root = host.coordinates?.takeIf { it.isAttached } ?: return
    if (!editor.chromeVisible) return
    val viewport = editor.viewportBounds() ?: return
    // 三种水滴都贴住文字下边界；拖动越过另一端时保留端点身份并翻转朝向。
    val handles =
        if (editor.showHandles && (!editor.readOnly || !editor.value.selection.collapsed)) {
            val ends =
                if (editor.value.selection.collapsed && !editor.draggingSelection) listOf(false)
                else listOf(true, false)
            ends.mapNotNull { editingHandle(editor, root, viewport, it) }
        } else emptyList()
    val handlesBackdrop = rememberLayerBackdrop()
    // Record the actual widgets at their laid-out positions. The loupe samples this layer with the
    // same coordinate mapping as the text, so both stay attached through all lens transforms.
    Box(Modifier.fillMaxSize().layerBackdrop(handlesBackdrop, reuseContent = true)) {
        handles.forEach { handle ->
            key(editor, handle.start) {
                GlasmioEditingHandle(editor, root, handle.start, handle.anchor)
            }
        }
    }

    val liveSource = editor.magnifierSource
    val source = liveSource ?: editor.magnifierRetainedSource ?: return
    val originSize = editor.magnifierOriginSize() ?: return
    if (!editor.allowsMagnifier()) return
    val combined =
        remember(backdrop, host.recording, handlesBackdrop) {
            object : Backdrop {
                override val isCoordinatesDependent: Boolean
                    get() =
                        backdrop.isCoordinatesDependent ||
                            host.recording.isCoordinatesDependent ||
                            handlesBackdrop.isCoordinatesDependent

                override fun DrawScope.drawBackdrop(
                    density: Density,
                    coordinates: LayoutCoordinates?,
                    layerBlock: (GraphicsLayerScope.() -> Unit)?,
                ) {
                    with(backdrop) { drawBackdrop(density, coordinates, layerBlock) }
                    with(host.recording) { drawBackdrop(density, coordinates, layerBlock) }
                    with(handlesBackdrop) { drawBackdrop(density, coordinates, layerBlock) }
                }
            }
        }
    GlasmioMagnifier(
        visible = liveSource != null,
        sourcePosition = root.screenToLocal(source),
        backdrop = combined,
        originSize = DpSize(originSize.width.dp, originSize.height.dp),
        lensSize = DpSize(132.dp, CapsuleLoupeHeight),
        verticalOffset = 44.dp,
        // 默认 motion 是 iOS 镜头观感（约 35dp 滞后），拖选时明显不跟手，这里收紧到单帧级跟随。
        motion =
            GlasmioLoupeMotion(
                followLowPass = 1f,
                followDampingRatio = 1f,
                followResponseSeconds = 0.08f,
            ),
        // A caret and a selection widget replay the opening passes when they swap mid-gesture.
        identity = editor.value.selection.collapsed,
        tracking = liveSource != null,
        onDismissed = editor::clearRetainedMagnifier,
    )
}

@Composable
private fun GlasmioEditingHandle(
    editor: GlasmioTextEditor,
    root: LayoutCoordinates,
    start: Boolean,
    anchor: Offset,
) {
    val density = LocalDensity.current
    val color = LocalGlassColors.current.accent
    val halfTouch = with(density) { 24.dp.toPx() }
    val radius = with(density) { HandleRadius.toPx() }
    val collapsed = editor.value.selection.collapsed
    val shape = editingHandleShape(editor.value.selection, start)
    val tip = Offset(halfTouch, halfTouch) - editingHandleHeadOffset(shape, radius)
    val path = remember(shape, radius) { editingHandlePath(shape, radius) }
    Canvas(
        Modifier.absoluteOffset {
                // 只在布局阶段读取逐帧位置，光标与水滴尖端同步而不重组整份正文。
                val position = if (collapsed) editor.drawnCaretHandleTip(root) ?: anchor else anchor
                IntOffset((position.x - tip.x).roundToInt(), (position.y - tip.y).roundToInt())
            }
            .size(48.dp)
            .semantics {
                contentDescription =
                    if (editor.value.selection.collapsed) "光标" else if (start) "选区起点" else "选区终点"
            }
            .pointerInput(editor, start) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    editor.beginHandle(start)
                    var released = false
                    try {
                        val rect = editor.handleRect(start) ?: return@awaitEachGesture
                        val original = rect.center
                        var point = original
                        editor.updatePointer(point)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            point += change.positionChange()
                            editor.dragHandle(start, point)
                            change.consume()
                        }
                    } finally {
                        editor.endHandle(released)
                    }
                }
            },
    ) {
        translate(tip.x, tip.y) { drawPath(path, color) }
    }
}

/** A drawable selection widget, in overlay pixels. */
private class EditingHandle(val start: Boolean, val anchor: Offset)

/**
 * Where one widget sits, or null when UIKit would not show it. The widget the finger holds stays
 * drawn outside the field; the idle one is only shown while its line is still on screen.
 */
private fun editingHandle(
    editor: GlasmioTextEditor,
    root: LayoutCoordinates,
    viewport: Rect,
    start: Boolean,
): EditingHandle? {
    val rect = editor.handleRect(start) ?: return null
    val edge = rect.bottom
    val anchor = editingHandleTip(rect)
    val dragging = editor.draggingHandle == start
    if (
        !dragging &&
            !(anchor.x in viewport.left..viewport.right &&
                edge in (viewport.top - 1f)..(viewport.bottom + 1f))
    )
        return null
    val visibleAnchor =
        if (dragging) {
            Offset(
                anchor.x.coerceIn(viewport.left, viewport.right),
                edge.coerceIn(viewport.top, viewport.bottom),
            )
        } else anchor
    return EditingHandle(start, root.screenToLocal(visibleAnchor))
}
