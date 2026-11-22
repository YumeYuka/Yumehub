package moe.yumeyuka.yumehub.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute

/** Compose state shared between the shell and the routes. */
@Stable
internal class GithubNavigationState {
    /** Blocks navigation while a page is busy, e.g. while a comment is being submitted. */
    var locked by mutableStateOf(false)

    /** 底栏只观察主页列表，详情列表不能重绑主页收缩动画。 */
    val mainListStates = mutableStateMapOf<GithubTab, LazyListState>()
}

/**
 * 向唯一的 Decompose 子栈发送命令：pop 与 returnToRoot 受提交锁保护，push 由调用方把关。
 *
 * 这里不保留路由历史的 Compose 镜像：子栈是唯一事实来源，读栈都在回调里即时进行。持有 navigator 的外壳和主页 因此不会因为 push / pop 重组。
 */
@Stable
internal class GithubNavigator(private val state: GithubNavigationState) {
    val navigation = StackNavigation<GithubRouteEntry>()

    private var stack: Value<ChildStack<GithubRouteEntry, *>>? = null

    /** 由创建子栈的组件调用一次。 */
    fun bind(stack: Value<ChildStack<GithubRouteEntry, *>>) {
        this.stack = stack
    }

    /** 调用时刻的栈深，不是可订阅状态。 */
    val atRoot: Boolean
        get() = (stack?.value?.items?.size ?: 0) <= 1

    /**
     * 每次前进创建独立栈条目；即使目标参数相同，也保留来源页和已有历史。
     *
     * 不检查提交锁：调用方（路由表）负责判断当前页是否仍在栈顶。锁的释放要靠一次重组，页面在提交 完成的事件回调里发起的 push 可能早于这次重组，在这里拦会把跳转静默丢掉。
     */
    fun push(route: GithubRoute) {
        navigation.push(GithubRouteEntry(route))
    }

    fun pop(): Boolean {
        if (state.locked || atRoot) return false
        navigation.pop()
        return true
    }

    fun returnToRoot() {
        if (state.locked) return
        navigation.navigate { it.take(1) }
    }

    /** 会话结束不受提交锁限制；整条栈换成新的根配置，旧子组件随之销毁。 */
    fun reset(root: GithubRoute) {
        navigation.navigate { listOf(GithubRouteEntry(root)) }
    }
}
