package moe.yumeyuka.yumehub.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme

enum class EmptyScene { Inbox, Search, Done, Offline }

@Composable
fun EmptyState(
    scene: EmptyScene,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    verticalBias: Float = -0.1f,
) {
    val colors = GlasmioTheme.colors
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = BiasAlignment(0f, verticalBias),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EmptyStateIllustration(scene)
            Text(
                text = title,
                style = GlasmioTheme.textStyles.subheadline,
                color = colors.content,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = GlasmioTheme.textStyles.footnote,
                    color = colors.secondaryContent,
                )
            }
        }
    }
}

// Projected paths from Hairline UI's registry/empty-state-scenes.ts (MIT).
// Copyright (c) 2026 Hairline UI. https://hairlineui.com/license#components
private class Face(data: String, val fill: String, val stroke: String, val dashed: Boolean = false,
    ring: Boolean = false, clipData: String? = null) {
    val path = PathParser().parsePathString(data).toPath().apply {
        if (ring) fillType = PathFillType.EvenOdd
    }
    val clip = clipData?.let { PathParser().parsePathString(it).toPath() }
    val outline = Stroke(width = if (stroke == "accent" && fill == "none") 1.5f else 1f,
        cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2f, 2.5f)) else null)
}

private val TileShapes = listOf(
    Face("M-72.75 48.3 L0 90.3 L0 84 L-72.75 42 Z", "left", "line"),
    Face("M72.75 48.3 L0 90.3 L0 84 L72.75 42 Z", "right", "line"),
    Face("M0 0 L72.75 42 L0 84 L-72.75 42 Z", "top", "line"),
    Face("M24.25 14 L-48.5 56", "none", "faint"),
    Face("M-24.25 14 L48.5 56", "none", "faint"),
    Face("M48.5 28 L-24.25 70", "none", "faint"),
    Face("M-48.5 28 L24.25 70", "none", "faint"),
)

private val InboxShapes = listOf(
    Face("M-41.22 42 L0 65.8 L0 54.6 L-41.22 30.8 Z", "left", "line"),
    Face("M41.22 42 L0 65.8 L0 54.6 L41.22 30.8 Z", "right", "line"),
    Face("M0 7 L41.22 30.8 L0 54.6 L-41.22 30.8 Z M0 11.2 L33.95 30.8 L0 50.4 L-33.95 30.8 Z", "top", "line", ring = true),
    Face("M0 18.9 L33.95 38.5 L0 58.1 L-33.95 38.5 Z", "hole", "faint", clipData = "M0 11.2 L33.95 30.8 L0 50.4 L-33.95 30.8 Z"),
    Face("M0 11.2 L-33.95 30.8 L-33.95 38.5 L0 18.9 Z", "right", "line", clipData = "M0 11.2 L33.95 30.8 L0 50.4 L-33.95 30.8 Z"),
    Face("M0 11.2 L33.95 30.8 L33.95 38.5 L0 18.9 Z", "left", "line", clipData = "M0 11.2 L33.95 30.8 L0 50.4 L-33.95 30.8 Z"),
    Face("M-29.7 16.45 L-5.46 30.45 L-5.46 29.61 L-29.7 15.61 Z", "left", "line"),
    Face("M24.85 12.95 L-5.46 30.45 L-5.46 29.61 L24.85 12.11 Z", "right", "line"),
    Face("M0.61 -1.89 L24.85 12.11 L-5.46 29.61 L-29.7 15.61 Z", "top", "line"),
    Face("M-1.21 4.06 L14.55 13.16", "none", "accent"),
    Face("M-7.27 7.56 L8.49 16.66", "none", "accent"),
    Face("M-13.34 11.06 L-4.24 16.31", "none", "accent"),
)

