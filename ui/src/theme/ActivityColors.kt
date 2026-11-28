package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.GlasmioTheme

/**
 * 活动角标使用 Primer 的 bgColor.*.emphasis；Star 保留原有黄色，不跟随应用强调色。
 * 来源：https://github.com/primer/primitives/blob/main/src/tokens/functional/color/bgColor.json5
 */
object ActivityColors {
    val accent: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF1F6FEB) else Color(0xFF0969DA)

    val success: Color
        @Composable get() = WorkColors.open

    val done: Color
        @Composable get() = WorkColors.merged

    val neutral: Color
        @Composable get() = WorkColors.draft

    val starred: Color = AccentPalette[7]

    val severe: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFFBD561D) else Color(0xFFBC4C00)

    val onBadge: Color = Color.White
}
