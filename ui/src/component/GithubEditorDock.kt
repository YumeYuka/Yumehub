package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionGroup
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionStyle
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial
import io.github.neoruaa.glasmio.glass.navigationGlassBackground
import io.github.neoruaa.glasmio.glass.rememberNavigationInnerShadows

/** 编辑器底栏与操作按钮统一尺寸，不随键盘状态变化。 */
val GithubEditorDockActionSize = 40.dp

/** 保留合并操作组与菜单同款磨砂材质，底栏单独关闭外侧投影。 */
@Composable
fun GithubEditorDock(
    modifier: Modifier = Modifier,
    height: Dp = GithubEditorDockActionSize,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = ContinuousRoundedRectangle(height / 2)
    val colors = GlasmioTheme.colors
    val appearance = GlasmioTheme.appearance
    val innerShadows = rememberNavigationInnerShadows(appearance.usesBackdrop)
    Box(
        modifier =
            modifier
                .height(height)
                .border(1.dp, colors.subtleStroke, shape)
                .navigationGlassBackground(
                    backdrop = LocalGlassBackdrop.current,
                    shape = { shape },
                    containerColor = colors.container,
                    navigationMaterial = true,
                    innerShadows = innerShadows,
                    isDark = colors.isDark,
                    appearance = appearance,
                    material = NavigationGlassMaterial.RegularMedium,
                    shadowAlpha = 0f,
                ),
    ) {
        // 保留原生材质的边缘高光与磨砂，底栏不使用菜单的外侧投影。
        GlasmioToolbarActionGroup(
            modifier = Modifier.height(height).clip(shape).padding(contentPadding),
            style = GlasmioToolbarActionStyle.Merged,
            content = content,
        )
    }
}
