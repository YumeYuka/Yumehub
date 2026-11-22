package moe.yumeyuka.yumehub.data.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class DownloadDestination(private val context: Context) {
    private val resolver: ContentResolver
        get() = context.contentResolver

    fun create(task: DownloadTask, tree: String?): Uri = writing {
        if (tree != null) {
            val root = Uri.parse(tree)
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    root,
                    DocumentsContract.getTreeDocumentId(root),
                )
            DocumentsContract.createDocument(resolver, parent, task.mime, task.name)
        } else {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, task.name)
                    put(MediaStore.Downloads.MIME_TYPE, task.mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Yumehub/",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: throw IOException("No destination")
    }

    fun open(uri: Uri) = writing {
        resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
    }

    fun publish(uri: Uri) = writing {
        if (uri.authority == MediaStore.AUTHORITY) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Cannot publish")
        }
    }

    fun discard(uri: Uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null)
        }
    }
}

internal inline fun <T> writing(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DownloadFailure(DownloadError.STORAGE, e)
    }
