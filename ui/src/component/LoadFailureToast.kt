package moe.yumeyuka.yumehub.ui.component

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost

@Composable
fun LoadFailureToast(failure: Any?, message: String) {
    val context = LocalContext.current
    val visible = LocalAppPageHost.current?.isCurrent != false
    var shown by rememberSaveable(failure) { mutableStateOf<String?>(null) }
    DisposableEffect(context, failure, visible, message) {
        val toast =
            if (visible && failure != null && message.isNotBlank() && shown != message) {
                shown = message
                Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
            } else null
        onDispose { toast?.cancel() }
    }
}
