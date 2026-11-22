package moe.yumeyuka.yumehub.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Link
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Settings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection

@Composable
internal fun LinkHandlingSection() {
    val strings = LocalGithubStrings.current.LinkHandling
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var officialInstalled by remember(context) { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        fun refresh() {
            officialInstalled =
                try {
                    context.packageManager.getApplicationInfo(
                        OfficialGithubPackage,
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val openSettings: (String) -> Unit = { packageName ->
        if (!openLinkSettings(context, packageName)) {
            Toast.makeText(context, strings.SettingsOpenFailed, Toast.LENGTH_SHORT).show()
        }
    }
    GithubSection(strings.Title) {
        GlasmioListRow(
            title = strings.OpenWithYumehub,
            leading = { GithubIcon(Lucide.Link, contentDescription = null) },
            onClick = { openSettings(context.packageName) },
        )
        if (officialInstalled) {
            GlasmioListRow(
                title = strings.OfficialGithub,
                leading = { GithubIcon(Lucide.Settings, contentDescription = null) },
                onClick = { openSettings(OfficialGithubPackage) },
            )
        }
    }
}

/** OEM 缺少专用入口时退回该应用的信息页，最后尝试系统默认应用设置。 */
private fun openLinkSettings(context: Context, packageName: String): Boolean {
    val packageUri = Uri.fromParts("package", packageName, null)
    val intents =
        listOf(
            Intent(AndroidSettings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri),
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )
    for (intent in intents) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
    }
    return false
}

private const val OfficialGithubPackage = "com.github.android"
