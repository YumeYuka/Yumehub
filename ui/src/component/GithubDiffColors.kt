package moe.yumeyuka.yumehub.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Primer primitives 11.10.0 的 light/dark diffBlob token，来源见 diff 文档。 */
data class GithubDiffColors(
    val background: Color,
    val foreground: Color,
    val mutedForeground: Color,
    val additionBackground: Color,
    val additionNumberBackground: Color,
    val additionWordBackground: Color,
    val deletionBackground: Color,
    val deletionNumberBackground: Color,
    val deletionWordBackground: Color,
    val additionForeground: Color,
    val deletionForeground: Color,
)

fun githubDiffColors(isDark: Boolean): GithubDiffColors {
    val background = if (isDark) Color(0xFF0D1117) else Color.White
    return if (isDark) {
        GithubDiffColors(
            background = background,
            foreground = Color(0xFFF0F6FC),
            mutedForeground = Color(0xFF9198A1),
            additionBackground = Color(0x262EA043).compositeOver(background),
            additionNumberBackground = Color(0x4D3FB950).compositeOver(background),
            additionWordBackground = Color(0x662EA043),
            deletionBackground = Color(0x1AF85149).compositeOver(background),
            deletionNumberBackground = Color(0x4DF85149).compositeOver(background),
            deletionWordBackground = Color(0x66F85149),
            additionForeground = Color(0xFF3FB950),
            deletionForeground = Color(0xFFF85149),
        )
    } else {
        GithubDiffColors(
            background = background,
            foreground = Color(0xFF1F2328),
            mutedForeground = Color(0xFF59636E),
            additionBackground = Color(0xFFDAFBE1),
            additionNumberBackground = Color(0xFFACEEBB),
            additionWordBackground = Color(0xFFACEEBB),
            deletionBackground = Color(0xFFFFEBE9),
            deletionNumberBackground = Color(0xFFFFCECB),
            deletionWordBackground = Color(0xFFFFCECB),
            additionForeground = Color(0xFF1A7F37),
            deletionForeground = Color(0xFFD1242F),
        )
    }
}
