package moe.yumeyuka.yumehub.feature.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import java.util.Random
import kotlin.math.roundToInt

private const val Steps = 24
private const val StepMillis = 850

private data class Led(val center: Offset, val start: Int, val duration: Int, val intensity: Float)

@Composable
internal fun LoginDotField(modifier: Modifier = Modifier, belowTitle: Boolean = false) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val seed = rememberSaveable { kotlin.random.Random.nextLong() }
    val step =
        if (lifecycle == Lifecycle.State.RESUMED) {
            rememberInfiniteTransition(label = "loginLeds")
                .animateValue(
                    initialValue = 0,
                    targetValue = Steps,
                    typeConverter = Int.VectorConverter,
                    animationSpec =
                        infiniteRepeatable(tween(Steps * StepMillis, easing = LinearEasing)),
                    label = "lightPattern",
                )
        } else null
    val ink = GlasmioTheme.colors.content
    Spacer(
        modifier.clipToBounds().drawWithCache {
            val pitch = 20.dp.toPx()
            val radius = pitch * 0.20f
            val columns = (size.width / pitch).toInt()
            val rows = (size.height / pitch).toInt()
            val left = (size.width - (columns - 1) * pitch) / 2f
            val top = (size.height - (rows - 1) * pitch) / 2f
            val random = Random(seed)
            val leds = mutableListOf<Led>()

            fun cluster(x: Float, y: Float, peak: Float, vararg mask: String) {
                val col = ((columns - mask.first().length) * x).roundToInt()
                val row = ((rows - mask.size) * y).roundToInt()
                for ((dy, line) in mask.withIndex()) {
                    for ((dx, cell) in line.withIndex()) {
                        if (cell != '#') continue
                        leds +=
                            Led(
                                center =
                                    Offset(left + (col + dx) * pitch, top + (row + dy) * pitch),
                                start = random.nextInt(Steps),
                                duration = 13 + random.nextInt(7),
                                intensity = peak * (0.78f + random.nextFloat() * 0.22f),
                            )
                    }
                }
            }

            if (belowTitle) {
                if (rows >= 4) {
                    cluster(0.32f, 0.10f, 0.033f, "##.", ".##")
                    cluster(0.77f, 0.56f, 0.025f, ".#", "#.")
                }
            } else {
                cluster(0.95f, 0.02f, 0.020f, ".#.#", "#..#", ".##.")
                cluster(0.58f, 0.28f, 0.021f, ".###", "###.", "#...")
                cluster(0.83f, 0.55f, 0.024f, "#", "#")
                cluster(0.04f, 0.98f, 0.040f, "####.", "##.#.", "#...#")
            }

            onDrawBehind {
                val tick = step?.value ?: 0
                for (led in leds) {
                    if ((tick - led.start + Steps) % Steps < led.duration) {
                        drawCircle(ink, radius, led.center, alpha = led.intensity)
                    }
                }
            }
        },
    )
}
