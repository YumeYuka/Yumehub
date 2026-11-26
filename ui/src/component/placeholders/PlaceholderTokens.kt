package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.ui.unit.dp

/** 骨架的统一尺寸与样式。 */
object PlaceholderTokens {
    /** 骨架块相对内容色的透明度，浅色与深色主题共用。 */
    const val ContentAlpha = 0.08f

    /** 行内色块圆角，与正文行高无关。 */
    val BlockCorner = 4.dp

    /** README 覆盖占位的最小高度，避免首帧过矮导致内容跳一下。 */
    val ReadmeMinHeight = 140.dp

    /** Glasmio 常规列表行高度，占位行与真实行共用。 */
    val ListRowHeight = 52.dp
}
