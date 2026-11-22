package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup

/** 展示文本不保留原始编码和换行，不能用作提交草稿。 */
@Serializable
data class WorkspaceFilePreview(val commit: String, val lines: String?, val html: String?)

internal fun parseWorkspaceFilePreview(repository: JsonObject): WorkspaceFilePreview {
    val revision = repository["object"] as? JsonObject ?: throw WorkspacesFileNotFound()
    val commit = revision["target"] as? JsonObject ?: revision
    val oid = commit.graphRequiredText("oid")
    val file = commit["file"] as? JsonObject ?: throw WorkspacesFileNotFound()
    val type = file["fileType"] as? JsonObject
    val rows = type?.get("fileLines") as? JsonArray
    val lines =
        rows
            ?.mapIndexed { index, item ->
                val row = item as? JsonObject ?: throw SerializationException("Invalid file line")
                if (row.graphText("number")?.toIntOrNull() != index + 1) {
                    throw SerializationException("Unexpected file line number")
                }
                val html =
                    row.graphText("html") ?: throw SerializationException("Missing file line")
                if ('<' in html || '&' in html) Jsoup.parseBodyFragment(html).body().wholeText()
                else html
            }
            ?.joinToString("\n")
    return WorkspaceFilePreview(oid, lines, type?.graphText("contentHTML"))
}
