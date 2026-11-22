package moe.yumeyuka.yumehub.ui.platform

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import moe.yumeyuka.yumehub.data.browser.openExternalUri
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

@Composable
fun rememberExternalUriHandler(): (String) -> Unit {
    val context = LocalContext.current
    val failureMessage = LocalGithubStrings.current.Common.ExternalLinkOpenFailed
    return remember(context, failureMessage) {
        { url ->
            if (!openExternalUri(context, url)) {
                Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
