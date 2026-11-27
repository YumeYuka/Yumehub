package moe.yumeyuka.yumehub.navigation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.extensions.compose.stack.animation.Direction
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimator
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.PredictiveBackAnimatable
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.androidPredictiveBackAnimatableV1
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.scale
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.essenty.backhandler.BackEvent
import com.arkivanov.essenty.backhandler.BackHandler
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationPhase
import moe.yumeyuka.yumehub.core.model.githubWebUri
import moe.yumeyuka.yumehub.ui.component.LocalGithubEditorFocusReady
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubHeaderMotionController
import moe.yumeyuka.yumehub.ui.navigation.GithubNavigationHeaderOverlay
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubCardMotionEnabled
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubHeaderMotion
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubRouteEntryReady

private const val MinPredictiveBackPercent = 1f
private const val MaxPredictiveBackPercent = 100f

// Same Decompose composition as YumeYucca/YumeBox, AppNavContainer.kt (4a6bb76).
private val yumePageAnimator = fade(tween(300)) + slide(tween(400)) + scale(tween(500))

private fun githubStackAnimation(): StackAnimation<GithubRouteEntry, RouteChild> =
    // RouteChild already consumes pointer input on non-top pages, so the outgoing page cannot react.
    // Decompose's full-screen overlay would also block the page that just arrived until every
    // combined animator finishes (the 500 ms scale), which reads as dead taps after the slide.
    stackAnimation(animator = GithubRouteEntryAnimator, disableInputDuringAnimation = false)

private object GithubRouteEntryAnimator : StackAnimator {
    @Composable
    override fun invoke(
        direction: Direction,
        isInitial: Boolean,
        onFinished: () -> Unit,
        content: @Composable (Modifier) -> Unit,
    ) {
        var finished by remember(direction, isInitial) { mutableStateOf(isInitial) }
        val entering = direction == Direction.ENTER_FRONT || direction == Direction.ENTER_BACK
        yumePageAnimator(direction, isInitial, {
            finished = true
            onFinished()
        }) { modifier ->
            CompositionLocalProvider(
                LocalGithubRouteEntryReady provides (direction != Direction.ENTER_FRONT || finished),
                LocalGithubCardMotionEnabled provides (entering && finished),
                LocalGithubEditorFocusReady provides (entering && finished),
            ) {
                content(modifier)
            }
        }
    }
}

@OptIn(ExperimentalDecomposeApi::class)
private fun githubPredictiveBackAnimation(
    backHandler: BackHandler,
    onBack: () -> Unit,
    amplitude: () -> Float,
    headerMotion: GithubHeaderMotionController,
    headerKey: (GithubRouteEntry) -> Any,
): StackAnimation<GithubRouteEntry, RouteChild> =
    predictiveBackAnimation(
        backHandler = backHandler,
        fallbackAnimation = githubStackAnimation(),
        selector = { event, exit, enter ->
            val initial = event.scaled(amplitude())
            val motion = androidPredictiveBackAnimatableV1(initial)
            val from = headerKey(exit.configuration)
            val to = headerKey(enter.configuration)
            headerMotion.beginTracking(from, to)
            headerMotion.gesture(initial.progress)
            object : PredictiveBackAnimatable by motion {
                override suspend fun animate(event: BackEvent) {
                    val next = event.scaled(amplitude())
                    headerMotion.gesture(next.progress)
                    motion.animate(next)
                }

                override suspend fun finish() {
                    headerMotion.phase(GlasmioNavigationPhase.Settling)
                    motion.finish()
                    headerMotion.finish(to)
                }

                override suspend fun cancel() {
                    headerMotion.phase(GlasmioNavigationPhase.Canceling)
                    motion.cancel()
                    headerMotion.finish(from)
                }
            }
        },
        onBack = onBack,
    )

private fun BackEvent.scaled(amplitude: Float): BackEvent =
    if (amplitude == 1f) this else copy(progress = progress * amplitude)

