package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.ui.unit.dp

/** 骨架的统一尺寸与时长；页面只引用这里，不再各自写透明度、圆角或过渡时长。 */
object PlaceholderTokens {
    /** 骨架块相对内容色的透明度，浅色与深色主题共用。 */
    const val ContentAlpha = 0.08f

    /** 行内色块圆角，与正文行高无关。 */
    val BlockCorner = 4.dp

    /** 占位与正文交接、可选内容收起统一使用的时长。 */
    const val TransitionMillis = 220

    /** README 覆盖占位的最小高度，避免首帧过矮导致内容跳一下。 */
    val ReadmeMinHeight = 140.dp

    /** Glasmio 常规列表行高度，占位行与真实行共用。 */
    val ListRowHeight = 52.dp
}
