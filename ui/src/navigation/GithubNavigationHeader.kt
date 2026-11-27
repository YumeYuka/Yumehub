package moe.yumeyuka.yumehub.ui.navigation

import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.GlassColors
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassContentColor
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionHost
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassSurfaceBrightness
import io.github.neoruaa.glasmio.glass.LocalGlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalIosToolbarActionGroup
import io.github.neoruaa.glasmio.glass.ToolbarActionTransform
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.icons.LocalSfToolbarSymbols
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderGroup
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationFrame
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationOperation
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationPhase
import io.github.neoruaa.glasmio.scaffold.glasmioAdaptiveContent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults

@Immutable
data class GithubHeaderSpec(
    val leading: List<GlasmioHeaderGroup> = emptyList(),
    val trailing: List<GlasmioHeaderGroup> = emptyList(),
    val backdrop: LayerBackdrop? = null,
    val colors: GlassColors? = null,
)

@Stable
class GithubHeaderMotionController(initialKey: Any) {
    private val frameState = mutableStateOf(GlasmioNavigationFrame(0L, initialKey, initialKey))
    val frame: State<GlasmioNavigationFrame>
        get() = frameState

    private val specs = mutableStateMapOf<Any, GithubHeaderSpec>()
    private val leadingWidths = mutableStateMapOf<Any, Int>()
    private val trailingWidths = mutableStateMapOf<Any, Int>()

    var committedKey: Any = initialKey
        private set

    val interactionKey: Any
        get() =
            if (
                frameState.value.phase == GlasmioNavigationPhase.Canceling ||
                    frameState.value.phase == GlasmioNavigationPhase.Tracking
            ) {
                frameState.value.fromEntryKey
            } else {
                frameState.value.toEntryKey
            }

    fun updateSpec(entryKey: Any, spec: GithubHeaderSpec) {
        if (specs[entryKey] != spec) {
            specs[entryKey] = spec
        }
    }

    fun removeSpec(entryKey: Any) {
        specs.remove(entryKey)
        leadingWidths.remove(entryKey)
        trailingWidths.remove(entryKey)
    }

    fun specFor(entryKey: Any): GithubHeaderSpec = specs[entryKey] ?: GithubHeaderSpec()

    internal fun publishWidths(entryKey: Any, leadingPx: Int?, trailingPx: Int?) {
        if (leadingPx != null) leadingWidths[entryKey] = leadingPx
        if (trailingPx != null) trailingWidths[entryKey] = trailingPx
    }

    fun leadingWidthPx(entryKey: Any): Int = leadingWidths[entryKey] ?: 0

    fun trailingWidthPx(entryKey: Any): Int = trailingWidths[entryKey] ?: 0

    fun beginNavigation(from: Any, to: Any, operation: GlasmioNavigationOperation) {
        if (from == to && frameState.value.phase == GlasmioNavigationPhase.Idle) return
        frameState.value =
            GlasmioNavigationFrame(
                transitionId = frameState.value.transitionId + 1L,
                fromEntryKey = from,
                toEntryKey = to,
                operation = operation,
                phase = GlasmioNavigationPhase.Settling,
            )
    }

    fun beginTracking(from: Any, to: Any) {
        frameState.value =
            GlasmioNavigationFrame(
                transitionId = frameState.value.transitionId + 1L,
                fromEntryKey = from,
                toEntryKey = to,
                operation = GlasmioNavigationOperation.Pop,
                phase = GlasmioNavigationPhase.Tracking,
            )
    }

    fun phase(phase: GlasmioNavigationPhase) {
        frameState.value = frameState.value.copy(phase = phase)
    }

    fun gesture(progress: Float) {
        frameState.value = frameState.value.copy(gestureProgress = progress.coerceIn(0f, 1f))
    }

    fun finish(key: Any) {
        val old = frameState.value
        committedKey = key
        frameState.value =
            old.copy(
                fromEntryKey = key,
                toEntryKey = key,
                phase = GlasmioNavigationPhase.Idle,
                gestureProgress = 0f,
                gestureVelocity = 0f,
            )
        pruneInactiveSpecs()
    }

