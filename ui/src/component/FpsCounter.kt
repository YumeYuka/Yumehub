package moe.yumeyuka.yumehub.ui.component

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.neoruaa.glasmio.core.GlasmioTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.Text

/** 统计窗口实际渲染帧率，不额外请求动画帧。 */
@Composable
fun FpsCounter(modifier: Modifier = Modifier) {
    val window = LocalActivity.current?.window ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fps by remember(window) { mutableIntStateOf(0) }
    var lowFps by remember(window) { mutableIntStateOf(-1) }

    DisposableEffect(window, lifecycle) {
        val mainHandler = Handler(Looper.getMainLooper())
        var stopMonitoring: (() -> Unit)? = null

        fun startMonitoring() {
            if (stopMonitoring != null) return
            fps = 0
            lowFps = -1
            val active = AtomicBoolean(true)
            val callbackThread = HandlerThread("Github FPS").apply { start() }
            val callbackHandler = Handler(callbackThread.looper)
            val display = window.decorView.display
            fun refreshPeriod(): Long {
                val rate = display?.refreshRate?.takeIf { it.isFinite() && it > 0f } ?: 60f
                return (1_000_000_000.0 / rate).roundToLong()
            }
            var framePeriod = refreshPeriod()
            val intervals = LongArray(2_400)
            val timestamps = LongArray(intervals.size)
            var firstSample = 0
            var sampleCount = 0
            var frames = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
                if (active.get()) {
                    // 丢弃的报告同样代表已渲染帧，不是掉帧。
                    frames += 1 + droppedReports
                    val duration =
                        activeFrameDurationNanos(
                            totalDuration = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                            deadline = metrics.getMetric(FrameMetrics.DEADLINE),
                            refreshPeriod = framePeriod,
                            firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                        )
                    if (duration != null) {
                        val index = (firstSample + sampleCount) % intervals.size
                        intervals[index] = duration
                        timestamps[index] = System.nanoTime()
                        if (sampleCount < intervals.size) {
                            sampleCount++
                        } else {
                            firstSample = (firstSample + 1) % intervals.size
                        }
                    }
                }
            }
            var sampleStarted = System.nanoTime()
            val update =
                object : Runnable {
                    override fun run() {
                        if (!active.get()) return
                        val now = System.nanoTime()
                        val elapsed = now - sampleStarted
                        val measuredFps = (frames * 1_000_000_000.0 / elapsed).roundToInt()
                        frames = 0
                        sampleStarted = now
                        val nextPeriod = refreshPeriod()
                        if (nextPeriod != framePeriod) {
                            // 刷新率变化后重新开始可比较的帧窗口。
                            framePeriod = nextPeriod
                            firstSample = 0
                            sampleCount = 0
                        }
                        val cutoff = now - 10_000_000_000L
                        while (sampleCount > 0 && timestamps[firstSample] < cutoff) {
                            firstSample = (firstSample + 1) % intervals.size
                            sampleCount--
                        }
                        val measuredLow =
                            onePercentLowFps(
                                LongArray(sampleCount) {
                                    intervals[(firstSample + it) % intervals.size]
                                },
                            )
                        mainHandler.post {
                            if (active.get()) {
                                fps = measuredFps
                                lowFps = measuredLow
                            }
                        }
                        callbackHandler.postDelayed(this, 1_000L)
                    }
                }
            window.addOnFrameMetricsAvailableListener(listener, callbackHandler)
            callbackHandler.postDelayed(update, 1_000L)
            stopMonitoring = {
                active.set(false)
                callbackHandler.removeCallbacks(update)
                mainHandler.removeCallbacksAndMessages(null)
                window.removeOnFrameMetricsAvailableListener(listener)
                callbackThread.quitSafely()
            }
        }

        fun stop() {
            stopMonitoring?.invoke()
            stopMonitoring = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startMonitoring()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startMonitoring()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }

    Text(
        text = "FPS $fps · 1% Low ${if (lowFps >= 0) lowFps.toString() else "—"}",
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        style = GlasmioTheme.textStyles.caption1.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

/** 把管线预算归一到一个刷新周期，并保留全部超时。 */
private fun activeFrameDurationNanos(
    totalDuration: Long,
    deadline: Long,
    refreshPeriod: Long,
    firstDraw: Boolean,
): Long? {
    if (firstDraw || totalDuration <= 0L || deadline <= 0L || refreshPeriod <= 0L) return null
    // Vsync 间隔包含空闲时间；TOTAL_DURATION 覆盖 UI 延迟与 GPU 完成，DEADLINE 是系统预算。
    return refreshPeriod + (totalDuration - deadline).coerceAtLeast(0L)
}

/** 最慢 1% 帧的倒数均值，至少需要 100 个真实样本。 */
private fun onePercentLowFps(durations: LongArray): Int {
    if (durations.size < 100) return -1
    durations.sort()
    val slowCount = (durations.size + 99) / 100
    var slowTotal = 0.0
    for (index in durations.size - slowCount until durations.size) slowTotal += durations[index]
    return (1_000_000_000.0 * slowCount / slowTotal).roundToInt()
}
