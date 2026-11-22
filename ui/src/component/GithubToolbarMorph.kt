package moe.yumeyuka.yumehub.ui.component

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassContentColor
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionHost
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalIosToolbarActionGroup
import io.github.neoruaa.glasmio.glass.ToolbarActionTransform
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.icons.LocalSfToolbarSymbols
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext

// 改编自 Glasmio 089376b 的 HeaderMotion.kt 与 GlasmioNavigationScaffold.kt，
// 以及工作区的独立工具栏接入；Apache-2.0，许可见 third_party/glasmio/LICENSE。
// 这里只保留页面和面板内的独立操作组，不处理导航手势和搜索区域。
private data class ToolbarLayerKey(val action: Any, val visual: Any)

private data class ToolbarSpring(val duration: Float, val bounce: Float) {
    val omega = (2.0 * PI / duration).toFloat()
    val damping = if (bounce >= 0f) 1f - bounce else 1f / (1f + bounce)
}

private object ToolbarSprings {
    val geometry = ToolbarSpring(.416f, .25f)
    val up = ToolbarSpring(.292f, .5f)
    val downHeight = ToolbarSpring(.416f, .416f)
    val downWidth = ToolbarSpring(.416f, .5f)
    val kick = ToolbarSpring(.25f, 0f)
    val returning = ToolbarSpring(.5f, .166f)
}

