package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import android.content.res.Configuration
import kotlin.math.roundToInt
import moe.yumeyuka.yumehub.ui.component.LocalReadingReceipt
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

private val CardEnterEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
private val CardExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
internal val GithubCardEnterSpec = tween<Float>(150, easing = CardEnterEasing)
internal val GithubCardExitSpec = tween<Float>(100, easing = CardExitEasing)
val GithubCardSizeSpec = tween<IntSize>(180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
private val CardContentEnterSpec = tween<Float>(150, easing = LinearEasing)
private val CardContentExitSpec = tween<Float>(110, delayMillis = 40, easing = CardExitEasing)
private val CardHandoffSize = SizeTransform { _, _ -> GithubCardSizeSpec }

/** Overlap the fades so that the skeleton does not disappear before content is visible. */
fun AnimatedContentTransitionScope<*>.githubCardContentTransform(animate: Boolean, animateSize: Boolean = false) =
    if (animate) {
        (fadeIn(CardContentEnterSpec) togetherWith fadeOut(CardContentExitSpec)).using(
            if (animateSize) CardHandoffSize else null,
        )
    } else {
        (EnterTransition.None togetherWith ExitTransition.None).using(null)
    }

/** 前进入场期间只组合骨架，转场结束后再交接已就绪的正文。 */
@Composable
fun GithubLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    animateSize: Boolean = false,
    deferUntilEntry: Boolean = true,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val animate = LocalGithubCardMotionEnabled.current
    AnimatedContent(
        targetState = loading || (deferUntilEntry && !LocalGithubRouteEntryReady.current),
        modifier = modifier,
        transitionSpec = { githubCardContentTransform(animate, animateSize) },
        label = "loading-content",
    ) { pending ->
        if (pending) Box(Modifier.clearAndSetSemantics {}) { placeholder() } else content()
    }
}

@Composable
fun GithubLoadingCard(
    loading: Boolean,
    modifier: Modifier = Modifier,
    grouped: Boolean = true,
    deferUntilEntry: Boolean = true,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (grouped) {
        GlasmioGroupedList(modifier = modifier) {
            GithubLoadingContent(loading, animateSize = true, deferUntilEntry = deferUntilEntry, placeholder = placeholder, content = content)
        }
    } else {
        GithubLoadingContent(
            loading = loading,
            modifier = modifier,
            deferUntilEntry = deferUntilEntry,
            placeholder = placeholder,
            content = content,
        )
    }
}

/** Owned by the bounded route document cache. No pixels, View, or Compose state are retained. */
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

/** The renderer is measured at its natural height from the start. Only its clipped list slot
 * grows, at most one window; the remainder is committed beyond the viewport, never tweened. */
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
    val environment = remember(config, density, direction, font, layoutKey) {
        listOf(Configuration(config), density, direction, font, layoutKey)
    }
    val active = LocalGithubPageHost.current?.isCurrent != false
    val animate = LocalGithubCardMotionEnabled.current && visible && active
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val width = layoutWidth
    val restored = remember(documentKey, geometry, width, environment) {
        geometry?.heightFor(width, environment) ?: 0
    }
    val progress = remember(documentKey, width, environment) { Animatable(if (loading) 0f else 1f) }
    var presented by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, presented) { if (presented) onPresented?.invoke() }
    val position = remember { DocumentPosition() }
    LaunchedEffect(loading, progress, animate, restored) {
        if (loading) progress.snapTo(0f)
        else if (animate && restored == 0) progress.animateTo(1f, CardContentEnterSpec)
        else progress.snapTo(1f)
    }
    // A retained layout is not a retained frame. Keep an honest placeholder at that size
    // until this instance can draw. Never fade an entire tall document into a GPU layer.
    val settled by remember(loading, restored, animate, progress) {
        derivedStateOf { !loading && (restored > 0 || !animate || progress.value >= 1f) }
    }
    val pending by remember(loading, restored, animate, progress) {
        derivedStateOf { loading || (restored == 0 && animate && progress.value == 0f) }
    }
    Layout(
        modifier = modifier.clipToBounds().onGloballyPositioned { position.top = it.positionInWindow().y },
        content = {
            Box(
                Modifier.then(if (pending || !visible) Modifier.clearAndSetSemantics {} else Modifier)
                    .drawWithContent {
                        if (!pending) {
                            drawContent()
                            if (visible && size.width > 0f && size.height > 0f) {
                                if (active && settled) geometry?.drawn(width, size.height.roundToInt(), environment)
                                if (active && settled && !presented) presented = true
                                if (settled && receipt != null && receipt.drawnBy !== owner) receipt.drawnBy = owner
                            }
                        }
                    },
                propagateMinConstraints = true,
            ) { content() }
            if (!settled) {
                Box(
                    Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                        // Hit-test shield for the pending document; the parent can still scroll.
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    },
                ) { placeholder() }
            }
        },
    ) { children, limits ->
        val body = children[0].measure(limits)
        val skeleton = children.getOrNull(1)?.measure(limits)
        val start = (restored.takeIf { it > 0 } ?: skeleton?.height ?: body.height)
            .coerceIn(limits.minHeight, limits.maxHeight)
        val height = when {
            settled -> body.height
            pending -> start
            else -> {
                val bottomOfWindow = (windowHeight - position.top).roundToInt().coerceAtLeast(0)
                val distance = (bottomOfWindow - start).coerceIn(0, windowHeight)
                val end = minOf(body.height, start + distance)
                start + ((end - start) * progress.value).roundToInt()
            }
        }
        layout(maxOf(body.width, skeleton?.width ?: 0), height) {
            body.placeRelative(0, 0)
            if (pending && skeleton != null) {
                val maxOffset = (height - skeleton.height).coerceAtLeast(0)
                val offset = (-position.top).roundToInt().coerceIn(0, maxOffset)
                skeleton.placeRelative(0, offset)
            }
        }
    }
}

private class DocumentPosition { var top = 0f }

/** Only presence changes animate; a cached field is composed at full size on its first frame. */
@Composable
fun <T : Any> GithubCardField(value: T?, content: @Composable (T) -> Unit) {
    val animate = LocalGithubCardMotionEnabled.current
    AnimatedVisibility(
        visible = value != null,
        enter = if (animate) fadeIn(CardContentEnterSpec) + expandVertically(GithubCardSizeSpec, Alignment.Top) else EnterTransition.None,
        exit = if (animate) fadeOut(CardContentExitSpec) + shrinkVertically(GithubCardSizeSpec, Alignment.Top) else ExitTransition.None,
        label = "card-field",
    ) {
        // Keep the outgoing value for its fade; forget it when the field leaves composition.
        var last by remember { mutableStateOf(value) }
        if (value != null && value != last) SideEffect { last = value }
        (value ?: last)?.let { content(it) }
    }
}

@Composable
fun GithubContentVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    animateEnter: Boolean = true,
    animateSize: Boolean = false,
    content: @Composable () -> Unit,
) {
    val motionEnabled = LocalGithubCardMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            if (motionEnabled && animateEnter) {
                fadeIn(GithubCardEnterSpec) +
                    if (animateSize) expandVertically(GithubCardSizeSpec, Alignment.Top)
                    else EnterTransition.None
            } else EnterTransition.None,
        exit =
            if (motionEnabled) {
                fadeOut(GithubCardExitSpec) +
                    if (animateSize) shrinkVertically(GithubCardSizeSpec, Alignment.Top)
                    else ExitTransition.None
            } else ExitTransition.None,
        label = "content-visibility",
        content = { content() },
    )
}
