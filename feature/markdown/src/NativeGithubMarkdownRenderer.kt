package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import java.net.URI
import kotlinx.coroutines.launch
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.NativeHtmlActions
import moe.yumeyuka.markdown.compose.NativeHtmlDocument
import moe.yumeyuka.markdown.compose.NativeHtmlSelectionMode
import moe.yumeyuka.markdown.compose.NativeHtmlViewport
import moe.yumeyuka.markdown.core.isSafeHtmlMailto
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownRenderer
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingOverlay
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubDetailPresentation
import org.koin.compose.koinInject

/** 统一适配入口；图片及 HTML 专有布局交给现有 WebView，其余内容使用原生库。 */
internal object NativeGithubMarkdownRenderer : GithubMarkdownRenderer {
    override val supportsHostedSelection: Boolean = true

    @Composable
    override fun SelectionHost(
        active: Boolean,
        modifier: Modifier,
        content: @Composable (Modifier) -> Unit,
    ) {
        NativeMarkdownSelectionHost(active, modifier, content)
    }

    @Composable
    override fun Render(
        content: GithubMarkdownContent,
        modifier: Modifier,
        actions: GithubMarkdownActions,
    ) {
        val repository: GithubMarkdownRepository = koinInject()
        val session = rememberMarkdownSession(repository)
        val hostActive = LocalNativeMarkdownHostActive.current
        key(
            repository,
            session.revision,
            content.nodeId,
            content.pageUrl,
            content.repositoryRootUrl,
            content.ref,
            content.readme,
            content.preview,
            GlasmioTheme.colors.isDark,
            actions.onTaskChanged != null,
            actions.onCommitSuggestion != null,
        ) {
            val state = rememberNativeMarkdownDocument(content, repository, session.isCurrent)
            val style = rememberNativeMarkdownStyles(content.transparentBackground, state.revision)
            val strings = LocalGithubStrings.current
            val presentation = LocalGithubDetailPresentation.current
            val renderToken = remember(state.revision) { Any() }
            DisposableEffect(presentation, renderToken) {
                presentation?.documentVisible(renderToken, false)
                onDispose { presentation?.removeDocument(renderToken) }
            }
            var renderFailure by remember(state.revision) { mutableStateOf(false) }
            val document = state.document
            val styles = style.styles
            // 缓存命中的原生正文可随本次布局直接绘制；新建 WebView 仍必须等待可绘制首帧。
            var readyRevision by remember {
                mutableStateOf(
                    document
                        ?.takeIf { styles != null && !needsWebMarkdownLayout(it.content) }
                        ?.revision,
                )
            }
            val revealed = document != null && readyRevision === document.revision
            LaunchedEffect(presentation, renderToken, revealed, state.revision) {
                if (revealed && document?.revision === state.revision && session.isCurrent()) {
                    presentation?.documentReady(renderToken)
                }
            }
            val failureMessage =
                when {
                    style.failed || renderFailure || state.failure == NativeMarkdownFailure.PARSE ->
                        strings.Markdown.RenderingFailed
                    state.failure == NativeMarkdownFailure.FETCH -> strings.Markdown.LoadFailed
                    else -> null
                }
            LaunchedEffect(failureMessage, presentation, renderToken) {
                if (failureMessage != null)
                    presentation?.documentFailed(renderToken) {
                        if (session.isCurrent()) state.retry()
                    }
            }
            var documentBounds by remember(renderToken) { mutableStateOf(Rect.Zero) }
            val viewport = presentation?.viewport
            LaunchedEffect(presentation, renderToken, viewport, documentBounds) {
                if (presentation != null && viewport != null) {
                    presentation.documentVisible(renderToken, documentBounds.overlaps(viewport))
                }
            }
            Column(modifier.onGloballyPositioned { documentBounds = it.boundsInWindow() }) {
                GithubLoadingOverlay(
                    loading = !revealed,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(
                                min =
                                    if (!revealed && failureMessage == null)
                                        PlaceholderTokens.ReadmeMinHeight
                                    else 0.dp,
                            ),
                    placeholder = {
                        if (failureMessage == null) {
                            GithubMarkdownPlaceholder(compact = true)
                        }
                    },
                ) {
                    if (document != null && styles != null) {
                        NativeMarkdownBoundContent(
                            state = state,
                            document = document,
                            style = styles,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (revealed) Modifier
                                        else Modifier.clearAndSetSemantics {},
                                    ),
                            actions = actions,
                            isCurrentAccount = session.isCurrent,
                            visible =
                                hostActive &&
                                    revealed &&
                                    !renderFailure &&
                                    presentation?.interactive != false,
                            selectionHosted = content.selectionHosted,
                            scrollToAnchor = content.scrollToAnchor,
                            onReady = { revision ->
                                if (revision === state.revision && session.isCurrent()) {
                                    readyRevision = revision
                                    presentation?.documentReady(renderToken)
                                }
                            },
                            onRenderFailure = { revision ->
                                if (revision === state.revision) renderFailure = true
                            },
                        )
                    }
                }
                if (failureMessage != null && session.isCurrent())
                    GithubLoadFailureToast(state.failure ?: failureMessage, failureMessage)
            }
        }
    }

    @Composable
    override fun Preview(
        content: GithubMarkdownContent,
        visible: Boolean,
        contentPadding: PaddingValues,
        modifier: Modifier,
        actions: GithubMarkdownActions,
    ) {
        val repository: GithubMarkdownRepository = koinInject()
        val session = rememberMarkdownSession(repository)
        val hostActive = LocalNativeMarkdownHostActive.current
        key(
            repository,
            session.revision,
            content.pageUrl,
            content.repositoryRootUrl,
            GlasmioTheme.colors.isDark,
            actions.onTaskChanged != null,
            actions.onCommitSuggestion != null,
        ) {
            NativeMarkdownPreviewPane(
                content = content,
                visible = visible && hostActive,
                contentPadding = contentPadding,
                modifier = modifier,
                actions = actions,
                repository = repository,
                isCurrentAccount = session.isCurrent,
            )
        }
    }
}