private class ToolbarTrack(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set

    var velocity = 0f
        private set

    var target = initial
    var spring = ToolbarSprings.geometry
    var delay = 0f
    val settled
        get() = abs(value - target) < .0005f && abs(velocity) < .005f && delay <= 0f

    fun snap(value: Float) {
        this.value = value
        velocity = 0f
        target = value
        delay = 0f
    }

    fun advance(seconds: Float, reduced: Boolean) {
        val dt = (seconds - delay.coerceAtLeast(0f)).coerceAtLeast(0f)
        delay = (delay - seconds).coerceAtLeast(0f)
        if (dt == 0f) return
        val w = spring.omega
        val z = if (reduced) 1f else spring.damping
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

private class ToolbarPulse {
    private val x = arrayOf(ToolbarTrack(0f), ToolbarTrack(1f))
    private val y = arrayOf(ToolbarTrack(0f), ToolbarTrack(1f))
    private var rising = 0

    fun trigger() {
        val up = rising
        val down = 1 - up
        for (channels in listOf(x, y)) {
            channels[up].target = 1f
            channels[up].spring = ToolbarSprings.up
            channels[up].delay = 0f
            channels[down].target = 0f
        }
        x[down].spring = ToolbarSprings.downWidth
        x[down].delay = .142f
        y[down].spring = ToolbarSprings.downHeight
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

    val settled
        get() = (x + y).all { it.settled }
}

private class ToolbarLayer(var action: GlasmioHeaderAction, visible: Float) {
    var content = action.content
    val visibility = ToolbarTrack(visible)
    val x = ToolbarTrack(0f)
    var width = 0
    var positioned = false
}

private class ToolbarPresentation {
    val width = ToolbarTrack(0f)
    val presence = ToolbarTrack(0f)
    val pulse = ToolbarPulse()
    val kick = ToolbarTrack(0f)
    var kickRequested = false
    var returnIn = 0f
    val layers = linkedMapOf<ToolbarLayerKey, ToolbarLayer>()
    val measuredWidths = mutableMapOf<ToolbarLayerKey, Int>()
    var initialized = false
    var signature: List<ToolbarLayerKey>? = null
    var lastTick = 0L
    var durationScale = 1f

    fun tick(now: Long, reduced: Boolean) {
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
                kick.spring = ToolbarSprings.returning
                kick.delay = 0f
            }
        }
        layers.values.forEach {
            it.visibility.advance(dt, reduced)
            it.x.advance(dt, reduced)
        }
        layers.entries.removeAll { it.value.visibility.target == 0f && it.value.visibility.settled }
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

@Composable
internal fun GithubToolbarMorph(actions: List<GlasmioHeaderAction>) {
    require(actions.map { it.key }.distinct().size == actions.size) {
        "Duplicate header action keys"
    }
    val target = actions.takeIf { it.isNotEmpty() }
    val lastEndpoint = remember { arrayOf(target) }
    val identity = actions.map { ToolbarLayerKey(it.key, it.visualKey) }
    var previous by remember(identity) { mutableStateOf(lastEndpoint[0]) }
    SideEffect { lastEndpoint[0] = target }
    if (target == null && previous == null) return
    ToolbarMorphGroup(previous, target) { previous = target }
}

@Composable
private fun ToolbarMorphGroup(
    from: List<GlasmioHeaderAction>?,
    to: List<GlasmioHeaderAction>?,
    onSettled: () -> Unit,
) {
    val state = remember { ToolbarPresentation() }
    var revision by remember { mutableIntStateOf(0) }
    var layoutRevision by remember { mutableIntStateOf(0) }
    val fromKeys = from.orEmpty().map { ToolbarLayerKey(it.key, it.visualKey) }
    val toKeys = to.orEmpty().map { ToolbarLayerKey(it.key, it.visualKey) }
    val reduced = remember { mutableStateOf(false) }
    if (state.signature != toKeys) {
        if (state.signature != null) {
            state.pulse.trigger()
            state.kickRequested = true
        }
        state.signature = toKeys
    }
    (from.orEmpty() + to.orEmpty()).forEach { action ->
        val identity = ToolbarLayerKey(action.key, action.visualKey)
        val existing = state.layers[identity]
        if (existing != null) existing.action = action
        else if (identity in toKeys || identity !in state.measuredWidths) {
            state.layers[identity] =
                ToolbarLayer(action, if (!state.initialized && identity in fromKeys) 1f else 0f)
        }
    }
    state.measuredWidths.keys.retainAll((fromKeys + toKeys + state.layers.keys).toSet())
    state.layers.forEach { (identity, layer) ->
        val visible = identity in toKeys
        if (visible) {
            layer.content =
                to?.firstOrNull { ToolbarLayerKey(it.key, it.visualKey) == identity }?.content
                    ?: layer.action.content
        }
        layer.visibility.target = if (visible) 1f else 0f
        layer.visibility.spring = ToolbarSprings.geometry
    }
    state.presence.target = if (to == null) 0f else 1f
    val latestOnSettled by rememberUpdatedState(onSettled)
    LaunchedEffect(state, toKeys, layoutRevision) {
        val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        state.durationScale = scale
        reduced.value = scale == 0f
        do {
            withFrameNanos { now ->
                state.tick(now, reduced.value)
                revision++
            }
        } while (!state.settled)
        state.lastTick = 0L
        latestOnSettled()
    }
    revision
    val layerEntries = state.layers.toList()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val pointLimit = with(density) { 16.dp.toPx() }
    val height = with(density) { 44.dp.toPx() }
    val presence = state.presence.value.coerceIn(0f, 1f)
    val sx = if (reduced.value) 1f else state.pulse.scale(state.width.value, pointLimit, true)
    val sy = if (reduced.value) 1f else state.pulse.scale(height, pointLimit, false)
    val appearScale = if (reduced.value) 1f else 1.2f - .2f * presence
    GlasmioToolbarActionHost(
        modifier =
            Modifier.testTag("GlasmioHeaderGroup:toolbar").graphicsLayer {
                alpha = presence
                outsets = LayerOutsets(64.dp, 64.dp, 64.dp, 64.dp)
            },
        presentationBounds = { Rect(0f, 0f, state.width.value.coerceAtLeast(0f), height) },
        presentationTransform = {
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
        },
    ) {
        val toolbarGroup = LocalIosToolbarActionGroup.current
        SubcomposeLayout { constraints ->
            revision
            val visuals = layerEntries.map { (identity, layer) ->
                subcompose(identity) {
                        val foreground = rememberGraphicsLayer()
                        val recorded = remember { booleanArrayOf(false) }
                        Box(
                            Modifier.defaultMinSize(minWidth = 44.dp)
                                .height(44.dp)
                                .padding(horizontal = 10.dp)
                                .graphicsLayer {
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
                                    outsets = LayerOutsets(32.dp, 32.dp, 32.dp, 32.dp)
                                    val p = layer.visibility.value
                                    alpha = p.coerceIn(0f, 1f)
                                    scaleX = if (reduced.value) 1f else .2f + .8f * p
                                    scaleY = if (reduced.value) 1f else .2f + .8f * p
                                    val blur =
                                        if (reduced.value) 0f
                                        else 10.dp.toPx() * (1f - p).coerceIn(0f, 1f)
                                    renderEffect =
                                        if (blur > .01f) {
                                            RenderEffect.createBlurEffect(
                                                    blur,
                                                    blur,
                                                    Shader.TileMode.DECAL,
                                                )
                                                .asComposeRenderEffect()
                                        } else null
                                }
                                .drawWithContent {
                                    if (!recorded[0] || layer.visibility.target > 0f) {
                                        foreground.record { this@drawWithContent.drawContent() }
                                        recorded[0] = true
                                    }
                                    drawLayer(foreground)
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
                    .single()
                    .measure(
                        if (layer.visibility.target == 0f && layer.width > 0) {
                            Constraints.fixed(layer.width, height.roundToInt())
                        } else
                            Constraints(
                                maxWidth = constraints.maxWidth,
                                maxHeight = height.roundToInt(),
                            ),
                    )
                    .also {
                        layer.width = it.width
                        state.measuredWidths[identity] = it.width
                    }
            }
            fun endpoint(keys: List<ToolbarLayerKey>): Pair<Float, Map<Any, Float>> {
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
            if (state.kickRequested) {
                val offsets =
                    oldX.keys.intersect(newX.keys).map { newX.getValue(it) - oldX.getValue(it) }
                state.kick.target = if (offsets.isEmpty()) 0f else offsets.average().toFloat() / 6f
                state.kick.spring = ToolbarSprings.kick
                state.kick.delay = .05f
                state.returnIn = .15f
                state.kickRequested = false
            }
            val width = if (to == null) oldWidth else newWidth
            var layoutChanged = state.width.target != width
            state.width.target = width
            state.width.spring = ToolbarSprings.geometry
            layerEntries.forEach { (identity, layer) ->
                val targetX = newX[identity.action] ?: oldX[identity.action] ?: layer.x.target
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
            val hitActions = to ?: from.orEmpty()
            val hits = hitActions.map { action ->
                val layer = state.layers[ToolbarLayerKey(action.key, action.visualKey)]
                val ownsInteraction = to?.any { it.key == action.key } == true
                val hit =
                    subcompose("hit" to action.key) {
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
                                if (ownsInteraction)
                                    Box(
                                        Modifier.matchParentSize().semantics {
                                            contentDescription = action.contentDescription
                                        },
                                    )
                            }
                        }
                        .single()
                        .measure(
                            Constraints.fixed(
                                layer?.width ?: 44.dp.roundToPx(),
                                height.roundToInt(),
                            ),
                        )
                (layer?.x?.value ?: 0f) to hit
            }
            layout(
                state.width.value.roundToInt().coerceIn(0, constraints.maxWidth),
                height.roundToInt(),
            ) {
                visuals.forEachIndexed { index, placeable ->
                    placeable.placeRelative(layerEntries[index].second.x.value.roundToInt(), 0)
                }
                hits.forEach { (x, placeable) -> placeable.placeRelative(x.roundToInt(), 0) }
            }
        }
    }
}
