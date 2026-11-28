package moe.yumeyuka.yumehub

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.arkivanov.decompose.retainedComponent
import io.github.neoruaa.glasmio.glass.render.configureGlassDynamicRange
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.navigation.GithubAppNavigationComponent
import moe.yumeyuka.yumehub.navigation.restoreNavigation
import moe.yumeyuka.yumehub.navigation.saveNavigation
import moe.yumeyuka.yumehub.ui.platform.AppLanguageManager
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()
    private val tokens: TokenStore by inject()
    private var loginOpen by mutableStateOf(false)
    private var startupLink: String? = null
    private var savedNavigation: Bundle? = null
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            loginOpen = false
            if (result.resultCode != RESULT_OK) {
                lifecycleScope.launch {
                    (application as App).awaitStorage()
                    if (tokens.accessToken.value.isBlank()) finish()
                }
            }
        }

    private lateinit var navigationComponent: GithubAppNavigationComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        var firstCompositionReady = false
        splash.setKeepOnScreenCondition { !firstCompositionReady }
        loginOpen = savedInstanceState?.getBoolean(LoginOpenKey) == true
        savedNavigation = savedInstanceState?.getBundle(NavigationKey)
        // Configure the window before composition; Compose updates insets after view dispatch.
        WindowCompat.enableEdgeToEdge(window)
        startupLink =
            if (savedInstanceState?.getBoolean(LinkHandledKey) == true) {
                savedInstanceState.getString(PendingLinkKey)
            } else intent?.viewLink()
        lifecycleScope.launch {
            (application as App).awaitStorage()
            lifecycle.withStarted {
                if (!isFinishing) setUpContent { firstCompositionReady = true }
            }
        }
    }

    private fun setUpContent(onFirstComposition: () -> Unit) {
        val initialHdrOutput =
            settingsStore.glassHdrEnabled.state.value &&
                settingsStore.liquidGlassEnabled.state.value &&
                !settingsStore.reduceTransparency.state.value
        configureGlassDynamicRange(window, hdrEnabled = initialHdrOutput)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Decompose registers its back callback before Compose registers page handlers.
        navigationComponent = retainedComponent { componentContext ->
            GithubAppNavigationComponent(
                componentContext = componentContext,
                predictiveBackEnabledAtLaunch = settingsStore.predictiveBackEnabled.state.value,
                predictiveBackPercent = settingsStore.predictiveBackMaxProgress.state.value,
                restored =
                    tokens.navigationSessionId?.let { savedNavigation?.restoreNavigation(it) },
            )
        }
        savedNavigation = null
        navigationComponent.initializeLink(startupLink)
        startupLink = null
        setContent {
            var initialInsetsReady by remember { mutableStateOf(false) }
            // Compose may initially report zero system bar insets, causing Scaffold to measure the
            // list with an incomplete top bar height and shift it down after inset dispatch.
            // A decorView dispatch alone does not mean Compose has updated: wait for its inset to
            // match the actual window value before composing the first screen. Zero can be valid.
            if (
                initialInsetsReady ||
                    WindowInsets.systemBars.getTop(LocalDensity.current) ==
                        windowManager.currentWindowMetrics.windowInsets
                            .getInsets(AndroidWindowInsets.Type.systemBars())
                            .top
            ) {
                SideEffect {
                    initialInsetsReady = true
                    onFirstComposition()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguageManager.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.viewLink()?.let { link ->
            if (::navigationComponent.isInitialized) navigationComponent.receiveLink(link)
            else startupLink = link
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(LoginOpenKey, loginOpen)
        if (::navigationComponent.isInitialized) {
            tokens.navigationSessionId?.let {
                outState.putBundle(NavigationKey, navigationComponent.saveNavigation(it))
            }
        } else savedNavigation?.let { outState.putBundle(NavigationKey, it) }
        outState.putBoolean(LinkHandledKey, true)
        outState.putString(
            PendingLinkKey,
            if (::navigationComponent.isInitialized) navigationComponent.pendingLink
            else startupLink,
        )
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
        const val NavigationKey = "github-navigation-v1"
        const val LinkHandledKey = "github-link-handled"
        const val PendingLinkKey = "github-pending-link"
    }
}