/**
 * 持有唯一的导航栈、主标签和发往它们的全部命令。
 *
 * Activity 配置重建复用组件；进程恢复仅接受当前会话的轻量路由参数。[predictiveBackEnabledAtLaunch] 在启动时
 * 取值，会话中切换会在栈存活期间换掉转场对象，所以该设置下次启动生效。
 */
internal class GithubAppNavigationComponent(
    val componentContext: ComponentContext,
    val predictiveBackEnabledAtLaunch: Boolean,
    predictiveBackPercent: Float,
    restored: RestoredNavigation? = null,
) {
    val state = GithubNavigationState()
    val navigator = GithubNavigator(state)

    private var rootSession = 0

    val routeStack =
        GithubRouteStack(
            navigator = navigator,
            value =
                navigator.routeStack(
                    componentContext = componentContext.childContext("RootNavigation"),
                    initialRoutes = listOf(MainTabsRoute(rootSession)) + restored?.routes.orEmpty(),
                ),
        )

    private var mainTabState by mutableStateOf(restored?.tab ?: GithubTab.Home)
    private var predictiveBackPercentState by
        mutableFloatStateOf(
            predictiveBackPercent.coerceIn(MinPredictiveBackPercent, MaxPredictiveBackPercent),
        )
    private var sessionLogin: String? = null
    private var session: GithubNavigationSession? = null

    val navigationSessionId: String?
        get() = session?.navigationSessionId

    var pendingLink by mutableStateOf<String?>(null)
        private set

    private var activityLinkInitialized = false

    fun initializeLink(url: String?) {
        if (activityLinkInitialized) return
        activityLinkInitialized = true
        url?.let(::receiveLink)
    }

    /** 只保留最新的系统请求，冷启动时等待账号恢复，提交期间等待解锁。 */
    fun receiveLink(url: String) {
        pendingLink = githubWebUri(url)?.toASCIIString() ?: return
    }

    fun consumeLink(url: String): Boolean {
        if (pendingLink != url || state.locked) return false
        pendingLink = null
        return true
    }

    fun openMainTab(tab: GithubTab) {
        if (state.locked) return
        navigator.returnToRoot()
        mainTabState = tab
    }

    val mainTab: GithubTab
        get() = mainTabState

    /** 手势进度实时读取，不因记住转场对象而冻结滑块设置。 */
    val predictiveBackAmplitude: Float
        get() = predictiveBackPercentState / MaxPredictiveBackPercent

    fun updatePredictiveBackPercent(percent: Float) {
        predictiveBackPercentState =
            percent.coerceIn(MinPredictiveBackPercent, MaxPredictiveBackPercent)
    }

    /** 主标签只在根上切换；被详情覆盖或提交中都不响应。 */
    fun selectTab(tab: GithubTab) {
        if (state.locked || !navigator.atRoot) return
        mainTabState = tab
    }

    fun navigateBack() {
        navigator.pop()
    }

    /** 只从 AppRoot 的账号作用域绑定一次，绝不比较每个 tab 的 owner 包装对象。 */
    fun bindSession(session: GithubNavigationSession, login: String) {
        if (this.session === session) return
        val previousSession = this.session
        val accountChanged = sessionLogin != null && sessionLogin != login
        if (previousSession != null) pendingLink = null
        this.session = session
        sessionLogin = login
        // The initial route is already an empty MainTabsRoute; avoid replacing it from the first
        // ViewModel factory.
        if (previousSession != null || accountChanged) resetStacks()
    }

    fun endSession(session: GithubNavigationSession) {
        if (this.session !== session) return
        pendingLink = null
        this.session = null
        sessionLogin = null
        resetStacks()
    }

    fun ensureSession(login: String) {
        if (sessionLogin == login) return
        sessionLogin = login
        resetStacks()
    }

    private fun resetStacks() {
        mainTabState = GithubTab.Home
        state.locked = false
        state.mainListStates.clear()
        rootSession += 1
        navigator.reset(MainTabsRoute(rootSession))
    }
}

