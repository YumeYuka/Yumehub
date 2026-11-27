package moe.yumeyuka.yumehub.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationOperation
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationPhase
import moe.yumeyuka.yumehub.feature.editor.EditorRoute
import moe.yumeyuka.yumehub.ui.navigation.GithubHeaderMotionController
import moe.yumeyuka.yumehub.ui.navigation.GithubPageState
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubHeaderEntryKey
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageBackdrop
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubSkeletonEntrance
import moe.yumeyuka.yumehub.ui.navigation.recordGithubSkeletonEntrance

/** 以条目引用区分每次入栈；路由参数相同的页面也拥有独立子组件和页面状态。 */
internal class GithubRouteEntry(val route: GithubRoute)

/** 一条真实的 Decompose 子栈，以及向它发命令的 navigator。 */
@Immutable
internal class GithubRouteStack(
    val navigator: GithubNavigator,
    val value: Value<ChildStack<GithubRouteEntry, RouteChild>>,
)

internal val LocalGithubRouteChild =
    staticCompositionLocalOf<RouteChild> { error("LocalGithubRouteChild 未提供") }

/**
 * 栈内一个配置对应的组件。
 *
 * 被覆盖的页面会离开组合，回来时重新组合；页面 ViewModel 与滚动状态留在这里，随配置真实出栈才释放。
 */
@Stable
internal class RouteChild(
    val route: GithubRoute,
    private val navigator: GithubNavigator,
    componentContext: ComponentContext,
) : ComponentContext by componentContext, ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val pageState = GithubPageState()

    // 出栈先销毁 Decompose 子组件，退场页面在最后一帧后才离开组合。
    private var destroyed = false
    private var mounted = 0

    /**
     * 是否为栈顶配置。只由 [routeStack] 的栈观察者写入，页面按需订阅自己的归属，不必订阅整条栈。
     *
     * 不取 Essenty 的 RESUMED：保留组件的生命周期跟随 Activity 前后台，后台完成的操作会因此被判成「不在栈顶」而无法导航。
     */
    var isTop by mutableStateOf(false)

    init {
        lifecycle.doOnDestroy {
            isTop = false
            destroyed = true
            if (route is EditorRoute) route.session.dispose()
            if (mounted == 0) viewModelStore.clear()
        }
    }

    @Composable
    fun Content() {
        DisposableEffect(this) {
            mounted++
            onDispose {
                mounted--
                if (destroyed && mounted == 0) viewModelStore.clear()
            }
        }
        val top = isTop
        // 非栈顶（被覆盖、退场中、预测返回露出的下层）降低生命周期并停用页面级返回处理。
        val lifecycleOwner =
            rememberLifecycleOwner(
                maxLifecycle = if (top) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
            )
        val parentDispatcherOwner =
            checkNotNull(LocalNavigationEventDispatcherOwner.current) {
                "RouteChild 必须由提供 NavigationEventDispatcher 的宿主托管"
            }
        val dispatcherOwner = rememberChildDispatcherOwner(parentDispatcherOwner, enabled = top)
        val entrance = pageState.skeletonEntrance
        val entryReady = LocalGithubRouteEntryReady.current
        val entryKey: Any? = if (route is MainTabsRoute) null else LocalGithubHeaderEntryKey.current
        CompositionLocalProvider(
            LocalLifecycleOwner provides lifecycleOwner,
            LocalNavigationEventDispatcherOwner provides dispatcherOwner,
            LocalViewModelStoreOwner provides this,
            LocalGithubRouteChild provides this,
            LocalGithubCardMotionEnabled provides (top && LocalGithubCardMotionEnabled.current),
            LocalGithubPageState provides pageState,
            LocalGithubSkeletonEntrance provides entrance,
            LocalGithubHeaderEntryKey provides entryKey,
        ) {
            val input =
                if (top) Modifier
                else
                    Modifier.clearAndSetSemantics {}
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
            Box(
                Modifier.fillMaxSize()
                    .clipToBounds()
                    .background(GlasmioTheme.colors.groupedBackground)
                    .focusProperties { onEnter = { if (!top) cancelFocusChange() } }
                    .focusGroup()
                    .then(
                        if (top && entryReady) Modifier.recordGithubSkeletonEntrance(entrance)
                        else Modifier,
                    )
                    .then(input),
            ) {
                if (route is MainTabsRoute) GithubMainScreen(navigator)
                else {
                    val backdrop = rememberLayerBackdrop()
                    CompositionLocalProvider(
                        LocalGithubPageBackdrop provides backdrop,
                        LocalBlurBackdrop provides backdrop,
                    ) {
                        GithubRouteContent(route, navigator)
                    }
                }
            }
        }
    }
}

