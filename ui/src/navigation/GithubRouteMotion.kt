package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos

/** New forward entries defer heavy content until the moving page has settled. */
val LocalGithubRouteEntryReady = compositionLocalOf { true }

/** Only settled, active routes play card animations. */
val LocalGithubCardMotionEnabled = compositionLocalOf { true }

/** 页面或列表新一轮内容是否已呈现过一帧。 */
class GithubSkeletonEntrance {
    var presented = false
        internal set
}

/** 首帧组合的骨架参与入场交接；之后组合的行（滚动、追加）直接呈现。 */
val LocalGithubSkeletonEntrance = staticCompositionLocalOf<GithubSkeletonEntrance?> { null }

@Composable
fun rememberGithubSkeletonEntrance(key: Any?): GithubSkeletonEntrance {
    val entrance = remember(key) { GithubSkeletonEntrance() }
    LaunchedEffect(entrance) {
        withFrameNanos {}
        entrance.presented = true
    }
    return entrance
}
