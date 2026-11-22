package moe.yumeyuka.yumehub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityOptionsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.arkivanov.decompose.retainedComponent
import io.github.neoruaa.glasmio.glass.render.configureGlassDynamicRange
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.navigation.GithubAppNavigationComponent
import moe.yumeyuka.yumehub.ui.theme.githubTypography
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
                predictiveBackEnabledAtLaunch = settingsStore.predictiveBackEnabled.value,
                predictiveBackPercent = settingsStore.predictiveBackMaxProgress.value,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val fonts = githubTypography(applicationContext)
        var firstCompositionReady = false
        splash.setKeepOnScreenCondition { !firstCompositionReady }
        loginOpen = savedInstanceState?.getBoolean(LoginOpenKey) == true
        // 在 Compose 首次布局前完成窗口配置，避免首帧使用未分发的系统栏 inset。
        WindowCompat.enableEdgeToEdge(window)
        val initialHdrOutput =
            settingsStore.glassHdrEnabled.value &&
                settingsStore.liquidGlassEnabled.value &&
                !settingsStore.reduceTransparency.value
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
            if (fonts.collectAsState().value == null) return@setContent
            SideEffect { firstCompositionReady = true }
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
