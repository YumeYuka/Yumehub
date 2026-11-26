package moe.yumeyuka.yumehub.ui.component.placeholders

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import kotlin.math.roundToInt
import moe.yumeyuka.yumehub.ui.component.LocalReadingReceipt
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

// Apple UnitCurve curves: easeOut (0, 0, 0.58, 1) and easeIn (0.42, 0, 1, 1).
private val IosEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val IosEaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

internal val GithubCardEnterSpec = tween<Float>(260, easing = IosEaseOut)
internal val GithubCardExitSpec = tween<Float>(200, easing = IosEaseIn)
val GithubCardSizeSpec =
    spring<IntSize>(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = IntSize(1, 1))
private val CardContentEnterSpec = tween<Float>(300, easing = IosEaseOut)
private val CardContentExitSpec = tween<Float>(200, delayMillis = 60, easing = IosEaseIn)
private const val HandoffMillis = 300

/** 占位保持不透明，直到正文完全接管后才消失；尺寸在交接期间平滑过渡。 */
private val ContentHandoffSize = SizeTransform { _, _ -> GithubCardSizeSpec }

/**
 * 占位和正文在同一槽位里交接。前进入场期间无论是否命中缓存都只组合占位；正文就绪后从占位 上方淡入，占位保持不透明到淡入结束，所以不会出现背景空帧；容器高度用
 * [GithubCardSizeSpec] 平滑变化，不突然跳动。卡片内部晚到字段仍由 [GithubCardField] 展开。
 */
@Composable
fun GithubLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val pending = loading || !LocalGithubRouteEntryReady.current
    val animate = LocalGithubCardMotionEnabled.current
    AnimatedContent(
        targetState = pending,
        modifier = modifier,
        transitionSpec = {
            if (!animate) {
                (EnterTransition.None togetherWith ExitTransition.None).using(null)
            } else {
                (fadeIn(CardContentEnterSpec) togetherWith
                        fadeOut(tween(0, delayMillis = HandoffMillis)))
                    .using(ContentHandoffSize)
                    .apply { targetContentZIndex = 1f }
            }
        },
        label = "loading-content",
    ) { isPending ->
        if (isPending) {
            Column(Modifier.clearAndSetSemantics {}) { placeholder() }
        } else {
            Column { content() }
        }
    }
}

@Composable
fun GithubLoadingCard(
    loading: Boolean,
    modifier: Modifier = Modifier,
    grouped: Boolean = true,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (grouped) {
        GlasmioGroupedList(modifier = modifier) {
            GithubLoadingContent(loading, placeholder = placeholder, content = content)
        }
    } else {
        GithubLoadingContent(
            loading = loading,
            modifier = modifier,
            placeholder = placeholder,
            content = content,
        )
    }
}

/** Owned by the bounded route document cache. No pixels, View or Compose state are retained. */
class GithubDocumentGeometry {
    private var environment: Any? = null
    private var width = 0
    private var height = 0

    internal fun heightFor(width: Int, environment: Any): Int =
        if (this.width == width && this.environment == environment) height else 0

    internal fun drawn(width: Int, height: Int, environment: Any) {
        this.width = width
        this.height = height
        this.environment = environment
    }
}

/** 文档正文先按自身高度测量；可画之前占位只占恢复高度或自身高度。正文画出来后占位在正文上方 淡出，容器高度随 [GithubCardSizeSpec] 过渡，骨架不移动，也不会露出空白帧。 */
@Composable
fun GithubLoadingOverlay(
    loading: Boolean,
    documentKey: Any,
    modifier: Modifier = Modifier,
    geometry: GithubDocumentGeometry? = null,
    layoutKey: Any? = null,
    layoutWidth: Int,
    visible: Boolean = true,
    onPresented: (() -> Unit)? = null,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val receipt = LocalReadingReceipt.current
    val owner = remember(documentKey, loading) { Any() }
    DisposableEffect(receipt, owner) {
        onDispose { if (receipt != null && receipt.drawnBy === owner) receipt.drawnBy = null }
    }
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val font = GlasmioTheme.typography.bodyLarge
    val environment =
        remember(config, density, direction, font, layoutKey) {
            listOf(Configuration(config), density, direction, font, layoutKey)
        }
    val active = LocalGithubPageHost.current?.isCurrent != false
    val width = layoutWidth
    val restored =
        remember(documentKey, geometry, width, environment) {
            geometry?.heightFor(width, environment) ?: 0
        }
    val reveal = remember(documentKey, width, environment) { Animatable(if (loading) 0f else 1f) }
    LaunchedEffect(loading) {
        if (loading) reveal.snapTo(0f) else reveal.animateTo(1f, CardContentEnterSpec)
    }
    var presented by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, presented) { if (presented) onPresented?.invoke() }
    val settled by remember(loading) { derivedStateOf { !loading && reveal.value >= 1f } }
    Layout(
        modifier = modifier.animateContentSize(GithubCardSizeSpec).clipToBounds(),
        content = {
            Box(
                Modifier.then(
                        if (loading || !visible) Modifier.clearAndSetSemantics {} else Modifier,
                    )
                    .drawWithContent {
                        if (!loading) {
                            drawContent()
                            if (visible && size.width > 0f && size.height > 0f) {
                                if (active) {
                                    geometry?.drawn(width, size.height.roundToInt(), environment)
                                    if (!presented) presented = true
                                }
                                if (receipt != null && receipt.drawnBy !== owner)
                                    receipt.drawnBy = owner
                            }
                        }
                    },
                propagateMinConstraints = true,
            ) {
                content()
            }
            if (!settled) {
                Box(
                    Modifier.graphicsLayer { alpha = 1f - reveal.value }
                        .clearAndSetSemantics {}
                        .pointerInput(Unit) {
                            // Hit-test shield for the pending document; the parent can still
                            // scroll.
                            awaitPointerEventScope { while (true) awaitPointerEvent() }
                        },
                ) {
                    placeholder()
                }
            }
        },
    ) { children, limits ->
        val body = children[0].measure(limits)
        val skeleton = children.getOrNull(1)?.measure(limits)
        val height =
            if (loading) {
                (restored.takeIf { it > 0 } ?: skeleton?.height ?: body.height).coerceIn(
                    limits.minHeight,
                    limits.maxHeight,
                )
            } else body.height
        layout(maxOf(body.width, skeleton?.width ?: 0), height) {
            body.placeRelative(0, 0)
            skeleton?.placeRelative(0, 0)
        }
    }
}

/** Only presence changes animate; a cached field is composed at full size on its first frame. */
@Composable
fun <T : Any> GithubCardField(value: T?, content: @Composable (T) -> Unit) {
    val animate = LocalGithubCardMotionEnabled.current
    AnimatedVisibility(
        visible = value != null,
        enter =
            if (animate)
                fadeIn(CardContentEnterSpec) + expandVertically(GithubCardSizeSpec, Alignment.Top)
            else EnterTransition.None,
        exit =
            if (animate)
                fadeOut(CardContentExitSpec) + shrinkVertically(GithubCardSizeSpec, Alignment.Top)
            else ExitTransition.None,
        label = "card-field",
    ) {
        // Keep the outgoing value for its fade; forget it when the field leaves composition.
        var last by remember { mutableStateOf(value) }
        if (value != null && value != last) SideEffect { last = value }
        (value ?: last)?.let { content(it) }
    }
}
