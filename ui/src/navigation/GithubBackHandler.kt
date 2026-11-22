package moe.yumeyuka.yumehub.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** 在当前导航 entry 的 dispatcher 内处理局部返回；无 owner 时兼容独立预览。 */
@Composable
fun GithubBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    val guardedOnBack = { if (currentEnabled) currentOnBack() }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher

    if (dispatcher == null) {
        BackHandler(enabled = enabled, onBack = guardedOnBack)
    } else {
        // 官方注册仅以 state 为 key，dispatcher 变化时必须一并重建 state。
        key(dispatcher) {
            val state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
            NavigationBackHandler(
                state = state,
                isBackEnabled = enabled,
                onBackCompleted = guardedOnBack,
            )
        }
    }
}