private val SearchShapes = listOf(
    Face("M-12.73 19.95 L38.19 49.35 L38.19 47.95 L-12.73 18.55 Z", "none", "line", dashed = true),
    Face("M50.92 42 L38.19 49.35 L38.19 47.95 L50.92 40.6 Z", "none", "line", dashed = true),
    Face("M0 11.2 L50.92 40.6 L38.19 47.95 L-12.73 18.55 Z", "none", "line", dashed = true),
    Face("M-30.31 30.1 L20.61 59.5 L20.61 58.1 L-30.31 28.7 Z", "none", "line", dashed = true),
    Face("M33.34 52.15 L20.61 59.5 L20.61 58.1 L33.34 50.75 Z", "none", "line", dashed = true),
    Face("M-17.58 21.35 L33.34 50.75 L20.61 58.1 L-30.31 28.7 Z", "none", "line", dashed = true),
    Face("M-47.89 40.25 L-7.88 63.35 L-7.88 61.95 L-47.89 38.85 Z", "none", "line", dashed = true),
    Face("M4.85 56 L-7.88 63.35 L-7.88 61.95 L4.85 54.6 Z", "none", "line", dashed = true),
    Face("M-35.16 31.5 L4.85 54.6 L-7.88 61.95 L-47.89 38.85 Z", "none", "line", dashed = true),
    Face("M18.34 29.03 L30.3 42.7 L25.84 45.3 L13.87 31.64 Z", "right", "line"),
    Face("M21.47 16.1 L21.32 14.2 L20.86 12.33 L20.11 10.53 L19.08 8.82 L17.78 7.24 L16.25 5.8 L14.49 4.55 L12.55 3.49 L10.46 2.65 L8.25 2.04 L5.96 1.66 L3.64 1.54 L1.31 1.66 L-0.98 2.04 L-3.19 2.65 L-5.28 3.49 L-7.22 4.55 L-8.97 5.8 L-10.51 7.24 L-11.81 8.82 L-12.84 10.53 L-13.59 12.33 L-14.04 14.2 L-14.19 16.1 L-14.04 18 L-13.59 19.87 L-12.84 21.67 L-11.81 23.38 L-10.51 24.96 L-8.97 26.4 L-7.22 27.65 L-5.28 28.71 L-3.19 29.55 L-0.98 30.16 L1.31 30.54 L3.64 30.66 L5.96 30.54 L8.25 30.16 L10.46 29.55 L12.55 28.71 L14.49 27.65 L16.25 26.4 L17.78 24.96 L19.08 23.38 L20.11 21.67 L20.86 19.87 L21.32 18 Z", "glass", "faint"),
    Face("M25.93 16.1 L25.74 13.72 L25.17 11.39 L24.23 9.14 L22.94 7 L21.32 5.02 L19.4 3.23 L17.21 1.66 L14.78 0.34 L12.17 -0.71 L9.41 -1.48 L6.55 -1.94 L3.64 -2.1 L0.73 -1.94 L-2.13 -1.48 L-4.89 -0.71 L-7.51 0.34 L-9.93 1.66 L-12.12 3.23 L-14.05 5.02 L-15.67 7 L-16.96 9.14 L-17.89 11.39 L-18.46 13.72 L-18.65 16.1 L-18.46 18.48 L-17.89 20.81 L-16.96 23.06 L-15.67 25.2 L-14.05 27.18 L-12.12 28.97 L-9.93 30.54 L-7.51 31.86 L-4.89 32.91 L-2.13 33.68 L0.73 34.14 L3.64 34.3 L6.55 34.14 L9.41 33.68 L12.17 32.91 L14.78 31.86 L17.21 30.54 L19.4 28.97 L21.32 27.18 L22.94 25.2 L24.23 23.06 L25.17 20.81 L25.74 18.48 Z M21.47 16.1 L21.32 14.2 L20.86 12.33 L20.11 10.53 L19.08 8.82 L17.78 7.24 L16.25 5.8 L14.49 4.55 L12.55 3.49 L10.46 2.65 L8.25 2.04 L5.96 1.66 L3.64 1.54 L1.31 1.66 L-0.98 2.04 L-3.19 2.65 L-5.28 3.49 L-7.22 4.55 L-8.97 5.8 L-10.51 7.24 L-11.81 8.82 L-12.84 10.53 L-13.59 12.33 L-14.04 14.2 L-14.19 16.1 L-14.04 18 L-13.59 19.87 L-12.84 21.67 L-11.81 23.38 L-10.51 24.96 L-8.97 26.4 L-7.22 27.65 L-5.28 28.71 L-3.19 29.55 L-0.98 30.16 L1.31 30.54 L3.64 30.66 L5.96 30.54 L8.25 30.16 L10.46 29.55 L12.55 28.71 L14.49 27.65 L16.25 26.4 L17.78 24.96 L19.08 23.38 L20.11 21.67 L20.86 19.87 L21.32 18 Z", "left", "accent", ring = true),
)

private val drawEase = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

private val DoneShapes = listOf(
    Face("M-41.22 42 L0 65.8 L0 58.1 L-41.22 34.3 Z", "left", "line"),
    Face("M41.22 42 L0 65.8 L0 58.1 L41.22 34.3 Z", "right", "line"),
    Face("M0 10.5 L41.22 34.3 L0 58.1 L-41.22 34.3 Z", "top", "line"),
    Face("M-19.91 9.96 L-7.63 20.54 L26 -6.91 L32.3 -1.77 L-7.8 30.98 L-26.38 14.96 Z", "accent-side", "accent"),
    Face("M-19.91 9.96 L-7.63 20.54 L-7.63 25.02 L-19.91 14.44 Z", "accent-side", "accent"),
    Face("M-7.63 20.54 L26 -6.91 L26 -2.43 L-7.63 25.02 Z", "accent-side", "accent"),
    Face("M26 -6.91 L32.3 -1.77 L32.3 2.71 L26 -2.43 Z", "accent-side", "accent"),
    Face("M-26.38 14.96 L-19.91 9.96 L-19.91 14.44 L-26.38 19.44 Z", "accent-side", "accent"),
    Face("M-19.91 14.44 L-7.63 25.02 L26 -2.43 L32.3 2.71 L-7.8 35.46 L-26.38 19.44 Z", "accent", "accent"),
)

