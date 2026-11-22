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
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingOverlay
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder

/** 隐藏时保留 DOM 和滚动位置，只在可见草稿变化时请求新的官方 HTML。 */
@Composable
internal fun MarkdownPreviewPane(
    content: GithubMarkdownContent,
    visible: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    repository: GithubMarkdownRepository,
    isCurrentAccount: () -> Boolean,
) {
    val strings = LocalGithubStrings.current.Markdown
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
    var readyRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    var failedRevision by remember { mutableStateOf<NativeMarkdownRevision?>(null) }
    val document = state.document
    val revealed =
        document != null &&
            state.owns(document) &&
            readyRevision === document.revision &&
            failedRevision !== document.revision
    val failureMessage =
        when {
            failedRevision === state.revision || state.failure == NativeMarkdownFailure.PARSE ->
                strings.RenderingFailed
            state.failure == NativeMarkdownFailure.FETCH -> strings.PreviewLoadFailed
            else -> null
        }
    Box(
        modifier.background(
            if (content.transparentBackground) Color.Transparent else colors.elevatedBackground,
        ),
    ) {
        GithubLoadingOverlay(
            loading = !revealed,
            modifier = Modifier.matchParentSize(),
            placeholder = {
                if (failureMessage == null) {
                    Box(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
                        GithubMarkdownPlaceholder(Modifier.fillMaxSize())
                    }
                }
            },
        ) {
            if (document != null) {
                MarkdownWebDocument(
                    state = state,
                    document = document,
                    modifier = Modifier.fillMaxSize(),
                    actions = actions,
                    isCurrentAccount = isCurrentAccount,
                    visible = visible && revealed,
                    viewport = true,
                    contentPadding = contentPadding,
                    scrollToAnchor = request.scrollToAnchor,
                    onReady = { revision ->
                        if (revision === state.revision && isCurrentAccount())
                            readyRevision = revision
                    },
                    onRenderFailure = { revision -> failedRevision = revision },
                )
            }
        }
        if (visible && failureMessage != null && isCurrentAccount())
            GithubLoadFailureToast(state.failure ?: failureMessage, failureMessage)
    }
}
