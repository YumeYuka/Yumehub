package moe.yumeyuka.yumehub.ui.component

import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop

/** 屏幕与玻璃采样复用同一份真实正文录制，避免额外绘制一次 WebView。 */
fun Modifier.githubBackdropSource(backdrop: LayerBackdrop): Modifier =
    layerBackdrop(backdrop, reuseContent = true)