    private fun pruneInactiveSpecs() {
        val frame = frameState.value
        val active = setOf(frame.fromEntryKey, frame.toEntryKey, committedKey)
        if (specs.size > 16) {
            val iterator = specs.keys.iterator()
            while (iterator.hasNext()) {
                val k = iterator.next()
                if (k !in active) {
                    iterator.remove()
                    leadingWidths.remove(k)
                    trailingWidths.remove(k)
                }
            }
        }
    }
}

val LocalGithubHeaderMotion = staticCompositionLocalOf<GithubHeaderMotionController?> { null }
val LocalGithubHeaderEntryKey = staticCompositionLocalOf<Any?> { null }
val LocalGithubHeaderActionsSink =
    staticCompositionLocalOf<((List<GlasmioHeaderAction>) -> Unit)?> { null }

private data class NavHeaderSpring(val duration: Float, val bounce: Float) {
    val omega = (2.0 * PI / duration).toFloat()
    val damping = if (bounce >= 0f) 1f - bounce else 1f / (1f + bounce)
}

private object NavHeaderSprings {
    val geometry = NavHeaderSpring(.416f, .25f)
    val interactive = NavHeaderSpring(.2f, 0f)
    val up = NavHeaderSpring(.292f, .5f)
    val downHeight = NavHeaderSpring(.416f, .416f)
    val downWidth = NavHeaderSpring(.416f, .5f)
    val kick = NavHeaderSpring(.25f, 0f)
    val returning = NavHeaderSpring(.5f, .166f)
}

private class NavHeaderTrack(
    initial: Float,
    private val positionTolerance: Float = .001f,
    private val velocityTolerance: Float = .015f,
) {
    var value by mutableFloatStateOf(initial)
        private set

    var velocity = 0f
        private set

    var target = initial
    var spring = NavHeaderSprings.geometry
    var delay = 0f
    val settled: Boolean
        get() =
            abs(value - target) < positionTolerance &&
                abs(velocity) < velocityTolerance &&
                delay <= 0f

    fun snap(value: Float) {
        this.value = value
        this.velocity = 0f
        this.target = value
        this.delay = 0f
    }

    fun advance(seconds: Float, reduceMotion: Boolean) {
        val dt = (seconds - delay.coerceAtLeast(0f)).coerceAtLeast(0f)
        delay = (delay - seconds).coerceAtLeast(0f)
        if (dt == 0f) return
        val w = spring.omega
        val z = if (reduceMotion) 1f else spring.damping
        val x = value - target
        val v = velocity
        val decay = exp(-z * w * dt)
        if (z < 1f) {
            val wd = w * sqrt(1f - z * z)
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            value = target + decay * (x * c + (v + z * w * x) / wd * s)
            velocity = decay * (v * c - (z * w * v + w * w * x) / wd * s)
        } else {
            val b = v + w * x
            value = target + decay * (x + b * dt)
            velocity = decay * (v - w * b * dt)
        }
        if (settled) {
            value = target
            velocity = 0f
        }
    }
}

private class NavHeaderPulse {
    private val x = arrayOf(NavHeaderTrack(0f), NavHeaderTrack(1f))
    private val y = arrayOf(NavHeaderTrack(0f), NavHeaderTrack(1f))
    private var rising = 0

    fun trigger() {
        val up = rising
        val down = 1 - up
        for (channels in listOf(x, y)) {
            channels[up].target = 1f
            channels[up].spring = NavHeaderSprings.up
            channels[up].delay = 0f
            channels[down].target = 0f
        }
        x[down].spring = NavHeaderSprings.downWidth
        x[down].delay = .142f
        y[down].spring = NavHeaderSprings.downHeight
        y[down].delay = .113f
        rising = down
    }

    fun snap() {
        (x + y).forEach { it.snap(it.target) }
    }

    fun advance(dt: Float, reduced: Boolean) {
        (x + y).forEach { it.advance(dt, reduced) }
    }

