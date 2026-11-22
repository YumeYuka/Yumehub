package moe.yumeyuka.yumehub

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import kotlinx.coroutines.flow.first
import moe.yumeyuka.yumehub.core.model.parseGithubLink
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.data.storage.mmkv.ThemeMode
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import moe.yumeyuka.yumehub.feature.auth.AuthEvent
import moe.yumeyuka.yumehub.feature.auth.AuthUiState
import moe.yumeyuka.yumehub.feature.auth.AuthViewModel
import moe.yumeyuka.yumehub.feature.auth.LoginScreen
import moe.yumeyuka.yumehub.feature.auth.R as AuthResources
import moe.yumeyuka.yumehub.feature.auth.TwoFactorOverlay
import moe.yumeyuka.yumehub.feature.auth.TwoFactorViewModel
import moe.yumeyuka.yumehub.feature.markdown.officialGithubMarkdownRenderer
import moe.yumeyuka.yumehub.feature.profile.SettingsViewModel
import moe.yumeyuka.yumehub.navigation.GithubAppNavigationComponent
import moe.yumeyuka.yumehub.navigation.GithubAppShell
import moe.yumeyuka.yumehub.navigation.GithubNavigationSession
import moe.yumeyuka.yumehub.navigation.openLink
import moe.yumeyuka.yumehub.ui.component.GithubFpsCounter
import moe.yumeyuka.yumehub.ui.component.GithubTextSelectionHost
import moe.yumeyuka.yumehub.ui.component.LocalGithubMarkdownRenderer
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import moe.yumeyuka.yumehub.ui.theme.YumehubTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun AppRoot(
    navigationComponent: GithubAppNavigationComponent,
    loginOpen: Boolean,
    openLogin: () -> Unit,
    updateGlassHdrOutput: (enabled: Boolean) -> Unit,
    updateSystemBarAppearance: (isDark: Boolean) -> Unit,
) {
    val authViewModel: AuthViewModel = koinViewModel()
    val tokens: TokenStore = koinInject()
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val twoFactorViewModel: TwoFactorViewModel = koinViewModel()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            twoFactorViewModel.refreshNotificationPermission()
        }

    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val openInBrowser = rememberExternalUriHandler()
    val pending = navigationComponent.pendingLink
    LaunchedEffect(authState, pending, loginOpen) {
        if (pending == null || loginOpen) return@LaunchedEffect
        val needsSession = parseGithubLink(pending) != null
        if (needsSession && authState !is AuthUiState.Authenticated) return@LaunchedEffect
        snapshotFlow { navigationComponent.state.locked }.first { !it }
        if (!navigationComponent.consumeLink(pending)) return@LaunchedEffect
        if (!navigationComponent.openLink(pending)) openInBrowser(pending)
    }
    val twoFactorState by twoFactorViewModel.uiState.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val accentArgb by settingsViewModel.accentColor.collectAsStateWithLifecycle()
    val liquidGlassEnabled by settingsViewModel.liquidGlassEnabled.collectAsStateWithLifecycle()
    val reduceTransparency by settingsViewModel.reduceTransparency.collectAsStateWithLifecycle()
    val topBarBlurEnabled by settingsViewModel.topBarBlurEnabled.collectAsStateWithLifecycle()
    val glassHdrEnabled by settingsViewModel.glassHdrEnabled.collectAsStateWithLifecycle()
    val fpsCounter by settingsViewModel.fpsCounter.collectAsStateWithLifecycle()
    val hdrOutput = glassHdrEnabled && liquidGlassEnabled && !reduceTransparency
    LaunchedEffect(hdrOutput) { updateGlassHdrOutput(hdrOutput) }
    val isDark = themeMode.isDark()
    SideEffect { updateSystemBarAppearance(isDark) }

    DisposableEffect(lifecycle, twoFactorViewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            twoFactorViewModel.setForeground(
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
        }
        lifecycle.addObserver(observer)
        twoFactorViewModel.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(authState is AuthUiState.Authenticated, loginOpen) {
        if (authState is AuthUiState.Authenticated && !loginOpen) {
            if (
                !notificationPermissionRequested &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionRequested = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            twoFactorViewModel.refreshNotificationPermission()
        }
    }

    LaunchedEffect(authViewModel) {
        authViewModel.events.collect { event ->
            when (event) {
                is AuthEvent.AuthorizationFailed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                AuthEvent.SignedOut -> Unit
            }
        }
    }

    YumehubTheme(
        isDark = isDark,
        accent = Color(accentArgb),
        liquidGlassEnabled = liquidGlassEnabled,
        reduceTransparency = reduceTransparency,
        topBarBlurEnabled = topBarBlurEnabled,
    ) {
        CompositionLocalProvider(
            LocalGithubMarkdownRenderer provides officialGithubMarkdownRenderer,
        ) {
            GlasmioBackdropHost(
                sampledContent = {
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(GlasmioTheme.colors.groupedBackground),
                    )
                },
                overlayContent = {
                    GithubTextSelectionHost {
                        when (val currentAuthState = authState) {
                            AuthUiState.Restoring -> Unit

                            is AuthUiState.Authenticated ->
                                AccountSwitchTransition(
                                    session = currentAuthState,
                                    switchAccount = authViewModel::switchAccount,
                                ) { requestSwitch ->
                                    key(currentAuthState.login, currentAuthState.generation) {
                                        val sessionOwner = rememberViewModelStoreOwner()
                                        viewModel(viewModelStoreOwner = sessionOwner) {
                                            GithubNavigationSession(
                                                component = navigationComponent,
                                                login = currentAuthState.login,
                                            )
                                        }
                                        CompositionLocalProvider(
                                            LocalViewModelStoreOwner provides sessionOwner,
                                        ) {
                                            GithubAppShell(
                                                navigationComponent = navigationComponent,
                                                currentLogin = currentAuthState.login,
                                                accounts = tokens.accounts,
                                                onAddAccount = openLogin,
                                                onSwitchAccount = requestSwitch,
                                                onSignOut = authViewModel::signOut,
                                                settingsViewModel = settingsViewModel,
                                            )
                                        }
                                    }
                                    TwoFactorOverlay(
                                        state = twoFactorState,
                                        consumeNotice = twoFactorViewModel::consumeNotice,
                                        onDigitsChange = twoFactorViewModel::onDigitsChange,
                                        onApprove = twoFactorViewModel::approve,
                                        onReject = twoFactorViewModel::reject,
                                        onDismiss = twoFactorViewModel::dismiss,
                                    )
                                    if (fpsCounter) {
                                        GithubFpsCounter(
                                            Modifier.align(Alignment.TopEnd)
                                                .statusBarsPadding()
                                                .padding(top = 70.dp, end = 12.dp),
                                        )
                                    }
                                }

                            AuthUiState.SignedOut ->
                                LaunchedEffect(Unit) {
                                    // 返回结果先于会话流重放时，不要再打开一个登录页。
                                    if (
                                        tokens.accountLogin.value.isBlank() ||
                                            tokens.accessToken.value.isBlank()
                                    ) {
                                        openLogin()
                                    }
                                }
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun LoginRoot(
    addingAccount: Boolean,
    onLoggedIn: () -> Unit,
    updateSystemBarAppearance: (Boolean) -> Unit,
) {
    val settings: SettingsStore = koinInject()
    val themeMode by settings.themeMode.state.collectAsStateWithLifecycle()
    val isDark = themeMode.isDark()
    SideEffect { updateSystemBarAppearance(isDark) }
    YumehubTheme(isDark = isDark) {
        GlasmioBackdropHost(
            modifier = Modifier.fillMaxSize(),
            sampledContent = {
                val colors = GlasmioTheme.colors
                Image(
                    painter = painterResource(AuthResources.drawable.login_paper),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().background(colors.elevatedBackground),
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(colors.content),
                )
            },
            overlayContent = {
                LoginScreen(onLoggedIn = onLoggedIn, addingAccount = addingAccount)
            },
        )
    }
}

@Composable
private fun ThemeMode.isDark(): Boolean =
    when (this) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
