package moe.yumeyuka.yumehub.feature.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/** 即使系统 WebView provider 不可用，也保留可返回、可重试的原生宿主 */
internal class MarkdownWebViewHost(context: Context) : FrameLayout(context) {
    var markdownView: MarkdownWebView? = null
        private set

    // 在 LazyColumn 的无界高度下直接测量 Android 子树，避免把长文高度编码为 Compose 固定约束。
    var readmeHeightPx: Int? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = readmeHeightPx
        val spec =
            if (height == null) heightMeasureSpec
            else
                MeasureSpec.makeMeasureSpec(
                    resolveSize(height, heightMeasureSpec),
                    MeasureSpec.EXACTLY,
                )
        super.onMeasure(widthMeasureSpec, spec)
    }

    private var released = false
    private var viewportCompositingEnabled = false
    private var visibleBoundsListenerRegistered = false
    private var compositingLayer: RenderNode? = null
    private val visibleBounds = Rect()
    private val nextVisibleBounds = Rect()
    private val updateVisibleBounds = ViewTreeObserver.OnPreDrawListener {
        // 外层列表滚动只移动 AndroidView，不一定重录它的 display list。
        // 可见窗口改变时必须重录，否则新露出的内容仍会被上一帧缓存边界裁掉。
        if (viewportCompositingEnabled && refreshVisibleBounds()) invalidate()
        true
    }

    fun setViewportCompositingEnabled(enabled: Boolean) {
        if (viewportCompositingEnabled == enabled) return
        viewportCompositingEnabled = enabled
        updateVisibleBoundsListener()
        if (!enabled) discardCompositingLayer()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateVisibleBoundsListener()
    }

    override fun onDetachedFromWindow() {
        removeVisibleBoundsListener()
        discardCompositingLayer()
        super.onDetachedFromWindow()
    }

    private fun updateVisibleBoundsListener() {
        val shouldRegister = viewportCompositingEnabled && isAttachedToWindow
        if (shouldRegister == visibleBoundsListenerRegistered) return
        if (shouldRegister) {
            val observer = viewTreeObserver
            if (!observer.isAlive) return
            observer.addOnPreDrawListener(updateVisibleBounds)
            visibleBoundsListenerRegistered = true
        } else {
            removeVisibleBoundsListener()
        }
    }

    private fun removeVisibleBoundsListener() {
        if (!visibleBoundsListenerRegistered) return
        val observer = viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(updateVisibleBounds)
        visibleBoundsListenerRegistered = false
    }

    private fun refreshVisibleBounds(): Boolean {
        nextVisibleBounds.setEmpty()
        if (
            !getLocalVisibleRect(nextVisibleBounds) ||
                !nextVisibleBounds.intersect(0, 0, width, height)
        ) {
            nextVisibleBounds.setEmpty()
        } else if (readmeHeightPx != null) {
            // 只移动合成区域，不移动 WebView。固定纹理尺寸，避免图片随每个滚动像素重新分配纹理。
            val viewport = rootView.height.coerceAtLeast(nextVisibleBounds.height())
            val layerHeight = (viewport + viewport / 4).coerceAtMost(height)
            if (
                visibleBounds.width() == width &&
                    visibleBounds.height() == layerHeight &&
                    visibleBounds.bottom <= height &&
                    visibleBounds.contains(nextVisibleBounds)
            )
                return false
            val top =
                (nextVisibleBounds.centerY() - layerHeight / 2).coerceIn(0, height - layerHeight)
            nextVisibleBounds.set(0, top, width, top + layerHeight)
        }
        if (visibleBounds == nextVisibleBounds) return false
        visibleBounds.set(nextVisibleBounds)
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!viewportCompositingEnabled || !canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }
        refreshVisibleBounds()
        if (visibleBounds.isEmpty) return
        val layer =
            compositingLayer
                ?: RenderNode("MarkdownWebView viewport")
                    .apply {
                        // 屏幕与多级玻璃效果只重放同一张合成纹理，不在各自目标上重放 WebView。
                        setUseCompositingLayer(true, null)
                        setClipToBounds(true)
                    }
                    .also { compositingLayer = it }
        // 合成区域受窗口大小限制，长文不会分配整篇高度的纹理。
        layer.setPosition(visibleBounds)
        val recording = layer.beginRecording(visibleBounds.width(), visibleBounds.height())
        try {
            recording.translate(-visibleBounds.left.toFloat(), -visibleBounds.top.toFloat())
            super.dispatchDraw(recording)
        } finally {
            layer.endRecording()
        }
        canvas.drawRenderNode(layer)
    }

    private fun discardCompositingLayer() {
        compositingLayer?.discardDisplayList()
        compositingLayer = null
        visibleBounds.setEmpty()
    }

    fun mount(retained: MarkdownWebView?, create: () -> MarkdownWebView, onFailure: () -> Unit) {
        try {
            markdownView?.let { view ->
                removeView(view)
                view.release()
                markdownView = null
            }
            // 先保存完整构造的对象，再初始化 bridge / settings / 模板，便于中途失败后释放。
            val view = retained ?: create()
            markdownView = view
            if (retained == null) view.initialize()
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } catch (_: Throwable) {
            // provider 构造本身失败时没有可取得的实例；已有实例则执行完整释放。
            runCatching { removeAllViews() }
            markdownView?.release()
            markdownView = null
            post { if (!released) onFailure() }
        }
    }

    fun release(park: (MarkdownWebView) -> Boolean) {
        if (released) return
        released = true
        discardCompositingLayer()
        removeAllViews()
        markdownView?.let { if (!park(it)) it.release() }
        markdownView = null
    }
}
