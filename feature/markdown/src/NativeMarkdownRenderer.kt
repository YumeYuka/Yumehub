package moe.yumeyuka.yumehub.feature.markdown

import androidx.compose.foundation.layout.PaddingValues
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
import moe.yumeyuka.yumehub.ui.component.MarkdownActions
import moe.yumeyuka.yumehub.ui.component.MarkdownContent
import moe.yumeyuka.yumehub.ui.component.MarkdownRenderer
import org.koin.compose.koinInject

/** 统一适配入口；图片及 HTML 专有布局交给现有 WebView，其余内容使用原生库。 */
internal object NativeMarkdownRenderer : MarkdownRenderer {
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
        content: MarkdownContent,
        modifier: Modifier,
        actions: MarkdownActions,
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
            MarkdownSurface(content, modifier, actions, repository, session.isCurrent,
                hostActive = hostActive, native = true)
        }
    }

    @Composable
    override fun Preview(
        content: MarkdownContent,
        visible: Boolean,
        contentPadding: PaddingValues,
        modifier: Modifier,
        actions: MarkdownActions,
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
            MarkdownSurface(
                content = content,
                visible = visible,
                hostActive = hostActive,
                native = true,
                viewport = true,
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
    actions: MarkdownActions,
    isCurrentAccount: () -> Boolean,
    webLayout: Boolean,
    initialHeightPx: Int,
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
    if (webLayout) {
        MarkdownWebContent(
            initialHeightPx = initialHeightPx,
            content = document.request.copy(scrollToAnchor = binding.scrollToAnchor),
            requested = state.request,
            allowInteraction = binding.allowsInteraction(),
            html = document.html,
            style = style,
            modifier = modifier,
            actions =
                MarkdownActions(
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
    actions: MarkdownActions,
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
    val ownsDocument = { mount.active && latestAccount() && latestState.owns(document) }
    val allowsInteraction = {
        ownsDocument() && latestState.allowsInteraction(document) && latestVisible && !documentFatal
    }
    LaunchedEffect(document.revision, scrollToAnchor) {
        anchor = scrollToAnchor.takeIf { ownsDocument() }
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
