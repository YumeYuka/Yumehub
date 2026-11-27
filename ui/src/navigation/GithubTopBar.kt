package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionGroup
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionStyle
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalGlassSurfaceBrightness
import io.github.neoruaa.glasmio.glass.LocalGlassSurfaceStyle
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/** 官方 Miuix 顶栏与大小标题联动，只适配原有返回和 Action 槽。 */
@Composable
internal fun GithubTopBar(
    title: String,
    backgroundColor: Color,
    scrollBehavior: ScrollBehavior,
    isContentScrolled: Boolean,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    largeTitleLoading: Boolean = false,
    largeTitleLeading: (@Composable () -> Unit)? = null,
    largeTitleSubtitle: String? = null,
    largeTitleMaxLines: Int = 1,
    largeTitleStyle: TextStyle? = null,
    largeTitleSuffix: String? = null,
    largeTitleEyebrow: (@Composable () -> Unit)? = null,
    largeTitleTrailing: (@Composable () -> Unit)? = null,
    largeTitleFooter: (@Composable () -> Unit)? = null,
    largeTitleOnClick: (() -> Unit)? = null,
    contentModifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val sharedHeader =
        LocalGithubHeaderMotion.current != null && LocalGithubHeaderEntryKey.current != null
    CompositionLocalProvider(
        LocalGlassSurfaceBrightness provides 1f,
        LocalGlassSurfaceStyle provides GlassSurfaceStyle.Navigation,
        LocalContentColor provides GlasmioTheme.colors.content,
    ) {
        GithubMiuixGlassTopAppBar(
            title = title,
            largeTitle = largeTitle,
            largeTitleLoading = largeTitleLoading,
            largeTitleLeading = largeTitleLeading,
            largeTitleSubtitle = largeTitleSubtitle,
            largeTitleMaxLines = largeTitleMaxLines,
            largeTitleStyle = largeTitleStyle,
            largeTitleSuffix = largeTitleSuffix,
            largeTitleEyebrow = largeTitleEyebrow,
            largeTitleTrailing = largeTitleTrailing,
            largeTitleFooter = largeTitleFooter,
            largeTitleOnClick = largeTitleOnClick,
            backgroundColor = backgroundColor,
            scrollBehavior = scrollBehavior,
            isContentScrolled = isContentScrolled,
            modifier = modifier,
            contentModifier = contentModifier,
            navigationIcon = {
                if (!sharedHeader) {
                    // 只补偿原按钮的 16dp 边距和 27dp 中心，不移动原生标题。
                    GlasmioToolbarActionGroup(
                        modifier = Modifier.padding(start = 4.dp).offset(y = 1.dp),
                        style = GlasmioToolbarActionStyle.Merged,
                    ) {
                        navigation()
                    }
                }
            },
            actions = {
                if (!sharedHeader) {
                    GlasmioToolbarActionGroup(
                        modifier = Modifier.padding(end = 4.dp).offset(y = 1.dp),
                        style = GlasmioToolbarActionStyle.Merged,
                        content = actions,
                    )
                }
            },
        )
    }
}
