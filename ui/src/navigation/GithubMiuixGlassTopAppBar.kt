// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import moe.yumeyuka.yumehub.ui.theme.LocalGithubTopBarBlurEnabled
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 普通标题直接交给 Miuix；只有自定义标题槽、详情占位或隐藏标题才需要独立前景。 */
@Composable
internal fun GithubMiuixGlassTopAppBar(
    title: String,
    largeTitle: String,
    backgroundColor: Color,
    scrollBehavior: ScrollBehavior,
    isContentScrolled: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    largeTitleLeading: (@Composable () -> Unit)? = null,
    largeTitleSubtitle: String? = null,
    largeTitleMaxLines: Int = 1,
    largeTitleStyle: TextStyle? = null,
    largeTitleSuffix: String? = null,
    largeTitleEyebrow: (@Composable () -> Unit)? = null,
    largeTitleTrailing: (@Composable () -> Unit)? = null,
    largeTitleFooter: (@Composable () -> Unit)? = null,
    largeTitleOnClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val source =
        if (LocalGithubTopBarBlurEnabled.current && GlasmioTheme.appearance.usesBackdrop) {
            LocalGlassBackdrop.current as? LayerBackdrop
        } else {
            null
        }
    val backdrop = remember(source) { source?.let(::MiuixTopBarBackdrop) }
    if (
        largeTitle.isNotEmpty() && largeTitleMaxLines == 1 && largeTitleStyle == null &&
            largeTitleLeading == null && largeTitleSuffix == null && largeTitleEyebrow == null &&
            largeTitleTrailing == null && largeTitleFooter == null && largeTitleOnClick == null &&
            largeTitleSubtitle == null && LocalGithubDetailPresentation.current == null
    ) {
        GlassTopAppBar(
            title = title,
            largeTitle = largeTitle,
            modifier = modifier,
            contentModifier = contentModifier,
            scrollBehavior = scrollBehavior,
            isContentScrolled = isContentScrolled,
            progressiveBlur = backdrop != null,
            backdrop = backdrop,
            bandBrush = GlassTopAppBarDefaults.bandBrush(backgroundColor),
            navigationIcon = navigationIcon,
            actions = { actions() },
        )
        return
    }
    Box(modifier = modifier) {
        // 原生顶栏只绘制背景带；不传折叠状态，避免它重设前景的高度范围。
        GlassTopAppBar(
            title = "",
            expandable = false,
            isContentScrolled = isContentScrolled,
            progressiveBlur = backdrop != null,
            backdrop = backdrop,
            modifier = Modifier.matchParentSize().clearAndSetSemantics {},
            contentModifier = Modifier.fillMaxSize(),
            bandBrush = GlassTopAppBarDefaults.bandBrush(backgroundColor),
            defaultWindowInsetsPadding = false,
        )
        GithubMiuixTopAppBarLayout(
            title = title,
            largeTitle = largeTitle,
            largeTitleBlurRadius = 0.dp,
            titleColor = MiuixTheme.colorScheme.onSurface,
            modifier = contentModifier,
            scrollBehavior = scrollBehavior,
            titlePadding = TopAppBarDefaults.TitlePadding,
            navigationIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
            actionIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
            navigationIcon = navigationIcon,
            largeTitleLeading = largeTitleLeading,
            largeTitleSubtitle = largeTitleSubtitle,
            largeTitleMaxLines = largeTitleMaxLines,
            largeTitleStyle = largeTitleStyle,
            largeTitleSuffix = largeTitleSuffix,
            largeTitleEyebrow = largeTitleEyebrow,
            largeTitleTrailing = largeTitleTrailing,
            largeTitleFooter = largeTitleFooter,
            largeTitleOnClick = largeTitleOnClick,
            actions = actions,
        )
    }
}

/** 复用正文的显示列表，按 Miuix 请求的比例采样，不重复录制正文。 */
@Stable
private class MiuixTopBarBackdrop(private val source: LayerBackdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        scale(1f / downscaleFactor, pivot = Offset.Zero) {
            with(source) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}
