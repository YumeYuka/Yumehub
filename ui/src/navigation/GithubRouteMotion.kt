package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

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

/** Close entry after drawing, after lazy layouts have subcomposed their visible slots. */
fun Modifier.recordGithubSkeletonEntrance(entrance: GithubSkeletonEntrance): Modifier =
    if (entrance.presented) this
    else
        drawWithContent {
            drawContent()
            if (size.width > 0f && size.height > 0f) entrance.presented = true
        }
