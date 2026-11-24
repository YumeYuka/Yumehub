package moe.yumeyuka.yumehub

import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.arkivanov.decompose.retainedComponent
import io.github.neoruaa.glasmio.glass.render.configureGlassDynamicRange
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.navigation.GithubAppNavigationComponent
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()
    private val tokens: TokenStore by inject()
    private var loginOpen by mutableStateOf(false)
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            loginOpen = false
            if (result.resultCode != RESULT_OK && tokens.accessToken.value.isBlank()) finish()
        }

    // 保留组件跨配置重建持有返回栈与当前标签；栈不序列化，进程重启回首页。
    private val navigationComponent: GithubAppNavigationComponent by lazy {
        retainedComponent { componentContext ->
            GithubAppNavigationComponent(
                componentContext = componentContext,
                predictiveBackEnabledAtLaunch = settingsStore.predictiveBackEnabled.state.value,
                predictiveBackPercent = settingsStore.predictiveBackMaxProgress.state.value,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        var firstCompositionReady = false
        splash.setKeepOnScreenCondition { !firstCompositionReady }
        loginOpen = savedInstanceState?.getBoolean(LoginOpenKey) == true
        // 在首次组合前配置窗口；Compose 的 inset 会在视图收到分发后更新。
        WindowCompat.enableEdgeToEdge(window)
        val initialHdrOutput =
            settingsStore.glassHdrEnabled.state.value &&
                settingsStore.liquidGlassEnabled.state.value &&
                !settingsStore.reduceTransparency.state.value
        configureGlassDynamicRange(window, hdrEnabled = initialHdrOutput)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // 必须在 setContent 之前创建：Decompose 的出栈回调要先于页面级返回处理注册。
        val navigationComponent = this.navigationComponent
        val link =
            if (savedInstanceState?.getBoolean(LinkHandledKey) == true) {
                savedInstanceState.getString(PendingLinkKey)
            } else {
                intent?.viewLink()
            }
        navigationComponent.initializeLink(link)
        setContent {
            var initialInsetsReady by remember { mutableStateOf(false) }
            // 首次组合时 Compose 的系统栏 inset 可能仍为 0，Scaffold 会按错误的顶栏高度
            // 测量列表，分发后再整体下移。仅等 decorView 分发不足以保证 Compose 已更新；
            // 首屏必须等 Compose 与窗口实际值一致再组合，实际值也可能合法地为 0。
            if (
                initialInsetsReady ||
                    WindowInsets.systemBars.getTop(LocalDensity.current) ==
                        windowManager.currentWindowMetrics.windowInsets
                            .getInsets(AndroidWindowInsets.Type.systemBars()).top
            ) {
                SideEffect {
                    initialInsetsReady = true
                    firstCompositionReady = true
                }
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides this@MainActivity,
                ) {
                    AppRoot(
                        navigationComponent = navigationComponent,
                        loginOpen = loginOpen,
                        openLogin = ::openLogin,
                        updateGlassHdrOutput = { enabled ->
                            if (enabled != initialHdrOutput) recreate()
                        },
                        updateSystemBarAppearance = { isDark ->
                            insetsController.isAppearanceLightStatusBars = !isDark
                            insetsController.isAppearanceLightNavigationBars = !isDark
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.viewLink()?.let(navigationComponent::receiveLink)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(LoginOpenKey, loginOpen)
        outState.putBoolean(LinkHandledKey, true)
        outState.putString(PendingLinkKey, navigationComponent.pendingLink)
        super.onSaveInstanceState(outState)
    }

    private fun openLogin() {
        if (loginOpen || isFinishing) return
        loginOpen = true
        loginLauncher.launch(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.AddingAccountKey, tokens.accountLogin.value.isNotBlank()),
            ActivityOptionsCompat.makeCustomAnimation(
                this,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            ),
        )
    }

    private fun Intent.viewLink(): String? = if (action == Intent.ACTION_VIEW) dataString else null

    private companion object {
        const val LoginOpenKey = "github-login-open"
        const val LinkHandledKey = "github-link-handled"
        const val PendingLinkKey = "github-pending-link"
    }
}
