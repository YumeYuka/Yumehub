package moe.yumeyuka.yumehub.data.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class CallResponseTest {
    @Test
    fun cancellingDuringBodyReadCancelsCallAndClosesResponse() = runBlocking {
        val enteredRead = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val closed = CompletableDeferred<Unit>()
        val body =
            object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        enteredRead.complete(Unit)
                        check(cancelled.await(5, TimeUnit.SECONDS))
                        throw IOException("cancelled read")
                    }

                    override fun close() {
                        closed.complete(Unit)
                        super.close()
                    }
                }
                .buffer()
                .asResponseBody()
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    Response.Builder()
                        .request(it.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build()
        val transport = client.newCall(Request.Builder().url("https://example.invalid/").build())
        val call =
            object : Call by transport {
                override fun cancel() {
                    transport.cancel()
                    cancelled.countDown()
                }
            }
        val reader = launch { call.useResponse { it.body.string() } }
        withTimeout(5_000) {
            enteredRead.await()
            reader.cancel()
            reader.join()
            closed.await()
        }
        assertTrue(call.isCanceled())
    }
}
