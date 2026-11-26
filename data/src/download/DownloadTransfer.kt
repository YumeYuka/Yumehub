package moe.yumeyuka.yumehub.data.download

import android.net.Uri
import android.os.SystemClock
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Duration
import moe.yumeyuka.yumehub.data.network.useResponse
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class DownloadTransfer(
    client: OkHttpClient,
    private val destination: DownloadDestination,
) {
    // 下载不使用注入 API 令牌的 Call.Factory；长传输只受连接和读写超时限制。
    private val http =
        client.newBuilder().callTimeout(Duration.ZERO).followSslRedirects(false).build()

    suspend fun receive(
        task: DownloadTask,
        resolve: suspend () -> String,
        awaitReady: suspend () -> Unit,
        onCall: (Call) -> Unit,
        create: () -> Uri,
        onProgress: (Long, Long?, Long) -> Unit,
        onVerify: () -> Unit,
    ): String {
        val uri by lazy(create)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var validator: String? = null
        var output: OutputStream? = null
        try {
            while (true) {
                awaitReady()
                val link = resolve().toHttpUrl()
                require(link.isHttps && link.username.isEmpty() && link.password.isEmpty())
                val offset = received.takeIf {
                    it > 0 && (validator != null || task.expectedDigest != null)
                }
                val request = Request.Builder().url(link).header("Accept-Encoding", "identity")
                if (offset != null) {
                    request.header("Range", "bytes=$offset-")
                    validator?.let { request.header("If-Range", it) }
                }
                val call = http.newCall(request.build())
                onCall(call)
                try {
                    val completed = call.useResponse { response ->
                        val length = response.body.contentLength().takeIf { it >= 0 }
                        val total =
                            when (response.code) {
                                200 -> {
                                    // 未支持 Range 或远端文件已变化：复用目标文件，从零覆盖并重新计算摘要。
                                    output?.let { writing { it.close() } }
                                    output = destination.open(uri)
                                    received = 0
                                    digest.reset()
                                    validator =
                                        response.header("ETag")?.takeIf {
                                            it.startsWith('"') && it.endsWith('"')
                                        }
                                    length
                                }
                                206 -> {
                                    val etag = response.header("ETag")
                                    if (validator != null && etag != null && etag != validator) {
                                        throw IOException("File changed during download")
                                    }
                                    resumedDownloadLength(
                                        offset,
                                        response.header("Content-Range"),
                                        length,
                                    )
                                }
                                416 -> {
                                    if (offset == null) throw IOException("HTTP 416")
                                    received = 0
                                    validator = null
                                    return@useResponse null
                                }
                                else -> throw IOException("HTTP ${response.code}")
                            }
                        val sink = checkNotNull(output)
                        val input = response.body.byteStream()
                        var sample = received
                        var clock = SystemClock.elapsedRealtime()
                        onProgress(received, total ?: task.total, 0)
                        while (!call.isCanceled()) {
                            val count = input.read(buffer)
                            if (count < 0) {
                                if (total != null && received != total)
                                    throw IOException("Incomplete response")
                                writing { sink.flush() }
                                onProgress(received, received, 0)
                                onVerify()
                                return@useResponse digest.verifiedSha256(task.expectedDigest)
                            }
                            writing { sink.write(buffer, 0, count) }
                            digest.update(buffer, 0, count)
                            received += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - clock >= 500) {
                                onProgress(
                                    received,
                                    total ?: task.total,
                                    (received - sample) * 1000 / (now - clock),
                                )
                                sample = received
                                clock = now
                            }
                        }
                        onProgress(received, total ?: task.total, 0)
                        null
                    }
                    if (completed != null) return completed
                } catch (e: IOException) {
                    if (!call.isCanceled()) throw e
                }
            }
        } finally {
            output?.let { writing { it.close() } }
        }
    }
}

private val remainingRange = Regex("""bytes (\d+)-(\d+)/(\d+)""")

internal fun resumedDownloadLength(offset: Long?, header: String?, length: Long?): Long {
    val match = header?.let(remainingRange::matchEntire)
    val start = match?.groupValues?.get(1)?.toLongOrNull()
    val end = match?.groupValues?.get(2)?.toLongOrNull()
    val total = match?.groupValues?.get(3)?.toLongOrNull()
    if (
        offset == null ||
            start != offset ||
            total == null ||
            total <= offset ||
            end != total - 1 ||
            (length != null && length != total - offset)
    ) {
        throw IOException("Invalid Content-Range")
    }
    return total
}