/** 在已有组件上下文上创建子栈；路由不跨进程恢复，无须序列化。 */
internal fun GithubNavigator.routeStack(
    componentContext: ComponentContext,
    initialRoutes: List<GithubRoute>,
): Value<ChildStack<GithubRouteEntry, RouteChild>> {
    val stack =
        componentContext.childStack(
            source = navigation,
            serializer = null,
            initialStack = { initialRoutes.map(::GithubRouteEntry) },
            handleBackButton = true,
        ) { entry, childContext ->
            RouteChild(route = entry.route, navigator = this, componentContext = childContext)
        }
    bind(stack)
    // 先于任何 Compose 订阅注册：栈一变，归属标记与栈值在同一次快照里更新。订阅与保留组件同寿命，无须取消。
    stack.subscribe { current ->
        current.backStack.forEach { it.instance.isTop = false }
        current.active.instance.isTop = true
    }
    return stack
}

/** 渲染当前的 Decompose 子栈，这里不另存第二份路由历史。 */
@Composable
internal fun GithubRouteStackHost(
    stack: GithubRouteStack,
    animation: StackAnimation<GithubRouteEntry, RouteChild>,
    headerMotion: GithubHeaderMotionController? = null,
    modifier: Modifier = Modifier,
) {
    val childStack by stack.value.subscribeAsState()
    val activeConfig = childStack.active.configuration
    val activeKey: Any =
        if (activeConfig.route is MainTabsRoute) {
            LocalGithubShellArgs.current.component.mainTab
        } else {
            activeConfig
        }
    if (headerMotion != null) {
        val depthRef = remember { intArrayOf(childStack.items.size) }
        val prevKeyRef = remember { arrayOf<Any>(activeKey) }
        LaunchedEffect(activeKey, childStack.items.size) {
            val prevKey = prevKeyRef[0]
            val prevDepth = depthRef[0]
            val nextDepth = childStack.items.size
            if (prevKey != activeKey) {
                val operation =
                    when {
                        prevKey is GithubTab && activeKey is GithubTab ->
                            GlasmioNavigationOperation.Tab
                        nextDepth < prevDepth -> GlasmioNavigationOperation.Pop
                        else -> GlasmioNavigationOperation.Push
                    }
                if (headerMotion.frame.value.phase == GlasmioNavigationPhase.Idle) {
                    headerMotion.beginNavigation(prevKey, activeKey, operation)
                }
                headerMotion.finish(activeKey)
            }
            prevKeyRef[0] = activeKey
            depthRef[0] = nextDepth
        }
    }
    // 内容 lambda 不能捕获任何外壳状态：预测返回以它为 key 记住 movableContent，实例一变整棵页面树就会重建。
    // 页面需要的环境都经 CompositionLocal 读取。
    Children(stack = childStack, modifier = modifier.fillMaxSize(), animation = animation) { child
        ->
        val childKey: Any? =
            if (child.configuration.route is MainTabsRoute) null else child.configuration
        CompositionLocalProvider(LocalGithubHeaderEntryKey provides childKey) {
            child.instance.Content()
        }
    }
}