/** 所有库回调都在适配边界重新核对会话、文档和 HTML 版本。 */
@Composable
internal fun NativeMarkdownBoundContent(
    state: NativeMarkdownDocumentState,
    document: NativeMarkdownDocument,
    style: HtmlStyles,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    isCurrentAccount: () -> Boolean,
    visible: Boolean = true,
    viewport: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    scrollToAnchor: String? = null,
    onReady: (NativeMarkdownRevision) -> Unit = {},
    onRenderFailure: (NativeMarkdownRevision) -> Unit = {},
    selectionHosted: Boolean = false,
): NativeMarkdownBinding {
    val binding =
        rememberNativeMarkdownBinding(
            state = state,
            document = document,
            actions = actions,
            isCurrentAccount = isCurrentAccount,
            visible = visible,
            scrollToAnchor = scrollToAnchor,
            onReady = onReady,
            onRenderFailure = onRenderFailure,
        )
    val webLayout = remember(document.content) { needsWebMarkdownLayout(document.content) }
    if (webLayout) {
        MarkdownWebContent(
            content = document.request.copy(scrollToAnchor = binding.scrollToAnchor),
            requested = state.request,
            allowInteraction = binding.allowsInteraction(),
            html = document.html,
            style = style,
            modifier = modifier,
            actions =
                GithubMarkdownActions(
                    onOpenLink = binding.actions.onOpenLink,
                    onTaskChanged =
                        actions.onTaskChanged?.let {
                            { position, checked ->
                                binding.actions.onTaskChanged?.invoke(position, checked)
                            }
                        },
                    onCommitSuggestion =
                        actions.onCommitSuggestion?.let {
                            { id, html -> binding.actions.onCommitSuggestion?.invoke(id, html) }
                        },
                ),
            isCurrentAccount = binding.ownsDocument,
            onReady = binding.actions.onReady,
            viewportScrolling = viewport,
            contentPadding = contentPadding,
            visible = visible,
            resourceRevision = document.revision,
            viewerLogin = document.viewerLogin,
            onRenderFailure = { if (binding.ownsDocument()) onRenderFailure(document.revision) },
        )
        return binding
    }
    val resumed = rememberNativeMarkdownResumed()
    val slots =
        rememberNativeMarkdownSlots(
            isCurrentDocument = binding.ownsDocument,
            isInteractive = binding.allowsInteraction,
            mediaActive = binding.allowsInteraction() && resumed,
            resourceRevision = document.revision,
        )
    if (viewport) {
        NativeHtmlViewport(
            content = document.content,
            style = style,
            modifier = modifier,
            actions = binding.actions,
            slots = slots,
            contentPadding = contentPadding,
            scrollToAnchor = binding.scrollToAnchor,
            documentRevision = document.revision,
        )
    } else {
        NativeHtmlDocument(
            content = document.content,
            style = style,
            modifier = modifier,
            actions = binding.actions,
            slots = slots,
            scrollToAnchor = binding.scrollToAnchor,
            documentRevision = document.revision,
            selectionMode =
                if (selectionHosted) NativeHtmlSelectionMode.Inherited
                else NativeHtmlSelectionMode.Own,
        )
    }
    return binding
}

internal class NativeMarkdownBinding(
    val actions: NativeHtmlActions,
    val ownsDocument: () -> Boolean,
    val allowsInteraction: () -> Boolean,
    val scrollToAnchor: String?,
)