    fun scale(size: Float, pointLimit: Float, horizontal: Boolean): Float {
        val r = min(sqrt(1.2f), sqrt(1f + pointLimit / size.coerceAtLeast(1f)))
        val channels = if (horizontal) x else y
        fun l(p: Float) = 1f / r + (r - 1f / r) * p
        return l(channels[0].value) * l(channels[1].value)
    }

    val settled: Boolean
        get() = (x + y).all { it.settled }
}

private data class NavLayerKey(val action: Any, val visual: Any)

private class NavHeaderLayer(var action: GlasmioHeaderAction, visible: Float) {
    var content = action.content
    val visibility = NavHeaderTrack(visible)
    val x = NavHeaderTrack(0f, positionTolerance = .1f, velocityTolerance = 1f)
    var width = 0
    var positioned = false
}

private class NavGroupPresentation {
    val width = NavHeaderTrack(0f, positionTolerance = .1f, velocityTolerance = 1f)
    val presence = NavHeaderTrack(0f)
    val pulse = NavHeaderPulse()
    val kick = NavHeaderTrack(0f, positionTolerance = .1f, velocityTolerance = 1f)
    var kickRequested = false
    var returnIn = 0f
    val layers = linkedMapOf<NavLayerKey, NavHeaderLayer>()
    val measuredWidths = mutableMapOf<NavLayerKey, Int>()
    var initialized = false
    var signature: List<NavLayerKey>? = null
    var lastTick = 0L
    var durationScale = 1f

    fun tick(now: Long, reduced: Boolean): Boolean {
        val dt =
            (if (lastTick == 0L) 0f
                else (now - lastTick) / 1e9f / durationScale.coerceAtLeast(.01f))
                .coerceIn(0f, 1f)
        lastTick = now
        if (durationScale == 0f) {
            (listOf(width, presence, kick) + layers.values.flatMap { listOf(it.visibility, it.x) })
                .forEach { it.snap(it.target) }
            pulse.snap()
            returnIn = 0f
        }
        width.advance(dt, reduced)
        presence.advance(dt, reduced)
        pulse.advance(dt, reduced)
        kick.advance(dt, reduced)
        if (returnIn > 0f) {
            returnIn -= dt
            if (returnIn <= 0f) {
                kick.target = 0f
                kick.spring = NavHeaderSprings.returning
                kick.delay = 0f
            }
        }
        layers.values.forEach {
            it.visibility.advance(dt, reduced)
            it.x.advance(dt, reduced)
        }
        return layers.entries.removeAll {
            it.value.visibility.target == 0f && it.value.visibility.settled
        }
    }

    val settled: Boolean
        get() =
            width.settled &&
                presence.settled &&
                pulse.settled &&
                kick.settled &&
                returnIn <= 0f &&
                layers.values.all { it.visibility.settled && it.x.settled }
}

private data class NavGroupEndpointWidths(
    val oldKeys: List<NavLayerKey>,
    val newKeys: List<NavLayerKey>,
    val oldWidth: Int,
    val newWidth: Int,
    val density: Float,
    val fontScale: Float,
)

/**
 * 屏幕空间固定的导航顶栏操作宿主。
 *
 * 与 Glasmio `GlasmioNavigationHeader` 同构：左右玻璃组不随路由滑出或缩放， 跨页切换时在屏幕锚点上连续 morph，并在手势返回时实时跟随进度。
 */
