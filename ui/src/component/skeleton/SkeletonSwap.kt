package moe.yumeyuka.yumehub.ui.component.skeleton

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubSkeletonEntrance

/** 骨架交接的唯一时序与曲线；所有 Card、文档正文都从这里取参数。 */
object SkeletonTiming {
    internal const val ShowDelayMillis = 120L
    internal const val MinVisibleMillis = 380L
    internal const val BodyStartScale = 0.99f
    internal val BodyBlur = 4.dp
    internal val SkeletonBlur = 3.dp
    // Interior's spring (260 stiffness, 34 damping, 0.8 mass), normalized to unit mass.
    internal val SwapSpec =
        spring(dampingRatio = 1.1787f, stiffness = 325f, visibilityThreshold = 0.001f)
    internal val MaxHeightTravel = 320.dp
}

private const val BodySlot = "skeleton-swap-body"
private const val PlaceholderSlot = "skeleton-swap-placeholder"

/**
 * The static skeleton and body crossfade on one spring. Small height changes follow that same
 * progress without reflowing the text.
 *
 * 首次呈现时不论数据是否已就绪都先出骨架再交接。恢复和追加沿用已呈现的列表身份。
 *
 * @param loading Data is not ready; delay the skeleton only when actually waiting on data.
 * @param contentPending 正文是否暂缓组合（页面转场等）；为 true 时正文不组合，只留骨架。
 * @param entrance 是否属于页面入场；为 false 时只在等待数据时出骨架。
 * @param reservedHeight Cached pixel height for the loading slot, or 0 to measure the skeleton.
 */
@Composable
fun SkeletonSwap(
    loading: Boolean,
    modifier: Modifier = Modifier,
    contentPending: Boolean = loading,
    entrance: Boolean = LocalGithubSkeletonEntrance.current?.presented == false,
    reservedHeight: Int = 0,
    placeholder: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val routeReady = LocalGithubRouteEntryReady.current
    val animate = LocalGithubCardMotionEnabled.current
    var hasSettledBody by remember { mutableStateOf(!loading && !contentPending && !entrance) }
    var entryPending by remember { mutableStateOf(entrance) }
    val entering = entryPending && (animate || !routeReady)
    var shownAt by remember { mutableStateOf<TimeMark?>(null) }
    val deferBody = contentPending || !routeReady && (entering || shownAt != null)
    val pending = loading || deferBody
    val waiting = pending || entering
    val showSkeleton = entering || shownAt != null || !animate || !hasSettledBody
    val reveal = if (shownAt != null && animate) remember { Animatable(0f) } else null

    LaunchedEffect(waiting, entering, animate, hasSettledBody) {
        if (waiting) {
            reveal?.snapTo(0f)
            if (shownAt == null) {
                if (animate && !entering && hasSettledBody) delay(SkeletonTiming.ShowDelayMillis)
                shownAt = TimeSource.Monotonic.markNow()
            }
            if (entering) entryPending = false
        } else {
            val shown = shownAt ?: return@LaunchedEffect
            if (reveal != null) {
                val remaining =
                    SkeletonTiming.MinVisibleMillis - shown.elapsedNow().inWholeMilliseconds
                if (remaining > 0) delay(remaining)
                reveal.animateTo(1f, SkeletonTiming.SwapSpec)
            }
            shownAt = null
            hasSettledBody = true
        }
    }

    val handoff = reveal?.takeUnless { waiting }
    val hold = waiting || handoff != null
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            if (!deferBody) {
                CompositionLocalProvider(LocalGithubSkeletonEntrance provides null) {
                    Column(Modifier.layoutId(BodySlot)) { content() }
                }
            }
            if (hold) {
                // 准备中的文档已排版但不可点。占位层拦截命中，列表仍可滚动。
                Box(
                    Modifier.layoutId(PlaceholderSlot)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        }
                        .clearAndSetSemantics {},
                    propagateMinConstraints = true,
                ) {
                    placeholder()
                }
            }
        },
    ) { measurables, limits ->
        val body = measurables.firstOrNull { it.layoutId == BodySlot }?.measure(limits)
        val skeleton = measurables.firstOrNull { it.layoutId == PlaceholderSlot }?.measure(limits)
        val reserved =
            (reservedHeight.takeIf { it > 0 }
                    ?: skeleton?.height
                    ?: body?.height
                    ?: limits.minHeight)
                .coerceIn(limits.minHeight, limits.maxHeight)
        val target = body?.height ?: reserved
        val easeHeight = abs(target - reserved) <= SkeletonTiming.MaxHeightTravel.roundToPx()
        val width =
            if (waiting) maxOf(body?.width ?: 0, skeleton?.width ?: 0, limits.minWidth)
            else body?.width ?: skeleton?.width ?: limits.minWidth
        val height =
            when {
                waiting -> reserved
                handoff != null && easeHeight && target != reserved ->
                    lerp(reserved, target, handoff.value.coerceIn(0f, 1f))
                else -> target
            }
        val bodyBlur = SkeletonTiming.BodyBlur.toPx()
        val skeletonBlur = SkeletonTiming.SkeletonBlur.toPx()
        layout(width, height) {
            val progress = handoff?.value?.coerceIn(0f, 1f) ?: 0f
            when {
                // Renderer readiness can depend on placement even before the document can draw.
                waiting && loading -> body?.placeRelativeWithLayer(0, 0) { alpha = 0f }
                handoff != null && progress > 0f ->
                    body?.placeRelativeWithLayer(0, 0) {
                        alpha = progress
                        val scale = lerp(SkeletonTiming.BodyStartScale, 1f, progress)
                        scaleX = scale
                        scaleY = scale
                        val radius = (1f - progress) * bodyBlur
                        renderEffect =
                            if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                    }
                !hold -> body?.placeRelative(0, 0)
            }
            if (waiting) {
                if (showSkeleton) skeleton?.placeRelative(0, 0)
                else skeleton?.placeRelativeWithLayer(0, 0) { alpha = 0f }
            } else if (handoff != null && progress < 1f) {
                skeleton?.placeRelativeWithLayer(0, 0) {
                    alpha = 1f - progress
                    val radius = progress * skeletonBlur
                    renderEffect =
                        if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                }
            }
        }
    }
}
