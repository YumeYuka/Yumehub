package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.GlasmioTheme

/**
 * GitHub Primer 的状态与分支专用配色，不随用户强调色变化。 来源：primer/primitives 的
 * src/tokens/functional/color/{bgColor,fgColor,borderColor}.json5 与
 * src/tokens/base/color/{light/light,dark/dark}.json5。
 */
object GithubWorkColors {
    val open: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF238636) else Color(0xFF1F883D)

    val closed: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFFDA3633) else Color(0xFFCF222E)

    val merged: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF8957E5) else Color(0xFF8250DF)

    val draft: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF656C76) else Color(0xFF59636E)

    /** fgColor.muted（neutral.9）：工作项头部标签、项目、任务等元数据 chip 的图标色。 */
    val muted: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF9198A1) else Color(0xFF59636E)

    // GitHub Releases 的 Latest 和 Pre-release 标签分别使用 success 与 attention 前景色。
    val releaseLatest: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF3FB950) else Color(0xFF1A7F37)

    val releasePrerelease: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFFD29922) else Color(0xFF9A6700)

    val onStatus: Color = Color.White

    val branchBackground: Color
        @Composable
        get() =
            if (GlasmioTheme.colors.isDark) Color(0xFF388BFD).copy(alpha = 0.1f)
            else Color(0xFFDDF4FF)

    val branchForeground: Color
        @Composable get() = if (GlasmioTheme.colors.isDark) Color(0xFF4493F8) else Color(0xFF0969DA)

    val branchBorder: Color
        @Composable
        get() =
            (if (GlasmioTheme.colors.isDark) Color(0xFF388BFD) else Color(0xFF54AEFF)).copy(
                alpha = 0.4f,
            )
}