@Composable
fun BoxScope.GithubNavigationHeaderOverlay(
    motion: GithubHeaderMotionController,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    val frame by motion.frame
    val source = motion.specFor(frame.fromEntryKey)
    val target = motion.specFor(frame.toEntryKey)
    val tracking = frame.phase == GlasmioNavigationPhase.Tracking
    val canceling = frame.phase == GlasmioNavigationPhase.Canceling
    val owner = if (tracking || canceling) source else target
    val progress =
        when {
            tracking -> frame.gestureProgress.coerceIn(0f, 1f)
            canceling -> 0f
            else -> 1f
        }
    val retainedLeading = remember { linkedMapOf<Any, GlasmioHeaderGroup>() }
    val retainedTrailing = remember { linkedMapOf<Any, GlasmioHeaderGroup>() }
    val density = LocalDensity.current
    val colors = owner.colors ?: GlasmioTheme.colors
    val resolvedBackdrop = backdrop ?: owner.backdrop ?: LocalGlassBackdrop.current
    val horizontalPadding = GlassTopAppBarDefaults.HorizontalPadding + 4.dp

    CompositionLocalProvider(
        LocalGlassColors provides colors,
        LocalGlassContentColor provides colors.content,
        LocalGlassSurfaceBrightness provides 1f,
        LocalGlassSurfaceStyle provides GlassSurfaceStyle.Navigation,
        LocalGlassBackdrop provides resolvedBackdrop,
    ) {
        Box(
            modifier =
                modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .glasmioAdaptiveContent()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                    .height(TopAppBarDefaults.CollapsedHeight)
                    .padding(horizontal = horizontalPadding)
                    .offset(y = 1.dp),
        ) {
            NavHeaderSide(
                from = source.leading,
                to = target.leading,
                owner = if (owner === source) source.leading else target.leading,
                retained = retainedLeading,
                progress = progress,
                tracking = tracking,
                modifier = Modifier.align(Alignment.CenterStart),
                leading = true,
            ) { old, new ->
                motion.publishWidths(frame.fromEntryKey, leadingPx = old, trailingPx = null)
                motion.publishWidths(frame.toEntryKey, leadingPx = new, trailingPx = null)
            }
            NavHeaderSide(
                from = source.trailing,
                to = target.trailing,
                owner = if (owner === source) source.trailing else target.trailing,
                retained = retainedTrailing,
                progress = progress,
                tracking = tracking,
                modifier = Modifier.align(Alignment.CenterEnd),
                leading = false,
            ) { old, new ->
                motion.publishWidths(frame.fromEntryKey, leadingPx = null, trailingPx = old)
                motion.publishWidths(frame.toEntryKey, leadingPx = null, trailingPx = new)
            }
        }
    }
}

