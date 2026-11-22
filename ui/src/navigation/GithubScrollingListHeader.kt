package moe.yumeyuka.yumehub.ui.navigation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val GithubScrollingHeaderKey = "github-scrolling-content-header"

/** 控件只组合一份；列表首项仅占据其真实高度，控件按首项位置滚动，不参与正文转场。 */
@Composable
internal fun GithubScrollingListHeader(
    listState: LazyListState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)?,
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    if (header == null) {
        Box(modifier) { content(0.dp) }
        return
    }
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val headerPlaceable =
            subcompose("header") {
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = horizontalPadding)
                            .scrollable(
                                state = listState,
                                orientation = Orientation.Vertical,
                                reverseDirection = true,
                            ),
                    ) {
                        header()
                    }
                }
                .single()
                .measure(constraints.copy(minHeight = 0))
        val body =
            subcompose("body") { content(headerPlaceable.height.toDp()) }
                .single()
                .measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            // 布局信息在 LazyColumn 测量后读取，顶部 inset 与滚动偏移在同一帧生效。
            val layout = listState.layoutInfo
            val slot = layout.visibleItemsInfo.firstOrNull { it.key == GithubScrollingHeaderKey }
            if (slot != null) {
                headerPlaceable.placeRelative(0, slot.offset - layout.viewportStartOffset)
            }
        }
    }
}