/** 账号 owner 真正清理时结束会话；配置重建复用该 VM，不触碰仍在栈里的子组件。 */
internal class GithubNavigationSession(
    private val component: GithubAppNavigationComponent,
    login: String,
    val navigationSessionId: String?,
) : ViewModel() {
    init {
        component.bindSession(this, login)
    }

    override fun onCleared() {
        component.endSession(this)
    }
}

/** 渲染保留组件里的根栈；外壳状态不经过这里，栈变化只重组栈宿主自己。 */
@Composable
internal fun GithubNavContainer(
    component: GithubAppNavigationComponent,
    modifier: Modifier = Modifier,
) {
    val commitBack = remember(component) { component::navigateBack }
    val initialKey = remember(component) { component.routeStack.value.value.active.configuration }
    val headerMotion = remember(component) { GithubHeaderMotionController(initialKey) }
    val animation =
        remember(component, headerMotion) {
            if (component.predictiveBackEnabledAtLaunch) {
                githubPredictiveBackAnimation(
                    backHandler = component.componentContext.backHandler,
                    onBack = commitBack,
                    amplitude = { component.predictiveBackAmplitude },
                    headerMotion = headerMotion,
                    headerKey = { entry ->
                        if (entry.route is MainTabsRoute) component.mainTab else entry
                    },
                )
            } else {
                githubStackAnimation()
            }
        }
    val locked = component.state.locked
    // 提交锁吞掉系统返回：Decompose 的出栈回调在 Activity 创建时注册，排在这里之后才轮到；
    // 页面级处理则随下面的子 dispatcher 一并停用。
    GithubBackHandler(enabled = locked) {}
    // MainActivity 显式提供原生 owner；宿主只建立同一事件树下的子 dispatcher。
    val parentDispatcherOwner =
        checkNotNull(LocalNavigationEventDispatcherOwner.current) {
            "GithubNavContainer 必须由提供 NavigationEventDispatcher 的 Activity 托管"
        }
    val dispatcherOwner = rememberChildDispatcherOwner(parentDispatcherOwner, enabled = !locked)
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides dispatcherOwner,
        LocalGithubHeaderMotion provides headerMotion,
    ) {
        val navBackdrop = if (GlasmioTheme.appearance.usesBackdrop) rememberLayerBackdrop() else null
        Box(modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (navBackdrop == null) Modifier
                        else Modifier.layerBackdrop(navBackdrop, reuseContent = true)
                            .trackBackdropPosition(navBackdrop),
                    )
                    .background(GlasmioTheme.colors.groupedBackground),
            ) {
                GithubRouteStackHost(
                    stack = component.routeStack,
                    animation = animation,
                    headerMotion = headerMotion,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            GithubNavigationHeaderOverlay(motion = headerMotion, backdrop = navBackdrop)
        }
    }
}

/** 子 dispatcher 停用时，其下注册的页面级 BackHandler 整体失效。 */
@Composable
internal fun rememberChildDispatcherOwner(
    parent: NavigationEventDispatcherOwner,
    enabled: Boolean,
): NavigationEventDispatcherOwner {
    val parentDispatcher = parent.navigationEventDispatcher
    val dispatcher =
        remember(parentDispatcher) {
            NavigationEventDispatcher(parent = parentDispatcher).also { it.isEnabled = enabled }
        }
    SideEffect {
        try {
            dispatcher.isEnabled = enabled
        } catch (_: IllegalStateException) {
            // 父 dispatcher 已级联清理，子树正在退出。
        }
    }
    DisposableEffect(dispatcher) {
        onDispose {
            try {
                dispatcher.dispose()
            } catch (_: IllegalStateException) {
                // 兼容父级先行清理的销毁顺序。
            }
        }
    }
    return remember(dispatcher) {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
    }
}
