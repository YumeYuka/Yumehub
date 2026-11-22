package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingOverlay
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder

/** 隐藏时保留文档和滚动；正文与预览共用原生 / WebView 分派，等待真实首帧后显示。 */
@Composable
internal fun NativeMarkdownPreviewPane(
    content: GithubMarkdownContent,
    visible: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    repository: GithubMarkdownRepository,
    isCurrentAccount: () -> Boolean,
) {
    val strings = LocalGithubStrings.current
    val colors = GlasmioTheme.colors
    var lastVisibleRequest by remember { mutableStateOf(content) }
    SideEffect { if (visible) lastVisibleRequest = content }
    val request = if (visible) content else lastVisibleRequest
    val state =
        rememberNativeMarkdownDocument(
            content = request,
            repository = repository,
            isCurrentAccount = isCurrentAccount,
            requestEnabled = visible,
        )
    val style =
        rememberNativeMarkdownStyles(
            transparentBackground = content.transparentBackground,
            retryKey = state.revision,
        )
    val background =
        if (content.transparentBackground) Color.Transparent else colors.elevatedBackground
    var readyRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    var failedRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    val shown = state.document
    val revealed =
        visible &&
            shown != null &&
            state.allowsInteraction(shown) &&
            readyRevision === shown.revision &&
            failedRevision !== shown.revision &&
            !style.failed &&
            style.styles != null
    val failureMessage =
        when {
            style.failed ||
                state.failure == NativeMarkdownFailure.PARSE ||
                failedRevision === state.revision -> strings.Markdown.RenderingFailed
            state.failure == NativeMarkdownFailure.FETCH -> strings.Markdown.PreviewLoadFailed
            else -> null
        }
    Box(
        modifier
            .background(background)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        val styles = style.styles
        GithubLoadingOverlay(
            loading = !revealed,
            modifier = Modifier.matchParentSize(),
            placeholder = {
                if (failureMessage == null) {
                    Box(
                        Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            }
                            .padding(contentPadding)
                            .padding(16.dp),
                    ) {
                        GithubMarkdownPlaceholder(Modifier.fillMaxSize())
                    }
                }
            },
        ) {
            if (shown != null && styles != null) {
                NativeMarkdownBoundContent(
                    state = state,
                    document = shown,
                    style = styles,
                    // 覆盖层内的正文容器需要由子项撑开，不能用 matchParentSize 等待父项定尺寸。
                    modifier =
                        Modifier.fillMaxSize()
                            .then(if (revealed) Modifier else Modifier.clearAndSetSemantics {}),
                    actions = actions,
                    isCurrentAccount = isCurrentAccount,
                    visible = visible && revealed,
                    viewport = true,
                    contentPadding = contentPadding,
                    scrollToAnchor = request.scrollToAnchor,
                    onReady = { revision ->
                        if (revision === state.revision && isCurrentAccount()) {
                            readyRevision = revision
                        }
                    },
                    onRenderFailure = { revision ->
                        if (revision === state.revision && isCurrentAccount()) {
                            failedRevision = revision
                        }
                    },
                )
            }
        }
        if (visible && failureMessage != null && isCurrentAccount())
            GithubLoadFailureToast(state.failure ?: failureMessage, failureMessage)
    }
}
