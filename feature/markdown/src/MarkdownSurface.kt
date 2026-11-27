package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.RetainVisibleMarkdownCard
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingOverlay
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

/** One document slot for body cards, READMEs and retained editor previews, independent of backend. */
@Composable
internal fun MarkdownSurface(
    content: GithubMarkdownContent,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    repository: GithubMarkdownRepository,
    isCurrentAccount: () -> Boolean,
    visible: Boolean = true,
    hostActive: Boolean = true,
    native: Boolean = false,
    viewport: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val page = LocalGithubPageHost.current
    val active = hostActive && page?.isCurrent != false
    val routeReady = LocalGithubRouteEntryReady.current
    // A hidden editor preview keeps its DOM/scroll, but does not follow edits or fetch a draft.
    var lastVisible by remember { mutableStateOf(content) }
    if (visible && content != lastVisible) SideEffect { lastVisible = content }
    val request = if (visible) content else lastVisible
    val state =
        rememberNativeMarkdownDocument(
            request,
            repository,
            isCurrentAccount,
            requestEnabled = routeReady && visible && active,
        )
    val strings = LocalGithubStrings.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val direction = LocalLayoutDirection.current
    val font = GlasmioTheme.typography.bodyLarge
    val background = if (viewport && !content.transparentBackground) GlasmioTheme.colors.elevatedBackground else Color.Transparent
    var inViewport by remember { mutableStateOf(false) }
    val frameModifier = modifier
        .then(if (viewport) Modifier.background(background) else Modifier)
        .then(if (visible && active) Modifier else Modifier.clearAndSetSemantics {})
        .onGloballyPositioned { inViewport = !it.boundsInWindow().isEmpty }
    BoxWithConstraints(frameModifier, propagateMinConstraints = true) {
        val width = constraints.maxWidth
        var attempt by remember(state.revision) { mutableIntStateOf(0) }
        // Changing width/scale invalidates the instance's drawable receipt, not its data.
        key(state.revision, width, density, direction, configuration, font, attempt) {
            var drawable by remember { mutableStateOf(false) }
            var renderFailed by remember { mutableStateOf(false) }
            val document = state.document
            val style =
                if (native && routeReady)
                    rememberNativeMarkdownStyles(request.transparentBackground, state.revision)
                else null
            val failure = when {
                renderFailed || style?.failed == true || state.failure == NativeMarkdownFailure.PARSE -> strings.Markdown.RenderingFailed
                state.failure == NativeMarkdownFailure.FETCH -> if (viewport) strings.Markdown.PreviewLoadFailed else strings.Markdown.LoadFailed
                else -> null
            }
            val bodyVisible = visible && active && inViewport && drawable && failure == null
            if (!viewport) RetainVisibleMarkdownCard(bodyVisible)
            GithubLoadingOverlay(
                loading = !routeReady || !drawable && failure == null,
                documentKey = state.revision,
                layoutWidth = width,
                layoutKey = native,
                geometry = document?.geometry?.takeIf { failure == null && !viewport },
                modifier = if (viewport) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                visible = visible && inViewport,
                onPresented = if (failure == null) actions.onReady else null,
                placeholder = {
                    if (viewport) GithubMarkdownPlaceholder(Modifier.padding(contentPadding).padding(16.dp))
                    else GithubMarkdownPlaceholder(compact = true)
                },
            ) {
                if (!routeReady) return@GithubLoadingOverlay
                if (failure != null) {
                    Box(Modifier.padding(contentPadding)) {
                        GlasmioListRow(
                            title = failure,
                            detail = strings.Common.Retry,
                            showDisclosure = false,
                            enabled = visible && active,
                            onClick = {
                                if (isCurrentAccount() && page?.isCurrent != false) {
                                    if (state.failure != null) state.retry() else attempt++
                                }
                            },
                        )
                    }
                } else if (document != null) {
                    val ready: (NativeMarkdownRevision) -> Unit = { revision ->
                        if (isCurrentAccount() && revision === state.revision) drawable = true
                    }
                    val failed: (NativeMarkdownRevision) -> Unit = { revision ->
                        if (isCurrentAccount() && revision === state.revision) renderFailed = true
                    }
                    val size = if (viewport) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                    if (!native) {
                        MarkdownWebDocument(state, document, size, actions, isCurrentAccount,
                            visible = bodyVisible, viewport = viewport, contentPadding = contentPadding,
                            scrollToAnchor = request.scrollToAnchor, onReady = ready, onRenderFailure = failed)
                    } else if (style?.styles != null) {
                        NativeMarkdownBoundContent(state, document, style.styles, size, actions, isCurrentAccount,
                            visible = bodyVisible, viewport = viewport, contentPadding = contentPadding,
                            selectionHosted = content.selectionHosted, scrollToAnchor = request.scrollToAnchor,
                            onReady = ready, onRenderFailure = failed)
                    }
                }
            }
        }
    }
}
