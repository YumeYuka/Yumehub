package moe.yumeyuka.yumehub.ui.platform

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberCopyText(): (label: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { label, text ->
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }
}