private val OfflineShapes = listOf(
    Face("M10.91 37.1 L38.8 53.2 L38.8 25.2 L10.91 9.1 Z", "left", "line"),
    Face("M56.98 42.7 L38.8 53.2 L38.8 25.2 L56.98 14.7 Z", "right", "line"),
    Face("M29.1 -1.4 L56.98 14.7 L38.8 25.2 L10.91 9.1 Z", "top", "line"),
    Face("M18.79 30.45 L21.22 31.85 L21.22 26.25 L18.79 24.85 Z", "hole", "line"),
    Face("M28.49 36.05 L30.92 37.45 L30.92 31.85 L28.49 30.45 Z", "hole", "line"),
    Face("M-0.61 40.25 L11.52 33.25", "none", "accent"),
    Face("M9.09 45.85 L21.22 38.85", "none", "accent"),
    Face("M-19.4 60.2 L-1.21 70.7 L-1.21 48.3 L-19.4 37.8 Z", "left", "line"),
    Face("M13.34 62.3 L-1.21 70.7 L-1.21 48.3 L13.34 39.9 Z", "right", "line"),
    Face("M-4.85 29.4 L13.34 39.9 L-1.21 48.3 L-19.4 37.8 Z", "top", "line"),
    Face("M-10.31 57.75 L-15.16 67.83 L-30.31 60.48 L-38.19 49.63 L-55.77 42.98", "none", "line"),
)

@Composable
private fun EmptyStateIllustration(kind: EmptyScene) {
    val scene = when (kind) {
        EmptyScene.Inbox -> InboxShapes
        EmptyScene.Search -> SearchShapes
        EmptyScene.Done -> DoneShapes
        EmptyScene.Offline -> OfflineShapes
    }
    val tileClock = remember { Animatable(0f) }
    val sceneClock = remember(scene) { Animatable(0f) }
    LaunchedEffect(Unit) { tileClock.animateTo(844f, tween(844, easing = LinearEasing)) }
    LaunchedEffect(scene) {
        val delay = if (tileClock.value == 844f) 160 else 260
        val duration = 640 + (scene.lastIndex * 34).coerceAtMost(560)
        sceneClock.animateTo(duration.toFloat(), tween(duration, delayMillis = delay, easing = LinearEasing))
    }
    val colors = GlasmioTheme.colors
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    Canvas(Modifier.size(168.dp, 140.dp)) {
        scale(size.width / 156f, size.height / 130f, Offset.Zero) {
            translate(78f, 36f) {
                fun render(faces: List<Face>, clock: Float) {
                    faces.forEachIndexed { index, face ->
                        val elapsed = clock - (index * 34).coerceAtMost(560)
                        if (elapsed <= 0f) return@forEachIndexed
                        val t = drawEase.transform((elapsed / 640f).coerceAtMost(1f))
                        val accent = colors.accent
                        val bg = colors.groupedBackground
                        val fg = colors.content
                        val line = when (face.stroke) {
                            "accent" -> accent
                            "faint" -> lerp(bg, fg, 0.20f)
                            else -> lerp(bg, fg, 0.46f)
                        }
                        fun paint() {
                            if (face.fill != "none" && t > 0.62f) {
                                val fill = when (face.fill) {
                                    "glass" -> lerp(bg, accent, 0.07f)
                                    "accent" -> lerp(bg, accent, 0.16f)
                                    "accent-side" -> lerp(bg, accent, 0.32f)
                                    "hole" -> lerp(bg, fg, 0.16f)
                                    "right" -> lerp(bg, fg, 0.12f)
                                    "left" -> lerp(bg, fg, 0.07f)
                                    else -> lerp(bg, fg, 0.03f)
                                }
                                drawPath(face.path, fill, alpha = ((t - 0.62f) / 0.38f).coerceIn(0f, 1f))
                            }
                            if (face.dashed || t >= 0.62f) {
                                drawPath(face.path, line, alpha = if (face.dashed) (elapsed / 420f).coerceAtMost(1f) else 1f, style = face.outline)
                            } else {
                                measure.setPath(face.path, false)
                                segment.reset()
                                measure.getSegment(0f, measure.length * t / 0.62f, segment)
                                drawPath(segment, line, style = face.outline)
                            }
                        }
                        if (face.clip == null) paint() else clipPath(face.clip) { paint() }
                    }
                }
                render(TileShapes, tileClock.value)
                render(scene, sceneClock.value)
            }
        }
    }
}
