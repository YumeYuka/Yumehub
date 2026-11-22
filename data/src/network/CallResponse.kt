package moe.yumeyuka.yumehub.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Call as RetrofitCall
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response as RetrofitResponse

/** 取消覆盖连接及响应体读取；read 返回前必须消费完需要的数据。 */
internal suspend fun <T> Call.useResponse(read: (Response) -> T): T =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                continuation.resume(execute().use(read))
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }

/** 仅供 @Streaming、@SkipCallbackExecutor 端点；读流期间保留 OkHttp 调度名额和取消句柄。 */
internal suspend fun <T> RetrofitCall<ResponseBody>.useStreamingBody(read: (ResponseBody) -> T): T =
    suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { cancel() }
        if (!result.isActive) return@suspendCancellableCoroutine
        enqueue(
            object : Callback<ResponseBody> {
                override fun onResponse(
                    call: RetrofitCall<ResponseBody>,
                    response: RetrofitResponse<ResponseBody>,
                ) {
                    val value =
                        try {
                            if (!response.isSuccessful) {
                                response.errorBody()?.close()
                                throw HttpException(response)
                            }
                            val body = response.body() ?: throw IOException("Missing response body")
                            body.use {
                                if (!result.isActive) return
                                read(it)
                            }
                        } catch (failure: Throwable) {
                            // 回调运行在 OkHttp 线程；把原异常交回调用协程，不转换为网络错误。
                            result.resumeWithException(failure)
                            return
                        }
                    result.resume(value)
                }

                override fun onFailure(call: RetrofitCall<ResponseBody>, failure: Throwable) {
                    result.resumeWithException(failure)
                }
            },
        )
    }
