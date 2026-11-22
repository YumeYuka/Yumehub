package moe.yumeyuka.yumehub.data.markdown

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.GitHubApi
import moe.yumeyuka.yumehub.data.network.useStreamingBody
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MarkdownStreamingTest {
    @Test
    fun cancellingLiveRetrofitBodyReleasesReaderAndClosesSource() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val closed = CompletableDeferred<Unit>()
        val source =
            object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.complete(Unit)
                    check(cancelled.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled read")
                }

                override fun close() {
                    closed.complete(Unit)
                    super.close()
                }
            }
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    Response.Builder()
                        .request(it.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(source.buffer().asResponseBody())
                        .build()
                }
                .build()
        val api =
            Retrofit.Builder()
                .baseUrl("https://example.invalid/")
                .callFactory { request ->
                    val transport = client.newCall(request)
                    object : Call by transport {
                        override fun cancel() {
                            transport.cancel()
                            cancelled.countDown()
                        }
                    }
                }
                .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GitHubApi::class.java)
        val job = launch {
            api.renderMarkdown(GithubMarkdownRequest("text", "gfm")).useStreamingBody {
                it.readLimitedHtml()
            }
        }
        withTimeout(5_000) {
            reading.await()
            job.cancel()
            closed.await()
            job.join()
        }
        assertEquals(0L, cancelled.count)
    }

    @Test
    fun enforcesUnknownLengthLimitWithoutRejectingExactBoundary() {
        val exact = Buffer().write(ByteArray(MaxHtmlBytes))
        assertEquals(MaxHtmlBytes, exact.asResponseBody().use { it.readLimitedHtml().length })
        val excess = Buffer().write(ByteArray(MaxHtmlBytes + 1))
        assertThrows(GithubMarkdownException::class.java) {
            excess.asResponseBody().use { it.readLimitedHtml() }
        }
    }
}
