package moe.yumeyuka.yumehub.data.download

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class DownloadError {
    NETWORK,
    STORAGE,
    DIGEST,
    INTERRUPTED,
    EXPIRED,
}

@Serializable
data class DownloadTask(
    val id: String,
    val account: String,
    val name: String,
    val mime: String,
    val expectedDigest: String?,
    val total: Long?,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0,
    val speed: Long = 0,
    val uri: String? = null,
    val sha256: String? = null,
    val error: DownloadError? = null,
    val sourceArchive: Boolean = false,
    val completedAtMillis: Long? = null,
) {
    val active: Boolean
        get() =
            status == DownloadStatus.QUEUED ||
                status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.PAUSED ||
                status == DownloadStatus.VERIFYING
}

internal fun safeDownloadName(name: String): String =
    name.replace(Regex("""[/\\\p{Cntrl}]"""), "_").trim().trim('.').take(180).ifBlank { "download" }

internal fun MessageDigest.verifiedSha256(expected: String?): String {
    val actual = digest().joinToString("") { "%02x".format(it) }
    if (expected != null && !expected.removePrefix("sha256:").equals(actual, ignoreCase = true)) {
        throw DownloadFailure(DownloadError.DIGEST)
    }
    return actual
}

internal class DownloadFailure(val reason: DownloadError, cause: Exception? = null) :
    Exception(reason.name, cause)

fun interface DownloadStarter {
    fun start(id: String)
}

private val downloadHistoryJson = Json { ignoreUnknownKeys = true }

/** 仅恢复可清理的半成品；损坏条目不阻止其他任务恢复，也不冒充下载成功。 */
internal fun interruptedDownloads(raw: String?): List<DownloadTask> {
    if (raw == null) return emptyList()
    val entries =
        try {
            downloadHistoryJson.parseToJsonElement(raw) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyList()
    return entries.mapNotNull { entry ->
        val task =
            try {
                downloadHistoryJson.decodeFromJsonElement<DownloadTask>(entry)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
        task
            .takeIf { it.uri != null && it.status != DownloadStatus.COMPLETED }
            ?.copy(status = DownloadStatus.FAILED, error = DownloadError.INTERRUPTED, speed = 0)
    }
}
