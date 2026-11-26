package moe.yumeyuka.yumehub.feature.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID
import kotlin.math.abs
import moe.yumeyuka.markdown.core.isSafeHtmlMailto
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import org.json.JSONObject

internal const val MarkdownMaxHeightPx = 120_000

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class MarkdownWebView(
    context: Context,
    content: GithubMarkdownContent,
    html: String,
    private val assets: MarkdownAssets,
    private val dark: Boolean,
    private val taskEnabled: Boolean,
    private val suggestionEnabled: Boolean,
    private val viewportScrolling: Boolean = false,
    private val viewerLogin: String? = null,
    private val nativeCodeBlocks: Boolean = false,
) : WebView(context) {
    @Volatile private var messageId = UUID.randomUUID().toString()
    private var content: GithubMarkdownContent? = content.copy(scrollToAnchor = null)
    private var html: String? = html
    private var bound = false
    private var isCurrent: () -> Boolean = { false }
    private var actions: (() -> GithubMarkdownActions)? = null
    private var onHeight: (Double) -> Unit = {}
    private var onAnchor: (Double, String) -> Unit = { _, _ -> }
    private var onFailure: () -> Unit = {}
    private var lastHeight: Double? = null
    private val template = if (dark) "template_dark.html" else "template.html"
    private val templateUrl = "$Origin$WebPath$template"
    private var released = false
    private var broken = false
    private var ready = false
    private var submittedId: String? = null
    private var onRendered: (() -> Unit)? = null
    private var onLayoutReady: ((String) -> Unit)? = null
    private var onCodeBlocks: (String, List<MarkdownWebCodeBlock>, Boolean) -> Unit = { _, _, _ -> }
    private var onViewportScroll: (Int, Int) -> Unit = { _, _ -> }
    private var codeBlocks = emptyMap<Int, MarkdownWebCodeBlock>()
    private var hostResumed: Boolean? = null
    private var mediaActive = true
    private var insets: String? = null
    private var topInset = 0f
    private var requestedAnchor: String? = null
    private var deliveredAnchor: String? = null
    private var startX = 0f
    private var startY = 0f
    private var previousY = 0f
    private var previousX = 0f
    private var touchSlop = 0
    private var horizontalGesture: Boolean? = null
    private var touchActive = false
    private var yieldedVerticalDrag = false
    private var horizontalScrollKnown = false
    private var canScrollContentLeft = false
    private var canScrollContentRight = false
    private var horizontalDirection = 0

    fun initialize() {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        // 官方 unj.java 构造使用 alpha=1 的近透明底色：完全透明的 WebView 表面
        // 在玻璃采样与屏幕双绘制目标重放（drawVk）时会产生整帧空白；alpha=1
        // 迫使 Chromium 合成器输出底色 quad，视觉仍近似透明且首帧不闪。
        setBackgroundColor(Color.argb(1, 0, 0, 0))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isSaveEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // 长正文可能远高于屏幕；固定视口预览可见时才启用离屏预栅格。
            offscreenPreRaster = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 图片和媒体遵守服务端缓存头，避免滚回正文时强制重新下载。
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(NativeBridge(), "native")
        webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = resource(request)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (request.isForMainFrame && active()) openLink(request.url.toString())
                    return true
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (url != templateUrl) {
                        ready = false
                        stopLoading()
                        removeJavascriptInterface("native")
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (released || broken || url != templateUrl || ready) return
                    ready = true
                    val id = messageId
                    evaluateJavascript(assets.installAdapterScript) { result ->
                        if (active() && id == messageId && result != "true") onFailure()
                    }
                    updateHtml()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        val notify = active()
                        broken = true
                        ready = false
                        if (notify) onFailure()
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail,
                ): Boolean {
                    val notify = active()
                    broken = true
                    ready = false
                    if (notify) onFailure()
                    return true
                }
            }
        loadUrl(templateUrl)
    }

    /** 只跟随宿主生命周期；页面内的切换不暂停 WebView。 */
    fun setHostResumed(resumed: Boolean) {
        if (released || broken || hostResumed == resumed) return
        hostResumed = resumed
        updateOffscreenPreRaster()
        applyActive()
        if (resumed) onResume() else onPause()
    }

    /** 被编辑器遮住时只暂停页面内媒体，不暂停 WebView。 */
    fun setMediaActive(active: Boolean) {
        if (released || broken) return
        if (mediaActive == active) return
        mediaActive = active
        isFocusable = active
        isFocusableInTouchMode = active
        importantForAccessibility = if (active) IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!active) clearFocus()
        updateOffscreenPreRaster()
        applyActive()
    }

    private fun updateOffscreenPreRaster() {
        settings.offscreenPreRaster = viewportScrolling && mediaActive && hostResumed == true
    }

    private fun applyActive() {
        if (ready) {
            evaluateJavascript("window.androidMarkdown.setActive(${jsActive()});", null)
        }
    }

    private fun jsActive() = hostResumed == true && mediaActive

    /** 单位为 CSS px（等于 dp）；只用于在 WebView 内部滚动的视口预览。 */
    fun setInsets(top: Float, right: Float, bottom: Float, left: Float) {
        if (released || broken) return
        topInset = top
        val next = "window.androidMarkdown.setInsets($top,$right,$bottom,$left);"
        if (next == insets) return
        insets = next
        if (ready) evaluateJavascript(next, null)
    }

    fun matchesConfiguration(dark: Boolean, tasks: Boolean, suggestions: Boolean): Boolean =
        !released &&
            !broken &&
            this.dark == dark &&
            taskEnabled == tasks &&
            suggestionEnabled == suggestions

    fun isCurrentContent(version: String): Boolean = active() && version == messageId

    fun updateCallbacks(
        isCurrent: () -> Boolean,
        actions: () -> GithubMarkdownActions,
        onHeight: (Double) -> Unit,
        onAnchor: (Double, String) -> Unit,
        onFailure: () -> Unit,
        onRendered: (() -> Unit)? = null,
        onCodeBlocks: (String, List<MarkdownWebCodeBlock>, Boolean) -> Unit = { _, _, _ -> },
        onViewportScroll: (Int, Int) -> Unit = { _, _ -> },
        onLayoutReady: ((String) -> Unit)? = null,
    ) {
        this.isCurrent = isCurrent
        this.actions = actions
        this.onHeight = onHeight
        this.onAnchor = onAnchor
        this.onFailure = onFailure
        this.onRendered = onRendered
        this.onLayoutReady = onLayoutReady
        this.onCodeBlocks = onCodeBlocks
        this.onViewportScroll = onViewportScroll
        bound = true
        onViewportScroll(scrollX, scrollY)
    }

    /** 同一文档不重载模板；输入完全相同则跳过 JS，新版本由适配器按 HTML 幂等复用 DOM。 */
    fun updateContent(content: GithubMarkdownContent, html: String) {
        if (!active()) return
        val next = content.copy(scrollToAnchor = null)
        val previous = this.content
        // 即使服务端 HTML 相同，原文版本改变也须轮换消息 id，适配器仍可复用 DOM。
        val changed = this.html != html || previous != next
        this.content = next
        if (changed) {
            this.html = html
            messageId = UUID.randomUUID().toString()
            codeBlocks = emptyMap()
            onCodeBlocks(messageId, emptyList(), false)
            lastHeight = null
            deliveredAnchor = null
        }
        updateHtml()
    }

    private fun updateHtml() {
        val current = content ?: return
        val rendered = html ?: return
        if (!ready || !active() || submittedId == messageId) return
        val id = messageId
        // 模板和 Lazy item 的绑定没有先后保证。以实际提交为准，不能把构造参数当作已绘制的 DOM。
        submittedId = id
        val baseUrl = markdownBaseUrl(current.pageUrl, current.repositoryRootUrl)
        // 传入解码 HTML；随机版本号同时隔离 bridge 消息和异步 evaluate 回调。
        evaluateJavascript(
            "(function(){try{window.androidMarkdown.setActive(${jsActive()});" +
                "window.androidMarkdown.setViewport($viewportScrolling);" +
                insets.orEmpty() +
                "window.androidMarkdown.load(" +
                "${JSONObject.quote(id)},${JSONObject.quote(rendered)}," +
                "$suggestionEnabled,$taskEnabled,${JSONObject.quote(baseUrl)}," +
                "${JSONObject.quote(current.repositoryRootUrl)},${current.transparentBackground}," +
                "${current.readme},${JSONObject.quote(viewerLogin)},$nativeCodeBlocks," +
                "${current.compactBodyTypography});return true;}catch(_){return false;}})();",
        ) { result ->
            if (active() && id == messageId) {
                if (result != "true") onFailure()
                else {
                    visibility = VISIBLE
                    deliverAnchor()
                    if (onRendered != null && onLayoutReady == null) {
                        // 不需要宿主布局协调的独立调用方仍使用可绘制回调。
                        postVisualStateCallback(
                            0L,
                            object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if (active() && id == messageId) onRendered?.invoke()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** AndroidView 离开当前绑定时立即失效，并清空 DOM，禁止回收宿主携带账号正文。 */
    fun resetContent() {
        messageId = UUID.randomUUID().toString()
        bound = false
        content = null
        html = null
        requestedAnchor = null
        deliveredAnchor = null
        lastHeight = null
        visibility = INVISIBLE
        isCurrent = { false }
        actions = null
        onHeight = {}
        onAnchor = { _, _ -> }
        onFailure = {}
        onRendered = null
        onLayoutReady = null
        onCodeBlocks = { _, _, _ -> }
        onViewportScroll = { _, _ -> }
        codeBlocks = emptyMap()
        if (ready && !released) {
            runCatching {
                evaluateJavascript(
                    "window.androidMarkdown.load(${JSONObject.quote(messageId)},'',false,false,null,null);",
                    null,
                )
            }
        }
        runCatching { scrollTo(0, 0) }
        runCatching { setHostResumed(false) }
    }

    private fun active() = !released && !broken && bound && isCurrent()

    private fun resource(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != "https" || uri.userInfo != null) return blocked()
        if (uri.host != "appassets.androidplatform.net") {
            return if (request.isForMainFrame) blocked() else null
        }
        if (uri.port != -1 && uri.port != 443) return blocked()
        if (uri.query != null || uri.fragment != null || request.method != "GET") return blocked()
        val path = uri.encodedPath.orEmpty()
        if (!path.startsWith(WebPath)) return blocked()
        val file = path.removePrefix(WebPath)
        if (file !in AssetNames || (request.isForMainFrame && file != template)) return blocked()
        return try {
            val mime =
                when {
                    file.endsWith(".html") -> "text/html"
                    file.endsWith(".css") -> "text/css"
                    file.endsWith(".js") -> "application/javascript"
                    else -> "font/ttf"
                }
            WebResourceResponse(
                mime,
                if (file.endsWith(".ttf")) null else "UTF-8",
                200,
                "OK",
                mapOf(
                    "Content-Security-Policy" to Policy,
                    "X-Content-Type-Options" to "nosniff",
                    "Referrer-Policy" to "no-referrer",
                    "Cache-Control" to "no-store",
                ),
                if (file.endsWith(".ttf")) {
                    context.assets.open("github-markdown/fonts/$file")
                } else {
                    context.assets.openMarkdownAsset(file)
                },
            )
        } catch (_: Exception) {
            blocked()
        }
    }

    private fun blocked() =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(byteArrayOf()),
        )

    fun scrollToAnchor(anchor: String?) {
        requestedAnchor = anchor
        if (anchor == null) deliveredAnchor = null
        deliverAnchor()
    }

    private fun deliverAnchor() {
        val anchor = requestedAnchor ?: return
        if (!ready || !active() || anchor == deliveredAnchor) return
        deliveredAnchor = anchor
        evaluateJavascript("window.androidMarkdown.anchor(${JSONObject.quote(anchor)});", null)
    }

    private fun openLink(raw: String) {
        if (!mediaActive) return
        if (raw.startsWith("#")) {
            evaluateJavascript("window.androidMarkdown.anchor(${JSONObject.quote(raw)});", null)
            return
        }
        val content = content ?: return
        val resolved =
            resolveMarkdownLink(raw, content.pageUrl, content.repositoryRootUrl) ?: return
        val page = content.pageUrl?.let { runCatching { URI(it) }.getOrNull() }
        val target = URI(resolved)
        if (
            target.rawFragment != null &&
                page != null &&
                target.scheme == page.scheme &&
                target.authority == page.authority &&
                target.path == page.path &&
                target.query == page.query
        ) {
            evaluateJavascript(
                "window.androidMarkdown.anchor(${JSONObject.quote(target.rawFragment)});",
                null,
            )
        } else {
            actions?.invoke()?.onOpenLink?.invoke(resolved)
        }
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun sendMessage(message: String) {
            if (message.length > 8 * 1024 * 1024) return
            val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
            if (payload.optString("id") != messageId) return
            post {
                if (
                    !active() ||
                        !ready ||
                        url != templateUrl ||
                        payload.optString("id") != messageId
                )
                    return@post
                when (payload.optString("messageName")) {
                    "layout" -> onLayoutReady?.invoke(messageId)
                    "code_blocks" ->
                        if (nativeCodeBlocks) {
                            val blocks = payload.optJSONArray("blocks") ?: return@post
                            if (blocks.length() > 50_000) return@post
                            codeBlocks = buildMap {
                                for (index in 0 until blocks.length()) {
                                    val block = blocks.optJSONObject(index) ?: return@post
                                    val id = block.optInt("id", -1)
                                    val html = block.optString("html")
                                    if (id < 0 || html.isBlank() || html.length > 4 * 1024 * 1024)
                                        return@post
                                    put(
                                        id,
                                        MarkdownWebCodeBlock(
                                            id = id,
                                            html = html,
                                        ),
                                    )
                                }
                            }
                            onCodeBlocks(
                                messageId,
                                codeBlocks.values.toList(),
                                codeBlocks.isEmpty(),
                            )
                        }
                    "code_layout" ->
                        if (nativeCodeBlocks) {
                            val layout = payload.optJSONArray("layout") ?: return@post
                            val updated = codeBlocks.toMutableMap()
                            for (index in 0 until layout.length()) {
                                val box = layout.optJSONArray(index) ?: return@post
                                if (box.length() != 9) return@post
                                val block = updated[box.optInt(0, -1)] ?: continue
                                val values = (1..8).map { box.optDouble(it).toFloat() }
                                if (
                                    values.any {
                                        !it.isFinite() || kotlin.math.abs(it) > 100_000_000f
                                    } ||
                                        values[2] < 0f ||
                                        values[3] < 0f ||
                                        values[6] < values[4] ||
                                        values[7] < values[5]
                                )
                                    return@post
                                updated[block.id] =
                                    block.copy(
                                        left = values[0],
                                        top = values[1],
                                        width =
                                            values[2].coerceAtMost(
                                                width / resources.displayMetrics.density,
                                            ),
                                        height = values[3],
                                        clip =
                                            androidx.compose.ui.geometry.Rect(
                                                values[4],
                                                values[5],
                                                values[6],
                                                values[7],
                                            ),
                                    )
                            }
                            codeBlocks = updated
                            onCodeBlocks(messageId, updated.values.toList(), true)
                        }
                    "horizontal_scroll" -> {
                        if (touchActive) {
                            horizontalScrollKnown = true
                            canScrollContentLeft = payload.optBoolean("canScrollLeft", false)
                            canScrollContentRight = payload.optBoolean("canScrollRight", false)
                            if (horizontalGesture == true) {
                                parent?.requestDisallowInterceptTouchEvent(ownsHorizontalGesture())
                            } else if (
                                horizontalGesture == null &&
                                    !scrollsInternally() &&
                                    !canScrollContentLeft &&
                                    !canScrollContentRight &&
                                    !canScrollHorizontally(-1) &&
                                    !canScrollHorizontally(1)
                            ) {
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                    "height" -> {
                        val height = payload.optDouble("height")
                        if (height.isFinite() && height > 0 && height != lastHeight) {
                            lastHeight = height
                            if (!scrollsInternally() && scrollY != 0) scrollTo(scrollX, 0)
                            // Compose 高度 state 负责布局；不再递归 requestLayout 整条父链。
                            onHeight(height)
                        }
                    }
                    "scroll_to" ->
                        payload.optDouble("posY").let {
                            if (it.isFinite() && it >= 0) revealDocumentPosition(it)
                        }
                    "open_link" -> openLink(payload.optString("href"))
                    "task_changed" ->
                        if (taskEnabled) {
                            val position = payload.optInt("taskPosition", -1)
                            if (position >= 0 && payload.opt("taskChecked") is Boolean) {
                                actions
                                    ?.invoke()
                                    ?.onTaskChanged
                                    ?.invoke(
                                        position,
                                        payload.getBoolean("taskChecked"),
                                    )
                            }
                        }
                    "commit_suggestion" ->
                        if (suggestionEnabled) {
                            val id = payload.optString("suggestionId")
                            if (id.isNotEmpty()) {
                                actions
                                    ?.invoke()
                                    ?.onCommitSuggestion
                                    ?.invoke(
                                        id,
                                        payload.optString("previewHTML"),
                                    )
                            }
                        }
                    "error" -> onFailure()
                    "escape_focus" -> focusSearch(FOCUS_FORWARD)?.requestFocus()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mediaActive) return false
        if (yieldedVerticalDrag && event.actionMasked != MotionEvent.ACTION_DOWN) return true
        var keepGesture: Boolean? = null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                yieldedVerticalDrag = false
                startX = event.x
                startY = event.y
                previousY = event.y
                previousX = event.x
                horizontalGesture = null
                touchActive = true
                horizontalScrollKnown = false
                canScrollContentLeft = false
                canScrollContentRight = false
                horizontalDirection = 0
                // 短暂保留起始事件，等 DOM 确认是否命中可横滚的代码块或表格。
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                if (horizontalGesture == null && (dx > touchSlop || dy > touchSlop)) {
                    horizontalGesture = dx > dy
                    if (horizontalGesture == false && inlineReadme()) {
                        // 在 Chromium 接收越过阈值的 MOVE 前取消，不能让卡片积累位移或在 UP 后 fling。
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        yieldedVerticalDrag = true
                        touchActive = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                horizontalGesture?.let { horizontal ->
                    // 一次手势只定向一次，避免斜滑期间在 WebView 与父滚动容器之间反复抢占。
                    if (horizontal || event.y != previousY) {
                        val direction = if (event.y < previousY) 1 else -1
                        val horizontalDelta = event.x - previousX
                        if (horizontalDelta != 0f) {
                            horizontalDirection = if (horizontalDelta < 0f) 1 else -1
                        }
                        keepGesture =
                            if (horizontal) {
                                ownsHorizontalGesture()
                            } else scrollsInternally() && canScrollVertically(direction)
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                horizontalGesture = null
                touchActive = false
                horizontalScrollKnown = false
                keepGesture = false
            }
        }
        previousY = event.y
        previousX = event.x
        val handled = super.onTouchEvent(event)
        // Chromium 处理 MOVE 时也可能要求独占手势。最后由宿主决定是否交给父列表。
        keepGesture?.let { parent?.requestDisallowInterceptTouchEvent(it) }
        return handled
    }

    /** 普通正文交给 Pager；命中代码块／表格且该方向仍能滚动时才保留横向手势。 */
    private fun ownsHorizontalGesture(): Boolean =
        !horizontalScrollKnown ||
            canScrollHorizontally(horizontalDirection) ||
            (if (horizontalDirection > 0) canScrollContentRight else canScrollContentLeft)

    private fun inlineReadme(): Boolean =
        content?.readme == true && !viewportScrolling && !nativeCodeBlocks

    private fun scrollsInternally(): Boolean =
        viewportScrolling ||
            (!inlineReadme() &&
                (lastHeight ?: 0.0) * resources.displayMetrics.density > MarkdownMaxHeightPx)

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, if (scrollsInternally()) y else 0)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        // 普通正文由父列表滚动；焦点、选区和重测不能在 WebView 内再留一个纵向偏移。
        if (top != 0 && !scrollsInternally()) super.scrollTo(left, 0)
        if (active()) onViewportScroll(scrollX, scrollY)
    }

    fun updateCodeBlockHeight(version: String, id: Int, heightCssPx: Float) {
        if (!isCurrentContent(version) || !heightCssPx.isFinite() || heightCssPx <= 0f) return
        evaluateJavascript(
            "window.androidMarkdown.codeHeight(${JSONObject.quote(version)},$id,$heightCssPx);",
            null,
        )
    }

    fun consumeCodeBlockScroll(deltaPx: Float): Float {
        if (!active() || !scrollsInternally() || !deltaPx.isFinite()) return 0f
        val before = scrollY
        val maximum =
            (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        scrollTo(scrollX, (before - deltaPx).toInt().coerceIn(0, maximum))
        return (before - scrollY).toFloat()
    }

    private fun revealDocumentPosition(cssY: Double) {
        // 等 Compose 将最新正文高度提交给 AndroidView，再计算内部可滚动区。
        val id = messageId
        postOnAnimation {
            postOnAnimation reveal@{
                if (!active() || id != messageId) return@reveal
                val density = resources.displayMetrics.density
                val inset = if (viewportScrolling) topInset else 0f
                val documentY =
                    ((cssY - inset) * density).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
                val maxScroll =
                    if (scrollsInternally()) {
                        (computeVerticalScrollRange() - computeVerticalScrollExtent())
                            .coerceAtLeast(0)
                    } else 0
                scrollTo(0, documentY.coerceAtMost(maxScroll))
                val viewportY = (documentY - scrollY).coerceIn(0, (height - 1).coerceAtLeast(0))
                onAnchor(viewportY / density.toDouble(), id)
            }
        }
    }

    override fun requestRectangleOnScreen(rectangle: Rect, immediate: Boolean): Boolean {
        if (active() && mediaActive) {
            revealDocumentPosition(rectangle.top / resources.displayMetrics.density.toDouble())
        }
        return true
    }

    fun release() {
        if (released) return
        runCatching { resetContent() }
        released = true
        ready = false
        // 初始化中途失败时也尽力执行全部释放步骤，避免前一步失败阻止 destroy。
        runCatching { removeJavascriptInterface("native") }
        runCatching { stopLoading() }
        runCatching { onPause() }
        runCatching { clearHistory() }
        runCatching { removeAllViews() }
        runCatching { destroy() }
    }

    private companion object {
        const val Origin = "https://appassets.androidplatform.net"
        const val WebPath = "/github-markdown/webview/"
        val AssetNames =
            setOf(
                "template.html",
                "template_dark.html",
                "colors_light.css",
                "colors_dark.css",
                "markdown.css",
                "syntax.css",
                "syntax_dark.css",
                "font.css",
                "suggested_changes.css",
                "markdown.js",
                "Ubuntu-Regular.ttf",
            )
        const val Policy =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src https:; media-src https:; font-src 'self'; connect-src 'none'; " +
                "frame-src 'none'; child-src 'none'; object-src 'none'; base-uri 'none'; " +
                "form-action 'none'; frame-ancestors 'none'"
    }
}

/** 相对链接以页面所在目录为基址；缺少页面时以仓库根目录为基址 */
internal fun resolveMarkdownLink(
    raw: String,
    pageUrl: String?,
    repositoryRootUrl: String?,
): String? = runCatching {
    if (isSafeHtmlMailto(raw)) return@runCatching raw
    val input = URI(raw)
    val base = markdownBaseUrl(pageUrl, repositoryRootUrl)
    val resolved =
        if (input.isAbsolute) input else URI(base ?: return@runCatching null).resolve(input)
    resolved
        .takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                !it.host.isNullOrEmpty() &&
                it.rawUserInfo == null &&
                it.host != "appassets.androidplatform.net"
        }
        ?.toASCIIString()
}
    .getOrNull()

internal fun markdownBaseUrl(pageUrl: String?, repositoryRootUrl: String?): String? {
    val page = pageUrl ?: return repositoryRootUrl?.let { it.trimEnd('/') + "/" }
    val uri = runCatching { URI(page) }.getOrNull() ?: return null
    val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
    val isDirectory =
        page.trimEnd('/') == repositoryRootUrl?.trimEnd('/') ||
            (uri.host == "github.com" && (parts.size == 2 || parts.getOrNull(2) == "tree"))
    return if (isDirectory)
        URI(
                uri.scheme,
                uri.authority,
                uri.path.trimEnd('/') + "/",
                uri.query,
                null,
            )
            .toASCIIString()
    else page
}
