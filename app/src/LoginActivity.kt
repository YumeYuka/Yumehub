package moe.yumeyuka.yumehub

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        }
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val addingAccount = intent.getBooleanExtra(AddingAccountKey, false)
        setContent {
            LoginRoot(
                addingAccount = addingAccount,
                onLoggedIn = {
                    setResult(RESULT_OK)
                    finish()
                },
                updateSystemBarAppearance = { isDark ->
                    bars.isAppearanceLightStatusBars = !isDark
                    bars.isAppearanceLightNavigationBars = !isDark
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    internal companion object {
        const val AddingAccountKey = "adding-account"
    }
}