@Composable
private fun NavHeaderSide(
    from: List<GlasmioHeaderGroup>,
    to: List<GlasmioHeaderGroup>,
    owner: List<GlasmioHeaderGroup>,
    retained: MutableMap<Any, GlasmioHeaderGroup>,
    progress: Float,
    tracking: Boolean,
    modifier: Modifier,
    leading: Boolean,
    onEndpointWidths: (Int?, Int?) -> Unit,
) {
    var retainedRevision by remember { mutableIntStateOf(0) }
    val measured = remember { mutableMapOf<Any, NavGroupEndpointWidths>() }
    val origins = remember { mutableMapOf<Any, Int>() }
    val density = LocalDensity.current

    fun total(groups: List<GlasmioHeaderGroup>, old: Boolean): Int? {
        val active = groups.filter { it.actions.isNotEmpty() }
        if (active.isEmpty()) return 0
        var width = with(density) { 8.dp.roundToPx() } * (active.size - 1)
        active.forEach { group ->
            val endpoint = measured[group.key] ?: return null
            if (endpoint.density != density.density || endpoint.fontScale != density.fontScale)
                return null
            val keys = group.actions.map { NavLayerKey(it.key, it.visualKey) }
            if ((if (old) endpoint.oldKeys else endpoint.newKeys) != keys) return null
            width += if (old) endpoint.oldWidth else endpoint.newWidth
        }
        return width
    }

    fun publish() {
        onEndpointWidths(total(from, true), total(to, false))
    }

    SideEffect { publish() }
    retainedRevision
    (from + to).forEach { retained[it.key] = it }
    val orderedKeys = buildList {
        (owner + to + from).map { it.key }.distinct().forEach(::add)
        retained.keys.filterNot { it in this }.forEach(::add)
    }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            orderedKeys.forEach { groupKey ->
                val fallback = retained.getValue(groupKey)
                key(groupKey) {
                    val old = from.firstOrNull { it.key == groupKey }
                    val new = to.firstOrNull { it.key == groupKey }
                    NavHeaderGroup(
                        from = old,
                        to = new,
                        owner = owner.firstOrNull { it.key == groupKey },
                        fallback = fallback,
                        progress = progress,
                        tracking = tracking,
                        onEndpoints = { oldWidth, newWidth ->
                            measured[groupKey] =
                                NavGroupEndpointWidths(
                                    old?.actions.orEmpty().map {
                                        NavLayerKey(it.key, it.visualKey)
                                    },
                                    new?.actions.orEmpty().map {
                                        NavLayerKey(it.key, it.visualKey)
                                    },
                                    oldWidth,
                                    newWidth,
                                    density.density,
                                    density.fontScale,
                                )
                            publish()
                        },
                        onGone = {
                            retained.remove(groupKey)
                            measured.remove(groupKey)
                            origins.remove(groupKey)
                            retainedRevision++
                        },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val childrenByKey = orderedKeys.zip(children).toMap()
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth

        fun positions(groups: List<GlasmioHeaderGroup>): Map<Any, Int> {
            val active = groups.filter { it.actions.isNotEmpty() }
            fun groupWidth(key: Any): Int = childrenByKey[key]?.width ?: 0
            val total =
                active.sumOf { groupWidth(it.key) } + gap * (active.size - 1).coerceAtLeast(0)
            var x = if (leading) 0 else width - total
            return active.associate { group ->
                (group.key to x).also { x += groupWidth(group.key) + gap }
            }
        }

        val activePositions = positions(owner)
        val oldPositions = positions(from)
        val newPositions = positions(to)
        val height = children.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            orderedKeys.forEachIndexed { index, groupKey ->
                val x =
                    if (tracking) {
                        val oldX =
                            oldPositions[groupKey]
                                ?: newPositions[groupKey]
                                ?: origins[groupKey]
                                ?: 0
                        val newX = newPositions[groupKey] ?: oldX
                        (oldX + (newX - oldX) * progress).roundToInt()
                    } else {
                        activePositions[groupKey]
                            ?: origins[groupKey]
                            ?: oldPositions[groupKey]
                            ?: 0
                    }
                origins[groupKey] = x
                children[index].placeRelative(x, 0)
            }
        }
    }
}

@Composable
private fun NavHeaderGroup(
    from: GlasmioHeaderGroup?,
    to: GlasmioHeaderGroup?,
    owner: GlasmioHeaderGroup?,
    fallback: GlasmioHeaderGroup,
    progress: Float,
    tracking: Boolean,
    onEndpoints: (Int, Int) -> Unit,
    onGone: () -> Unit,
) {
    val state = remember { NavGroupPresentation() }
    var structureRevision by remember { mutableIntStateOf(0) }
    var motionRevision by remember { mutableIntStateOf(0) }
    var widthRevision by remember { mutableIntStateOf(0) }
    var placementRevision by remember { mutableIntStateOf(0) }
    var layoutRevision by remember { mutableIntStateOf(0) }
    val targetActions = to?.actions.orEmpty()
    val fromKeys = from?.actions.orEmpty().map { NavLayerKey(it.key, it.visualKey) }
    val toKeys = to?.actions.orEmpty().map { NavLayerKey(it.key, it.visualKey) }
    val signature = if (progress == 0f && !tracking) fromKeys else toKeys
    val reduced = remember { mutableStateOf(false) }
    val changed = state.signature != signature
    if (changed) {
        if (state.signature != null) {
            state.pulse.trigger()
            state.kickRequested = true
        }
        state.signature = signature
    }
    (from?.actions.orEmpty() + targetActions).forEach { action ->
        val identity = NavLayerKey(action.key, action.visualKey)
        val desiredVisibility =
            (if (identity in fromKeys) 1f - progress else 0f) +
                (if (identity in toKeys) progress else 0f)
        val existing = state.layers[identity]
        if (existing != null) existing.action = action
        else if (desiredVisibility > 0f || identity !in state.measuredWidths) {
            state.layers[identity] =
                NavHeaderLayer(action, if (!state.initialized && identity in fromKeys) 1f else 0f)
        }
    }
    state.measuredWidths.keys.retainAll((fromKeys + toKeys + state.layers.keys).toSet())
    state.layers.forEach { (identity, layer) ->
        val start = if (identity in fromKeys) 1f else 0f
        val end = if (identity in toKeys) 1f else 0f
        val targetVisibility = start + (end - start) * progress
        if (targetVisibility > 0f) {
            val liveAction =
                owner?.actions?.firstOrNull { NavLayerKey(it.key, it.visualKey) == identity }
                    ?: layer.action
            layer.content = liveAction.content
        }
        layer.visibility.target = targetVisibility
        layer.visibility.spring =
            if (tracking) NavHeaderSprings.interactive else NavHeaderSprings.geometry
    }
    state.presence.target =
        (if (from != null) 1f else 0f) * (1f - progress) + (if (to != null) 1f else 0f) * progress
    SideEffect {
        if (
            state.initialized &&
                state.settled &&
                state.presence.target == 0f &&
                from == null &&
                to == null
        ) {
            onGone()
        }
    }
    LaunchedEffect(state, signature, progress, layoutRevision) {
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        state.durationScale = scale
        reduced.value = scale == 0f
        do {
            withFrameNanos { now ->
                val prevWidth = state.width.value.roundToInt()
                var prevXHash = 1
                state.layers.values.forEach { prevXHash = prevXHash * 31 + it.x.value.roundToInt() }
                val removed = state.tick(now, reduced.value)
                motionRevision++
                if (state.width.value.roundToInt() != prevWidth) widthRevision++
                var nextXHash = 1
                state.layers.values.forEach { nextXHash = nextXHash * 31 + it.x.value.roundToInt() }
                if (removed || nextXHash != prevXHash) placementRevision++
                if (
                    removed || (state.initialized && state.settled && state.presence.target == 0f)
                ) {
                    structureRevision++
                }
            }
        } while (!state.settled)
        state.lastTick = 0L
    }
    structureRevision
    val layerEntries = state.layers.toList()
    val hitActions = owner?.actions ?: (to ?: from ?: fallback).actions
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val pointLimit = with(density) { 16.dp.toPx() }
    val height = with(density) { 44.dp.toPx() }
    val presentationBoundsProvider =
        remember(state, height) {
            {
                motionRevision
                Rect(0f, 0f, state.width.value.coerceAtLeast(0f), height)
            }
        }
    val presentationTransformProvider =
        remember(state, height, pointLimit, direction, reduced) {
            {
                motionRevision
                val presence = state.presence.value.coerceIn(0f, 1f)
                val sx =
                    if (reduced.value) 1f
                    else state.pulse.scale(state.width.value, pointLimit, true)
                val sy = if (reduced.value) 1f else state.pulse.scale(height, pointLimit, false)
                val appearScale = if (reduced.value) 1f else 1.2f - .2f * presence
                val xScale = sx * appearScale
                val yScale = sy * appearScale
                ToolbarActionTransform(
                    xScale,
                    yScale,
                    state.width.value * (1f - xScale) / 2f +
                        (if (reduced.value) 0f
                        else state.kick.value * if (direction == LayoutDirection.Rtl) -1f else 1f),
                    height * (1f - yScale) / 2f,
                )
            }
        }
    GlasmioToolbarActionHost(
        modifier =
            Modifier.graphicsLayer {
                motionRevision
                alpha = state.presence.value.coerceIn(0f, 1f)
                outsets = LayerOutsets(64.dp, 64.dp, 64.dp, 64.dp)
            },
        style = (owner ?: to ?: from ?: fallback).style,
        presentationBounds = presentationBoundsProvider,
        presentationTransform = presentationTransformProvider,
    ) {
        val toolbarGroup = LocalIosToolbarActionGroup.current
        val visualCount = layerEntries.size
        Layout(
            content = {
                layerEntries.forEach { (identity, layer) ->
                    key("vis", identity) {
                        val foreground = rememberGraphicsLayer()
                        val recorded = remember { booleanArrayOf(false) }
                        val blurPadPx = with(density) { 32.dp.roundToPx() }
                        Box(
                            Modifier.defaultMinSize(minWidth = 44.dp)
                                .height(44.dp)
                                .padding(horizontal = 10.dp)
                                .graphicsLayer {
                                    motionRevision
                                    val member = toolbarGroup?.members?.firstOrNull()
                                    if (member != null) {
                                        val x =
                                            if (direction == LayoutDirection.Rtl) {
                                                state.width.value - layer.x.value - layer.width
                                            } else layer.x.value
                                        toolbarGroup.applyTransform(
                                            this,
                                            Rect(x, 0f, x + layer.width, height),
                                            toolbarGroup.pressTransform(member),
                                        )
                                    }
                                }
                                .graphicsLayer {
                                    motionRevision
                                    val p = layer.visibility.value
                                    alpha = p.coerceIn(0f, 1f)
                                    scaleX = if (reduced.value) 1f else .2f + .8f * p
                                    scaleY = if (reduced.value) 1f else .2f + .8f * p
                                }
                                .drawWithContent {
                                    motionRevision
                                    val p = layer.visibility.value
                                    val blur =
                                        if (reduced.value) 0f
                                        else 10.dp.toPx() * (1f - p).coerceIn(0f, 1f)
                                    val pad = if (blur > .01f) blurPadPx else 0
                                    val targetSize =
                                        IntSize(
                                            size.width.roundToInt().coerceAtLeast(1) + pad * 2,
                                            size.height.roundToInt().coerceAtLeast(1) + pad * 2,
                                        )
                                    if (
                                        !recorded[0] ||
                                            layer.visibility.target > 0f ||
                                            foreground.size != targetSize
                                    ) {
                                        foreground.clip = pad > 0
                                        foreground.record(size = targetSize) {
                                            if (pad > 0) {
                                                drawContext.transform.translate(
                                                    pad.toFloat(),
                                                    pad.toFloat(),
                                                )
                                                try {
                                                    this@drawWithContent.drawContent()
                                                } finally {
                                                    drawContext.transform.translate(
                                                        -pad.toFloat(),
                                                        -pad.toFloat(),
                                                    )
                                                }
                                            } else {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                        recorded[0] = true
                                    }
                                    foreground.renderEffect =
                                        if (blur > .01f) {
                                            android.graphics.RenderEffect.createBlurEffect(
                                                    blur,
                                                    blur,
                                                    Shader.TileMode.DECAL,
                                                )
                                                .asComposeRenderEffect()
                                        } else null
                                    if (pad > 0) {
                                        drawContext.transform.translate(
                                            -pad.toFloat(),
                                            -pad.toFloat(),
                                        )
                                        try {
                                            drawLayer(foreground)
                                        } finally {
                                            drawContext.transform.translate(
                                                pad.toFloat(),
                                                pad.toFloat(),
                                            )
                                        }
                                    } else {
                                        drawLayer(foreground)
                                    }
                                }
                                .clearAndSetSemantics {},
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(
                                LocalSfToolbarSymbols provides true,
                                LocalGlassContentColor provides LocalGlassColors.current.content,
                            ) {
                                layer.content()
                            }
                        }
                    }
                }
                hitActions.forEach { action ->
                    key("hit", action.key) {
                        val ownsInteraction = owner?.actions?.any { it.key == action.key } == true
                        GlassSurface(
                            modifier =
                                Modifier.fillMaxSize()
                                    .then(
                                        if (ownsInteraction) {
                                            action.menuAnchor?.let { anchor ->
                                                Modifier.glasmioMenuAnchor(
                                                    anchor,
                                                    action.onClick.takeIf { action.enabled },
                                                )
                                            } ?: Modifier
                                        } else Modifier,
                                    )
                                    .then(
                                        if (ownsInteraction) Modifier
                                        else Modifier.clearAndSetSemantics {},
                                    ),
                            style = GlassSurfaceStyle.Navigation,
                            enabled = ownsInteraction && action.enabled,
                            onClick = if (ownsInteraction) action.onClick else null,
                        ) {
                            if (ownsInteraction) {
                                Box(
                                    Modifier.matchParentSize().semantics {
                                        contentDescription = action.contentDescription
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            widthRevision
            val visualPlaceables = layerEntries.mapIndexed { index, (identity, layer) ->
                measurables[index]
                    .measure(
                        if (layer.visibility.target == 0f && layer.width > 0) {
                            Constraints.fixed(layer.width, height.roundToInt())
                        } else {
                            Constraints(
                                maxWidth = constraints.maxWidth,
                                maxHeight = height.roundToInt(),
                            )
                        },
                    )
                    .also {
                        layer.width = it.width
                        state.measuredWidths[identity] = it.width
                    }
            }

            fun endpoint(keys: List<NavLayerKey>): Pair<Float, Map<Any, Float>> {
                var x = 0f
                val positions = linkedMapOf<Any, Float>()
                keys.forEachIndexed { index, identity ->
                    positions[identity.action] = x
                    x += state.measuredWidths[identity] ?: 0
                    if (index < keys.lastIndex) x += 8.dp.toPx()
                }
                return x to positions
            }

            val (oldWidth, oldX) = endpoint(fromKeys)
            val (newWidth, newX) = endpoint(toKeys)
            onEndpoints(oldWidth.roundToInt(), newWidth.roundToInt())
            if (state.kickRequested) {
                val offsets =
                    oldX.keys.intersect(newX.keys).map { newX.getValue(it) - oldX.getValue(it) }
                state.kick.target = if (offsets.isEmpty()) 0f else offsets.average().toFloat() / 6f
                state.kick.spring = NavHeaderSprings.kick
                state.kick.delay = .05f
                state.returnIn = .15f
                state.kickRequested = false
            }
            val width =
                when {
                    from == null && to == null -> state.width.target
                    from == null -> newWidth
                    to == null -> oldWidth
                    else -> oldWidth + (newWidth - oldWidth) * progress
                }
            var layoutChanged = state.width.target != width
            state.width.target = width
            state.width.spring =
                if (tracking) NavHeaderSprings.interactive else NavHeaderSprings.geometry
            layerEntries.forEach { (identity, layer) ->
                val start = oldX[identity.action] ?: newX[identity.action] ?: layer.x.target
                val end = newX[identity.action] ?: oldX[identity.action] ?: layer.x.target
                val targetX = start + (end - start) * progress
                layoutChanged = layoutChanged || layer.x.target != targetX
                layer.x.target = targetX
                if (!layer.positioned) {
                    layer.x.snap(layer.x.target)
                    layer.positioned = true
                }
            }
            if (!state.initialized) {
                state.width.snap(width)
                val targetPresence = state.presence.target
                state.presence.snap(if (from == null) 0f else 1f)
                state.presence.target = targetPresence
                state.initialized = true
            }
            if (layoutChanged) layoutRevision++
            val hitPlaceables = hitActions.mapIndexed { index, action ->
                val layer = state.layers[NavLayerKey(action.key, action.visualKey)]
                val placeable =
                    measurables[visualCount + index].measure(
                        Constraints.fixed(
                            layer?.width ?: 44.dp.roundToPx(),
                            height.roundToInt(),
                        ),
                    )
                layer to placeable
            }
            layout(
                state.width.value.roundToInt().coerceIn(0, constraints.maxWidth),
                height.roundToInt(),
            ) {
                placementRevision
                visualPlaceables.forEachIndexed { index, placeable ->
                    val layer = layerEntries[index].second
                    placeable.placeRelative(layer.x.value.roundToInt(), 0)
                }
                hitPlaceables.forEach { (layer, placeable) ->
                    placeable.placeRelative((layer?.x?.value ?: 0f).roundToInt(), 0)
                }
            }
        }
    }
}
