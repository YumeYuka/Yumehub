package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** 只描述列表正文的身份和切换方向，不重建页面、顶栏或分段控件。 */
data class GithubListTransition(val source: Any, val loading: Boolean, val order: Int = 0)

internal data class GithubListFrame(
    val transition: GithubListTransition,
    val listState: LazyListState,
    val content: LazyListScope.() -> Unit,
)

internal val LocalGithubListFrameActive = staticCompositionLocalOf { true }

@Composable
internal fun GithubAnimatedListContent(
    frame: GithubListFrame,
    content: @Composable (GithubListFrame, Boolean) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val transition = updateTransition(frame, label = "github-list-frame")
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        // 切换分类才交接整段列表；同源占位与正文保留卡片实例，在卡片内部过渡。
        contentKey = { it.transition.source },
        transitionSpec = {
            val delta =
                if (initialState.transition.source == targetState.transition.source) 0
                else targetState.transition.order.compareTo(initialState.transition.order)
            val direction = if (rtl) -delta else delta
            val timing = tween<Float>(300, easing = FastOutSlowInEasing)
            // 启动占位帧换到首帧正文不换分类，直接交接；交叉淡化会让固定入口闪一下。
            if (initialState.transition.loading) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                ((fadeIn(timing) +
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) {
                            direction * it / 8
                        }) togetherWith
                        (fadeOut(timing) +
                            slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) {
                                -direction * it / 8
                            }))
                    .using(null)
            }
        },
    ) { displayed ->
        val active = displayed.transition.source == frame.transition.source
        CompositionLocalProvider(LocalGithubListFrameActive provides active) {
            content(displayed, active)
        }
    }
}
