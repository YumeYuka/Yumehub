package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** 个人页按参考图指定的浅色取色，暗色保留主题对比度。 */
object GithubProfileColors {
    val repositoriesBackground: Color = Color(0xFF393F46)
    val starredBackground: Color = Color(0xFFE9C44F)
    val organizationsBackground: Color = Color(0xFFDF6F24)
    val collectionIcon: Color = Color.White

    val icon: Color
        @Composable
        get() =
            if (GlasmioTheme.colors.isDark) GlasmioTheme.colors.secondaryContent
            else Color(0xFF9194A1)

    val statusBackground: Color
        @Composable
        get() =
            if (GlasmioTheme.colors.isDark) GlasmioTheme.colors.secondaryContent.copy(alpha = 0.08f)
            else Color(0xFFF1F1F2)

    val busyStatusBackground: Color
        @Composable
        get() =
            if (GlasmioTheme.colors.isDark) GlasmioTheme.colors.elevatedBackground
            else Color(0xFFFFFAE5)

    val busyStatusContent: Color
        @Composable
        get() = if (GlasmioTheme.colors.isDark) GlasmioTheme.colors.content else Color(0xFF735C0F)
}
