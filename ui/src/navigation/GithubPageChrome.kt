package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class ChromeLayer(val graphicsLayer: GraphicsLayer, val toolbar: Boolean) {
    var coordinates: LayoutCoordinates? = null
    var origin by mutableStateOf<Offset?>(null)
    var recorded = false
    var padding = 0f
    var contentSize = IntSize.Zero
}

private class PageChrome(val toolbarProgress: State<Float>, val bottomProgress: State<Float>) {
    val layers = mutableStateListOf<ChromeLayer>()
    var pageCoordinates: LayoutCoordinates? = null
    var entering = true

    fun updateOrigin(layer: ChromeLayer) {
        val page = pageCoordinates?.takeIf { it.isAttached } ?: return
        val slot = layer.coordinates?.takeIf { it.isAttached } ?: return
        // 转场变换在相对坐标中抵消；Pager 的横滑偏移仍然保留。
        layer.origin = page.localPositionOf(slot, Offset.Zero)
    }
}

private class ChromeOverlay {
    val pages = mutableStateListOf<PageChrome>()
}

private val LocalChromeOverlay = staticCompositionLocalOf<ChromeOverlay?> { null }
private val LocalPageChrome = staticCompositionLocalOf<PageChrome?> { null }
internal val LocalGithubToolbarProgress = staticCompositionLocalOf<State<Float>?> { null }

/** 只在普通进退栈时绘制移出页面变换的控件。 */
@Composable
fun GithubPageChromeOverlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val overlay = remember { ChromeOverlay() }
    Box(modifier) {
        CompositionLocalProvider(LocalChromeOverlay provides overlay, content = content)
        Box(Modifier.matchParentSize().drawWithContent { overlay.pages.forEach { drawChrome(it) } })
    }
}

class GithubPageChromeTransition(
    val pageModifier: Modifier,
    val entering: Boolean,
    val pageFinished: Boolean,
    val onFinished: () -> Unit,
)

val LocalGithubPageChromeTransition = compositionLocalOf<GithubPageChromeTransition?> { null }

@Composable
fun GithubPageChrome(
    modifier: Modifier = Modifier,
    toolbar: Boolean = false,
    content: @Composable () -> Unit,
) {
    val chrome = LocalPageChrome.current
    val layer = if (chrome != null) rememberChromeLayer(chrome, toolbar) else null
    val chromeModifier =
        if (chrome == null || layer == null) Modifier
        else
            Modifier.onGloballyPositioned {
                    layer.coordinates = it
                    chrome.updateOrigin(layer)
                }
                .drawWithContent {
                    // 退场保留最后一帧控件录制，图标子层仍可更新缩放和模糊。
                    if (!layer.recorded || chrome.entering) {
                        val padding = 64.dp.roundToPx()
                        layer.padding = padding.toFloat()
                        layer.contentSize = IntSize(size.width.toInt(), size.height.toInt())
                        layer.graphicsLayer.record(
                            size =
                                IntSize(
                                    layer.contentSize.width + padding * 2,
                                    layer.contentSize.height + padding * 2,
                                ),
                        ) {
                            translate(layer.padding, layer.padding) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        layer.recorded = true
                    }
                }
    Box(modifier.then(chromeModifier)) {
        CompositionLocalProvider(
            LocalGithubToolbarProgress provides chrome?.toolbarProgress?.takeIf { toolbar },
            content = content,
        )
    }
}

@Composable
private fun rememberChromeLayer(chrome: PageChrome, toolbar: Boolean): ChromeLayer {
    val graphicsLayer = rememberGraphicsLayer()
    val layer = remember(chrome, graphicsLayer, toolbar) { ChromeLayer(graphicsLayer, toolbar) }
    DisposableEffect(chrome, layer) {
        chrome.layers.add(layer)
        onDispose { chrome.layers.remove(layer) }
    }
    return layer
}

/** 保留原测量和输入节点；仅转场期间将控件绘制交给栈的兄弟层。 */
@Composable
fun GithubPageChromeHost(content: @Composable () -> Unit) {
    val transition = LocalGithubPageChromeTransition.current
    val entering = transition?.entering
    val initialProgress = if (entering == true) 0f else 1f
    val toolbarProgress = remember { Animatable(initialProgress) }
    val bottomProgress = remember { Animatable(initialProgress) }
    val chrome = remember { PageChrome(toolbarProgress.asState(), bottomProgress.asState()) }
    var settled by remember(entering) { mutableStateOf(false) }
    LaunchedEffect(entering) {
        if (entering == null) {
            toolbarProgress.snapTo(1f)
            bottomProgress.snapTo(1f)
        } else {
            val target = if (entering) 1f else 0f
            coroutineScope {
                // 页面栈动画为 500ms；控件叠层提前结束，避免延长 Decompose 的输入锁定。
                launch { toolbarProgress.animateTo(target, tween(300)) }
                launch { bottomProgress.animateTo(target, tween(300)) }
            }
            settled = true
        }
    }
    val finished = transition?.pageFinished == true && settled
    val onFinished by rememberUpdatedState(transition?.onFinished)
    LaunchedEffect(finished) { if (finished) onFinished?.invoke() }

    val overlay = LocalChromeOverlay.current.takeIf { entering != null && !(entering && finished) }
    chrome.entering = entering != false
    if (overlay != null) {
        DisposableEffect(overlay, chrome) {
            overlay.pages.add(chrome)
            onDispose { overlay.pages.remove(chrome) }
        }
    }
    Box(
        (transition?.pageModifier ?: Modifier)
            .fillMaxSize()
            .then(
                if (overlay == null) Modifier
                else
                    Modifier.onGloballyPositioned {
                            chrome.pageCoordinates = it
                            chrome.layers.forEach(chrome::updateOrigin)
                        }
                        .graphicsLayer(),
            ),
    ) {
        CompositionLocalProvider(LocalPageChrome provides chrome.takeIf { overlay != null }) {
            content()
        }
    }
}

private fun DrawScope.drawChrome(chrome: PageChrome) {
    chrome.layers.forEach { layer ->
        val origin = layer.origin ?: return@forEach
        if (!layer.recorded || layer.coordinates?.isAttached != true) return@forEach
        if (origin.x + layer.contentSize.width <= 0f || origin.x >= size.width) return@forEach
        val progress =
            if (layer.toolbar) chrome.toolbarProgress.value else chrome.bottomProgress.value
        val scale = if (layer.toolbar) 1.2f - 0.2f * progress else 1f
        layer.graphicsLayer.apply {
            alpha = progress.coerceIn(0f, 1f)
            scaleX = scale
            scaleY = scale
            pivotOffset =
                Offset(
                    layer.padding + layer.contentSize.width / 2f,
                    layer.padding + layer.contentSize.height / 2f,
                )
        }
        translate(origin.x - layer.padding, origin.y - layer.padding) {
            drawLayer(layer.graphicsLayer)
        }
    }
}
