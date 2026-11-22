package moe.yumeyuka.yumehub.feature.markdown

import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder

@Composable
internal fun MarkdownWebContent(
    content: GithubMarkdownContent,
    requested: GithubMarkdownContent,
    allowInteraction: Boolean,
    html: String,
    modifier: Modifier,
    actions: GithubMarkdownActions,
    isCurrentAccount: () -> Boolean,
    style: HtmlStyles? = null,
    onReady: (() -> Unit)? = null,
    viewportScrolling: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    visible: Boolean = true,
    resourceRevision: Any? = null,
    viewerLogin: String? = null,
    onRenderFailure: (() -> Unit)? = null,
) {
    val strings = LocalGithubStrings.current
    val layoutDirection = LocalLayoutDirection.current
    val latestReady = rememberUpdatedState(onReady)
    val latestFailure = rememberUpdatedState(onRenderFailure)
    val assetManager = LocalContext.current.assets
    val density = LocalDensity.current.density
    val dark = GlasmioTheme.colors.isDark
    val usesBackdrop = GlasmioTheme.appearance.usesBackdrop
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }
    val latestActions = rememberUpdatedState(actions)
    val latestRequested = rememberUpdatedState(requested)
    val latestInteraction = rememberUpdatedState(allowInteraction)
    val latestAccount = rememberUpdatedState(isCurrentAccount)
    val measuredDocumentIdentity = content.copy(scrollToAnchor = null, selectionHosted = false)
    val documentCache: NativeMarkdownDocumentCacheViewModel =
        viewModel(key = "github-markdown-document-cache")
    // 会话在最后一个 Markdown 离开组合时失效；返回要从路由缓存恢复高度，不能只依赖会话内 saveable。
    val retainedHeightDp =
        remember(measuredDocumentIdentity, html, viewerLogin, documentCache) {
            documentCache
                .get(measuredDocumentIdentity, viewerLogin)
                ?.takeIf { it.html == html }
                ?.webHeightDp ?: 1f
        }
    val measuredHeightState =
        key(measuredDocumentIdentity, html, viewportScrolling, viewerLogin) {
            rememberSaveable { mutableStateOf(retainedHeightDp) }
        }
    var measuredHeightDp by measuredHeightState
    var webView by remember { mutableStateOf<MarkdownWebView?>(null) }
    var codeBlocks by
        remember(resourceRevision) { mutableStateOf(emptyList<MarkdownWebCodeBlock>()) }
    var codeVersion by remember(resourceRevision) { mutableStateOf<String?>(null) }
    val nativeCodeBlocks = style != null
    val fullHeightReadme = content.readme && !viewportScrolling && !nativeCodeBlocks
    var codeLayoutReady by remember(resourceRevision) { mutableStateOf(!nativeCodeBlocks) }
    var measuredCodeHeights by remember(codeVersion) { mutableStateOf(emptyMap<Int, Float>()) }
    var layoutVersion by remember(resourceRevision) { mutableStateOf<String?>(null) }
    var webScroll by remember(resourceRevision) { mutableStateOf(IntOffset.Zero) }
    val codeScrollState = rememberScrollableState { delta ->
        webView?.consumeCodeBlockScroll(delta) ?: 0f
    }
    var webFailed by remember(resourceRevision) { mutableStateOf(false) }
    var readyNotified by remember(resourceRevision) { mutableStateOf(false) }
    var assets by remember(assetManager) { mutableStateOf(MarkdownAssets.cachedOrNull()) }
    LaunchedEffect(assetManager, resourceRevision) {
        if (assets != null) return@LaunchedEffect
        try {
            assets = withContext(Dispatchers.IO) { MarkdownAssets.load(assetManager) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            webFailed = true
        }
    }
    LaunchedEffect(webFailed, resourceRevision) {
        if (webFailed && latestAccount.value()) latestFailure.value?.invoke()
    }
    val codeMeasured =
        codeLayoutReady &&
            codeBlocks
                .filter { it.width > 0f }
                .all { block ->
                    measuredCodeHeights[block.id]?.let {
                        kotlin.math.abs(it - block.height) < 1f
                    } == true
                }
    DisposableEffect(webView, layoutVersion, codeMeasured, measuredHeightDp, readyNotified) {
        val view = webView
        val version = layoutVersion
        val observer = view?.viewTreeObserver
        var visualRequested = false
        var observing = true
        val listener = ViewTreeObserver.OnPreDrawListener {
            val heightApplied =
                view != null &&
                    (viewportScrolling ||
                        kotlin.math.abs(view.height - measuredHeightDp * density) <= 1f)
            if (
                view != null &&
                    version != null &&
                    !readyNotified &&
                    !visualRequested &&
                    codeMeasured &&
                    heightApplied &&
                    view.width > 0 &&
                    view.height > 0 &&
                    !view.isLayoutRequested &&
                    webView === view &&
                    latestAccount.value() &&
                    view.isCurrentContent(version)
            ) {
                visualRequested = true
                // Android 已应用高度仍不等于 Chromium 已提交画面；两者完成后才揭开占位。
                view.postVisualStateCallback(
                    0L,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            val currentHeightApplied =
                                viewportScrolling ||
                                    kotlin.math.abs(view.height - measuredHeightDp * density) <= 1f
                            if (
                                observing &&
                                    webView === view &&
                                    latestAccount.value() &&
                                    view.isCurrentContent(version) &&
                                    !readyNotified &&
                                    currentHeightApplied &&
                                    !view.isLayoutRequested
                            ) {
                                readyNotified = true
                                latestReady.value?.invoke()
                            } else {
                                visualRequested = false
                                if (observing) view.postInvalidateOnAnimation()
                            }
                        }
                    },
                )
            }
            true
        }
        if (!readyNotified) {
            observer?.addOnPreDrawListener(listener)
            view?.postInvalidateOnAnimation()
        }
        onDispose {
            observing = false
            if (observer?.isAlive == true) observer.removeOnPreDrawListener(listener)
        }
    }
    val taskEnabled = actions.onTaskChanged != null
    val suggestionEnabled = actions.onCommitSuggestion != null
    if (webFailed) {
        if (onRenderFailure == null) {
            GithubLoadFailureToast(Unit, strings.Markdown.RenderingFailed)
        }
    } else if (assets == null) {
        GithubMarkdownPlaceholder(modifier)
    } else {
        val mount: (MarkdownWebViewHost) -> Unit = { host ->
            readyNotified = false
            layoutVersion = null
            measuredCodeHeights = emptyMap()
            codeLayoutReady = !nativeCodeBlocks
            codeVersion = null
            codeBlocks = emptyList()
            host.mount(
                create = {
                    MarkdownWebView(
                        context = host.context,
                        assets = checkNotNull(assets),
                        content = content,
                        html = html,
                        dark = dark,
                        taskEnabled = taskEnabled,
                        suggestionEnabled = suggestionEnabled,
                        viewportScrolling = viewportScrolling,
                        viewerLogin = viewerLogin,
                        nativeCodeBlocks = nativeCodeBlocks,
                    )
                },
                onFailure = { if (latestAccount.value()) webFailed = true },
            )
        }
        key(resourceRevision, viewportScrolling) {
            Box(
                if (viewportScrolling) modifier.fillMaxSize().clipToBounds()
                else if (fullHeightReadme)
                    modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
                else
                    modifier
                        .fillMaxWidth()
                        .height(measuredHeightDp.dp)
                        .clipToBounds()
                        .bringIntoViewRequester(bringIntoView),
            ) {
                AndroidView(
                    modifier =
                        if (fullHeightReadme) Modifier.fillMaxWidth()
                        else Modifier.matchParentSize(),
                    factory = { context -> MarkdownWebViewHost(context).also(mount) },
                    onRelease = { host ->
                        if (webView === host.markdownView) webView = null
                        host.release()
                    },
                    update = { host ->
                        host.readmeHeightPx =
                            if (fullHeightReadme)
                                (measuredHeightDp * density).roundToInt().coerceAtLeast(1)
                            else null
                        // 内联 README 依靠可见区域合成绘制长文末尾，即使外壳不采样玻璃背景也要保留。
                        host.setViewportCompositingEnabled(fullHeightReadme || usesBackdrop)
                        val previous = host.markdownView
                        if (
                            previous != null &&
                                !previous.matchesConfiguration(dark, taskEnabled, suggestionEnabled)
                        ) {
                            mount(host)
                        }
                        val view = host.markdownView
                        webView = view
                        val binding = content.copy(scrollToAnchor = null)
                        view?.updateCallbacks(
                            isCurrent = { latestAccount.value() },
                            actions = {
                                val current = latestActions.value
                                // 保留旧 DOM 时禁止业务写入；新内容真正绑定前也拒绝旧消息。
                                if (latestInteraction.value && latestRequested.value == binding)
                                    current
                                else
                                    current.copy(
                                        onTaskChanged = null,
                                        onCommitSuggestion = null,
                                    )
                            },
                            onHeight = { cssHeight ->
                                if (!viewportScrolling && latestAccount.value()) {
                                    if (
                                        fullHeightReadme &&
                                            cssHeight * density > View.MEASURED_SIZE_MASK - 65536
                                    ) {
                                        // Android 的测量尺寸只有 24 位，还需为页面间距及页脚预留空间。
                                        webFailed = true
                                        return@updateCallbacks
                                    }
                                    measuredHeightDp =
                                        if (fullHeightReadme) cssHeight.coerceAtLeast(1.0).toFloat()
                                        else
                                            cssHeight
                                                .coerceIn(
                                                    1.0,
                                                    MarkdownMaxHeightPx.toDouble() / density,
                                                )
                                                .toFloat()
                                    documentCache.updateWebHeight(
                                        measuredDocumentIdentity,
                                        viewerLogin,
                                        html,
                                        measuredHeightDp,
                                    )
                                }
                            },
                            onAnchor = { cssY, version ->
                                scope.launch {
                                    // 高度经过布局后交给外层 LazyColumn，期间换文档则取消旧定位。
                                    withFrameNanos {}
                                    withFrameNanos {}
                                    if (!view.isCurrentContent(version)) return@launch
                                    val y = (cssY * density).toFloat().coerceAtLeast(0f)
                                    bringIntoView.bringIntoView(Rect(0f, y, 1f, y + density))
                                }
                            },
                            onFailure = { webFailed = true },
                            onCodeBlocks = { version, blocks, layoutReady ->
                                codeVersion = version
                                codeBlocks = blocks
                                codeLayoutReady = layoutReady
                            },
                            onViewportScroll = { x, y -> webScroll = IntOffset(x, y) },
                            onLayoutReady = { version ->
                                if (latestAccount.value() && webView === view) {
                                    layoutVersion = version
                                }
                            },
                        )
                        if (viewportScrolling)
                            view?.setInsets(
                                contentPadding.calculateTopPadding().value,
                                contentPadding.calculateRightPadding(layoutDirection).value,
                                contentPadding.calculateBottomPadding().value,
                                contentPadding.calculateLeftPadding(layoutDirection).value,
                            )
                        view?.setMediaActive(visible)
                        view?.updateContent(content, html)
                        view?.scrollToAnchor(content.scrollToAnchor)
                        view?.setHostResumed(
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        )
                    },
                )
                val version = codeVersion
                if (style != null && latestAccount.value() && version != null) {
                    key(version) {
                        MarkdownWebCodeBlocks(
                            blocks = codeBlocks,
                            style = style,
                            modifier = Modifier.matchParentSize(),
                            scroll = webScroll,
                            interactive = allowInteraction && visible,
                            scrollState = codeScrollState,
                            onHeight = { id, codeHeight ->
                                if (webView?.isCurrentContent(version) == true) {
                                    measuredCodeHeights = measuredCodeHeights + (id to codeHeight)
                                    webView?.updateCodeBlockHeight(version, id, codeHeight)
                                }
                            },
                            onFailure = {
                                if (webView?.isCurrentContent(version) == true) webFailed = true
                            },
                        )
                    }
                }
            }
        }
    }
    DisposableEffect(owner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.setHostResumed(true)
                Lifecycle.Event.ON_PAUSE -> view?.setHostResumed(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        view?.setHostResumed(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
