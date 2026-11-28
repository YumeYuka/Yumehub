package moe.yumeyuka.yumehub.feature.settings

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Folder
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RotateCcw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost

@Composable
internal fun DownloadDirectorySection(viewModel: SettingsPageViewModel) {
    val strings = LocalAppStrings.current.Downloads
    val host = requireNotNull(LocalAppPageHost.current)
    val context = LocalContext.current
    val resolver = context.contentResolver
    val directory by viewModel.downloadDirectory.collectAsStateWithLifecycle()
    val tree = remember(directory) { directory?.let(Uri::parse) }
    val name by
        produceState<String?>(null, tree, resolver) {
            value = tree?.let { withContext(Dispatchers.IO) { directoryName(resolver, it) } }
        }
    fun showFailure() = Toast.makeText(context, strings.DirectoryFailed, Toast.LENGTH_SHORT).show()

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && host.navigationEnabled) {
                try {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    viewModel.setDownloadDirectory(uri.toString())
                } catch (_: SecurityException) {
                    showFailure()
                } catch (_: IllegalStateException) {
                    showFailure()
                }
            }
        }

    ListSection(strings.Title) {
        GlasmioListRow(
            title = strings.Directory,
            subtitle =
                if (tree == null) "${Environment.DIRECTORY_DOWNLOADS}/Yumehub"
                else name ?: tree.lastPathSegment,
            leading = { AppIcon(Lucide.Folder, contentDescription = null) },
            enabled = host.navigationEnabled,
            onClick = {
                try {
                    picker.launch(tree)
                } catch (_: ActivityNotFoundException) {
                    showFailure()
                }
            },
            trailing =
                if (tree == null) null
                else
                    ({
                        GlasmioIconButton(
                            onClick = { viewModel.setDownloadDirectory(null) },
                            enabled = host.navigationEnabled,
                            sampleBackdrop = false,
                        ) {
                            AppIcon(
                                Lucide.RotateCcw,
                                contentDescription = strings.ResetDirectory,
                            )
                        }
                    }),
        )
    }
}

private fun directoryName(resolver: ContentResolver, tree: Uri): String? {
    if (!DocumentsContract.isTreeUri(tree)) return null
    return try {
        val document =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        val name = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        resolver.query(document, arrayOf(name), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
