package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** GitHub Linguist 的语义色；没有定义颜色的语言沿用主题次要颜色。 */
@Composable
fun githubLanguageColor(language: String): Color {
    val argb = githubLanguageArgb(language)
    return if (argb != 0) Color(argb) else GlasmioTheme.colors.secondaryContent
}
