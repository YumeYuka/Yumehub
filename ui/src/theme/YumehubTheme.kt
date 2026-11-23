package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassContentColor
import io.github.neoruaa.glasmio.core.LocalGroupedListIconColor
import io.github.neoruaa.glasmio.icons.GlasmioIconProvider
import moe.yumeyuka.yumehub.data.storage.mmkv.DefaultAccentColorArgb
import moe.yumeyuka.yumehub.locale.ProvideGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIconResolver
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/** Accent seeds offered in 设置 → 强调色 (iOS palette + GitHub green). */
val AccentPalette: List<Color> =
    listOf(
        Color(DefaultAccentColorArgb),
        Color(0xFF0A84FF),
        Color(0xFF30D158),
        Color(0xFF64D2FF),
        Color(0xFFBF5AF2),
        Color(0xFFFF375F),
        Color(0xFFFF9F0A),
        Color(0xFFFFD60A),
        Color(0xFF8E8E93),
    )

internal val LocalGithubTopBarBlurEnabled = compositionLocalOf { false }

@Composable
fun YumehubTheme(
    isDark: Boolean = isSystemInDarkTheme(),
    accent: Color = Color(DefaultAccentColorArgb),
    liquidGlassEnabled: Boolean = false,
    reduceTransparency: Boolean = false,
    topBarBlurEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    ProvideGithubStrings {
        val animatedAccent by
            animateColorAsState(accent, tween(durationMillis = 420), label = "accent")
        val colorScheme =
            remember(isDark, animatedAccent) { iosColorScheme(isDark, animatedAccent) }
        GlasmioTheme(
            isDark = isDark,
            colorScheme = colorScheme,
            appearance =
                GlassAppearance(
                    liquidGlassEnabled = liquidGlassEnabled,
                    reduceTransparency = reduceTransparency,
                ),
        ) {
            val glassColors = GlasmioTheme.colors
            val miuixColors =
                remember(isDark, glassColors) {
                    val base = if (isDark) miuixDarkColorScheme() else miuixLightColorScheme()
                    base.copy(
                        primary = glassColors.accent,
                        background = glassColors.groupedBackground,
                        onBackground = glassColors.content,
                        surface = glassColors.elevatedBackground,
                        onSurface = glassColors.content,
                        onSurfaceVariantSummary = glassColors.secondaryContent,
                    )
                }
            MiuixTheme(colors = miuixColors) {
                CompositionLocalProvider(LocalGithubTopBarBlurEnabled provides topBarBlurEnabled) {
                    GlasmioIconProvider(
                        resolver = GithubIconResolver,
                        defaultTint = {
                            LocalGroupedListIconColor.current
                                ?: LocalGlassContentColor.current
                                ?: LocalGlassColors.current.content
                        },
                        content = content,
                    )
                }
            }
        }
    }
}

fun readableForeground(preferred: Color, background: Color, fallback: Color): Color {
    val opaque = preferred.copy(alpha = 1f)
    val backgroundLuminance = background.luminance()
    fun hasTextContrast(color: Color): Boolean {
        val foregroundLuminance = color.luminance()
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05f) / (darker + 0.05f) >= 4.5f
    }
    if (hasTextContrast(opaque)) return opaque

    // 尽量保留强调色，只混入足以满足正文对比度的前景色。
    var lower = 0f
    var upper = 1f
    repeat(8) {
        val fraction = (lower + upper) / 2
        if (hasTextContrast(lerp(opaque, fallback, fraction))) upper = fraction
        else lower = fraction
    }
    return lerp(opaque, fallback, upper)
}

/**
 * iOS-style neutral surfaces with the accent as the only chromatic color.
 *
 * Glasmio's default [io.github.neoruaa.glasmio.core.glasmioColorScheme] tints every neutral from
 * the accent, which muddies contrast once a non-blue seed is chosen; MeiloX keeps the grays fixed
 * and only lets the accent drive primary/containers, and this mirrors that.
 */
private fun iosColorScheme(
    isDark: Boolean,
    accent: Color,
): ColorScheme {
    val surface = if (isDark) Color.Black else Color.White
    val accentLuminance = accent.compositeOver(surface).luminance()
    val blackContrast = (accentLuminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (accentLuminance + 0.05f)
    val onAccent = if (blackContrast >= whiteContrast) Color.Black else Color.White
    return if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.26f),
            onPrimaryContainer = accent,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1C1C1E),
            onSurfaceVariant = Color(0xB2EBEBF5),
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF2C2C2E),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF1C1C1E),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C),
            outline = Color(0xFF8E8E93),
            outlineVariant = Color(0x2BFFFFFF),
            error = Color(0xFFFF4245),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.16f),
            onPrimaryContainer = accent,
            background = Color(0xFFF2F2F7),
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF2F2F7),
            onSurfaceVariant = Color(0x993C3C43),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF2F2F7),
            surfaceContainer = Color(0xFFF2F2F7),
            surfaceContainerHigh = Color(0xFFE8E8ED),
            surfaceContainerHighest = Color(0xFFDDDDE3),
            outline = Color(0xFF8E8E93),
            outlineVariant = Color(0xFFE6E6E6),
            error = Color(0xFFFF383C),
        )
    }
}
