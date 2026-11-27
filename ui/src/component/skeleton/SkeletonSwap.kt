package moe.yumeyuka.yumehub.ui.component.skeleton

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubSkeletonEntrance

/** 骨架交接的唯一时序与曲线；所有 Card、文档正文都从这里取参数。 */
object SkeletonTiming {
    // Only delay showing the skeleton, never delay showing ready content.
    internal const val ShowDelayMillis = 100L
    // 入场先出骨架再交接正文，与缓存无关；前进转场 300ms，恢复不留骨架。
    internal const val MinShownMillis = 240L
    internal const val RowStaggerMillis = 60
    internal const val MaxStaggerRows = 5
    internal val RevealSpec = tween<Float>(360, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
    internal val TextRise = 4.dp
    internal val MaxHeightTravel = 320.dp

    val ShimmerSpec = tween<Float>(1600, easing = LinearEasing)
}

/** 一次交接的单一时钟：骨架淡出、槽高缓动与各行依次淡入共用，行相位只在布局和绘制阶段读取。 */
private class SkeletonReveal {
    private val clock = Animatable(0f)
    private var rows = 0
    private var stagger = 0f
    private var span = 1f

    /** 交接结束后组合的行直接呈现，不再挂图层。 */
    var settled by mutableStateOf(false)
        private set

    val value: Float get() = clock.value

    fun claim(): Int = minOf(rows++, SkeletonTiming.MaxStaggerRows - 1)

    suspend fun snap() = clock.snapTo(0f)

    suspend fun run() {
        val spec = SkeletonTiming.RevealSpec
        val last = minOf(rows.coerceAtLeast(1), SkeletonTiming.MaxStaggerRows) - 1
        val duration = spec.durationMillis + last * SkeletonTiming.RowStaggerMillis
        stagger = if (last == 0) 0f else SkeletonTiming.RowStaggerMillis.toFloat() / duration
        span = spec.durationMillis.toFloat() / duration
        clock.animateTo(1f, tween(duration, easing = LinearEasing))
        settled = true
    }

    /** 首行的相位即整块正文的相位。 */
    fun alphaFor(row: Int): Float {
        val progress =
            ((clock.value - minOf(row, SkeletonTiming.MaxStaggerRows - 1) * stagger) / span)
                .coerceIn(0f, 1f)
        return if (progress >= 1f) 1f else SkeletonTiming.RevealSpec.easing.transform(progress)
    }
}

private val LocalSkeletonReveal = staticCompositionLocalOf<SkeletonReveal?> { null }

/** 正文的一行：交接时按组合顺序依次淡入上浮。 */
@Composable
fun Modifier.skeletonRow(): Modifier {
    val reveal = LocalSkeletonReveal.current ?: return this
    if (reveal.settled) return this
    val row = remember(reveal) { reveal.claim() }
    val density = LocalDensity.current
    val rise = with(density) { SkeletonTiming.TextRise.toPx() }
    return graphicsLayer {
        val alpha = reveal.alphaFor(row)
        this.alpha = alpha
        translationY = (1f - alpha) * rise
    }
}

private const val BodySlot = "skeleton-swap-body"
private const val PlaceholderSlot = "skeleton-swap-placeholder"

/**
 * A shown skeleton hands off to the ready body on a single clock: fade the bones out, let the body
 * rise into its final position with its rows following one after another, and ease the slot height
 * without reflowing the text. Large height changes commit the ready height together with the fade.
 *
 * 首次呈现时不论数据是否已就绪都先出骨架再交接；返回恢复和滑动途中露出的相邻标签不算入场。
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
    val pending = loading || contentPending
    val animate = LocalGithubCardMotionEnabled.current
    var presented by remember { mutableStateOf(!entrance || !animate && !pending) }
    var skeletonShown by remember { mutableStateOf(!presented || pending && !animate) }
    var ready by remember { mutableStateOf(!entrance) }
    val reveal = remember { SkeletonReveal() }

    LaunchedEffect(entrance) {
        if (entrance) {
            delay(SkeletonTiming.MinShownMillis)
            ready = true
        }
    }
    val waiting = pending || !presented
    LaunchedEffect(pending, loading, animate, waiting, ready) {
        when {
            !animate -> {
                reveal.snap()
                skeletonShown = pending
                if (!pending) presented = true
            }
            waiting -> {
                reveal.snap()
                if (!skeletonShown) {
                    if (loading) delay(SkeletonTiming.ShowDelayMillis)
                    skeletonShown = true
                }
            }
            skeletonShown -> {
                reveal.run()
                skeletonShown = false
                presented = true
            }
        }
    }

    val handoff = !waiting && skeletonShown
    val showPlaceholder = skeletonShown || waiting && !animate
    val hold = waiting || handoff
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            if (!contentPending) {
                CompositionLocalProvider(LocalSkeletonReveal provides reveal) {
                    Column(Modifier.layoutId(BodySlot)) { content() }
                }
            }
            if (hold) {
                // 骨架在最上层接收触摸，透明正文不可点；祖先的滚动不受影响。
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
                    if (showPlaceholder) SkeletonShimmerHost(placeholder) else placeholder()
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
        val easeHeight =
            handoff && abs(target - reserved) <= SkeletonTiming.MaxHeightTravel.roundToPx()
        val height =
            when {
                waiting -> reserved
                easeHeight && target != reserved -> lerp(reserved, target, reveal.value)
                else -> target
            }
        val width =
            if (waiting) maxOf(body?.width ?: 0, skeleton?.width ?: 0, limits.minWidth)
            else body?.width ?: skeleton?.width ?: limits.minWidth
        val fade = if (handoff) reveal.alphaFor(0) else 0f
        val rise = SkeletonTiming.TextRise.toPx()
        layout(width, height) {
            if (handoff) {
                body?.placeRelativeWithLayer(0, 0) {
                    alpha = fade
                    translationY = (1f - fade) * rise
                }
            } else {
                body?.placeRelative(0, 0)
            }
            if (waiting) {
                if (showPlaceholder) skeleton?.placeRelative(0, 0)
                else skeleton?.placeRelativeWithLayer(0, 0) { alpha = 0f }
            } else if (handoff) {
                skeleton?.placeRelativeWithLayer(0, 0) { alpha = 1f - fade }
            }
        }
    }
}

/** 扫光相位。宿主页面只建一个无限动画，占位块在绘制阶段读取同一相位，整屏只有一条光带。 */
internal val LocalPlaceholderSweep = staticCompositionLocalOf<State<Float>?> { null }

/** 关掉某个区域的扫光；列表骨架保持静态灰块，避免整屏光带在转场里抖动。 */
val LocalSkeletonShimmerEnabled = staticCompositionLocalOf { true }

/** 为下层所有占位提供共享扫光时钟；已有宿主时直接透传，不重复创建动画。 */
@Composable
internal fun SkeletonShimmerHost(content: @Composable () -> Unit) {
    val parent = LocalPlaceholderSweep.current
    if (
        !LocalSkeletonShimmerEnabled.current ||
            parent != null ||
            !LocalGithubCardMotionEnabled.current
    ) {
        CompositionLocalProvider(LocalPlaceholderSweep provides parent, content = content)
        return
    }
    val clock = rememberInfiniteTransition(label = "skeleton-shimmer")
    val sweep =
        clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = SkeletonTiming.ShimmerSpec,
                    repeatMode = RepeatMode.Restart,
                ),
            label = "skeleton-sweep",
        )
    CompositionLocalProvider(LocalPlaceholderSweep provides sweep, content = content)
}
