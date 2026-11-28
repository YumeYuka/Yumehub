package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/** 传给编辑器内部的 contentPadding，使首行避开顶栏且仍可随正文滚入模糊区。 */
val LocalEditorContentInsets = compositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/** 固定错误提示使用顶栏下沿，不使用正文为模糊尾部预留的额外距离。 */
val LocalEditorHeaderHeight = compositionLocalOf { 0.dp }

/** 录制不透明背景和原生正文；加载交接时不换录制节点，WebView 和玻璃控件留在层外。 */
val LocalEditorBackdropSource = compositionLocalOf<Modifier> { Modifier }

/** 正文占满视口，顶栏叠在独立采样层之上；底部系统栏和键盘 inset 仅在此消费。 */
@Composable
internal fun EditorViewport(
    title: String,
    navigation: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
    titleContent: (@Composable () -> Unit)?,
) {
    val backdrop = LocalPageBackdrop.current ?: rememberLayerBackdrop()
    val barState =
        rememberTopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = -Float.MAX_VALUE,
        )
    val scrollBehavior = MiuixScrollBehavior(state = barState, canScroll = { false })
    val colors = GlasmioTheme.colors
    val appearance = GlasmioTheme.appearance
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusHeight + 54.dp
    val blurTail = if (appearance.usesBackdrop) 34.dp else 0.dp
    val contentInsets = PaddingValues(top = headerHeight + blurTail)
    val reservedActionWidth = with(LocalDensity.current) { 104.dp.roundToPx() }
    var navigationWidth by remember { mutableIntStateOf(reservedActionWidth) }
    var actionsWidth by remember { mutableIntStateOf(reservedActionWidth) }
    val titleSidePadding =
        with(LocalDensity.current) { maxOf(navigationWidth, actionsWidth).toDp() } + 24.dp

    // 键盘和导航栏在布局阶段统一消费，收起末段也不能低于导航栏的安全边界。
    Box(
        Modifier.fillMaxSize()
            .background(colors.elevatedBackground)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        CompositionLocalProvider(
            LocalEditorContentInsets provides contentInsets,
            LocalEditorHeaderHeight provides headerHeight,
            LocalGlassBackdrop provides backdrop,
            // 背景也必须录入采样层，避免代码页仅录到透明底文字而使玻璃取色异常。
            LocalEditorBackdropSource provides
                Modifier.glassBackdropSource(backdrop)
                    .trackBackdropPosition(backdrop)
                    .background(colors.elevatedBackground),
        ) {
            content()
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            PageTopBar(
                title = if (titleContent == null) title else "",
                backgroundColor = colors.elevatedBackground,
                largeTitle = "",
                scrollBehavior = scrollBehavior,
                isContentScrolled = true,
                contentModifier =
                    Modifier.onSizeChanged { barState.heightOffset = barState.heightOffsetLimit },
                navigation = {
                    val sharedHeader =
                        LocalHeaderMotion.current != null &&
                            LocalHeaderEntryKey.current != null
                    if (!sharedHeader) {
                        Box(
                            Modifier.onSizeChanged {
                                navigationWidth = maxOf(reservedActionWidth, it.width)
                            },
                        ) {
                            navigation()
                        }
                    }
                },
                actions = {
                    val sharedHeader =
                        LocalHeaderMotion.current != null &&
                            LocalHeaderEntryKey.current != null
                    if (!sharedHeader) {
                        Row(
                            Modifier.onSizeChanged {
                                actionsWidth = maxOf(reservedActionWidth, it.width)
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            actions()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (titleContent != null) {
                Box(
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .height(54.dp)
                        .padding(horizontal = titleSidePadding),
                ) {
                    titleContent()
                }
            }
        }
    }
}