/** 绑定同时拥有控件回调与失效条件，旧业务事件不依赖 Compose Modifier 的存活状态。 */
@Composable
internal fun rememberNativeMarkdownBinding(
    state: NativeMarkdownDocumentState,
    document: NativeMarkdownDocument,
    actions: GithubMarkdownActions,
    isCurrentAccount: () -> Boolean,
    visible: Boolean,
    scrollToAnchor: String?,
    onReady: (NativeMarkdownRevision) -> Unit,
    onRenderFailure: (NativeMarkdownRevision) -> Unit,
): NativeMarkdownBinding {
    val latestState by rememberUpdatedState(state)
    val latestAccount by rememberUpdatedState(isCurrentAccount)
    val latestActions by rememberUpdatedState(actions)
    val latestVisible by rememberUpdatedState(visible)
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onRenderFailure)
    val scope = rememberCoroutineScope()
    val mount = remember { NativeMarkdownMount() }
    DisposableEffect(mount) { onDispose { mount.active = false } }
    var anchor by remember { mutableStateOf<String?>(null) }
    var documentFatal by remember(document.revision) { mutableStateOf(false) }
    var laidOut by remember(document.revision) { mutableStateOf(false) }
    var readyDelivered by remember(document.revision) { mutableStateOf(false) }
    val ownsDocument = { mount.active && latestAccount() && latestState.owns(document) }
    val allowsInteraction = {
        ownsDocument() && latestState.allowsInteraction(document) && latestVisible && !documentFatal
    }
    LaunchedEffect(document.revision, scrollToAnchor) {
        anchor = scrollToAnchor.takeIf { ownsDocument() }
    }
    LaunchedEffect(
        document.revision,
        state.revision,
        state.loading,
        state.failure,
        state.requestEnabled,
        visible,
        laidOut,
        documentFatal,
        actions.onReady,
    ) {
        // 非活动帧可先完成布局，首次激活后再交付一次业务 ready。
        if (laidOut && !readyDelivered && allowsInteraction()) {
            readyDelivered = true
            latestActions.onReady?.invoke()
        }
    }
    val nativeActions =
        NativeHtmlActions(
            onOpenLink = { href ->
                if (allowsInteraction()) {
                    val local = nativeMarkdownLocalAnchor(href, document.request.pageUrl)
                    if (local != null) {
                        scope.launch {
                            // 先消费旧请求，同一锚点被再次点击时也会重新定位。
                            anchor = null
                            withFrameNanos {}
                            if (allowsInteraction()) anchor = local
                        }
                    } else if (nativeMarkdownHttpsUri(href) != null || isSafeHtmlMailto(href)) {
                        latestActions.onOpenLink(href)
                    }
                }
            },
            onTaskChanged =
                actions.onTaskChanged
                    ?.takeIf { allowsInteraction() }
                    ?.let {
                        { position, checked ->
                            if (allowsInteraction()) {
                                latestActions.onTaskChanged?.invoke(position, checked)
                            }
                        }
                    },
            onCommitSuggestion =
                actions.onCommitSuggestion
                    ?.takeIf { allowsInteraction() }
                    ?.let {
                        { suggestionId, html ->
                            if (allowsInteraction()) {
                                latestActions.onCommitSuggestion?.invoke(suggestionId, html)
                            }
                        }
                    },
            onReady = {
                if (ownsDocument() && !documentFatal && !laidOut) {
                    laidOut = true
                    latestReady(document.revision)
                }
            },
            onError = { issue ->
                if (ownsDocument() && issue.isDocumentFatal) {
                    documentFatal = true
                    latestFailure(document.revision)
                }
            },
        )
    return NativeMarkdownBinding(
        nativeActions,
        ownsDocument,
        allowsInteraction,
        anchor,
    )
}

private class NativeMarkdownMount {
    @Volatile var active = true
}

internal fun nativeMarkdownHttpsUri(value: String): URI? = runCatching {
    URI(value)
}
    .getOrNull()
    ?.takeIf { uri ->
        uri.scheme.equals("https", true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            !uri.host.equals("appassets.androidplatform.net", true)
    }

private fun nativeMarkdownLocalAnchor(href: String, pageUrl: String?): String? {
    if (href.startsWith('#')) return href
    val target = nativeMarkdownHttpsUri(href) ?: return null
    val page = pageUrl?.let(::nativeMarkdownHttpsUri) ?: return null
    if (
        target.host.equals(page.host, true) &&
            target.port == page.port &&
            target.rawPath.orEmpty().trimEnd('/') == page.rawPath.orEmpty().trimEnd('/') &&
            target.rawQuery == page.rawQuery
    ) {
        return target.rawFragment?.let { "#$it" }
    }
    return null
}
