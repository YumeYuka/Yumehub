package moe.yumeyuka.yumehub.data.network

import android.os.Build
import java.time.Duration
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.api.GitHubApi
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.auth.SessionFailures
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object SingleSubmission

object NetworkModule {
    fun provideOkHttpClient(
        tokenStore: TokenStore,
        sessionFailures: SessionFailures,
    ): OkHttpClient {
        val userAgent = buildUserAgent()
        return OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            .addInterceptor { chain ->
                val request = chain.request()
                val authorization = request.header("Authorization")
                val explicitToken =
                    authorization
                        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                        ?.substring(BearerPrefixLength)
                        ?.takeIf(String::isNotBlank)
                val authorized =
                    request
                        .newBuilder()
                        .header("User-Agent", userAgent)
                        .apply {
                            if (request.tag(SingleSubmission::class.java) != null) {
                                // 服务端可能已执行：一次性正文，并阻止无 body 写被 OkHttp 自动重放。
                                val body =
                                    request.body
                                        ?: if (request.method in BodylessWriteMethods) {
                                            ByteArray(0).toRequestBody(null)
                                        } else null
                                if (body != null) method(request.method, SingleUseBody(body))
                            }
                        }
                        .build()
                chain.proceed(authorized).also { response ->
                    val sentToken = explicitToken
                    if (
                        response.code == 401 &&
                            response.request.url.host == API_HOST &&
                            sentToken != null &&
                            sentToken == tokenStore.accessToken.value
                    ) {
                        sessionFailures.reportUnauthorized(sentToken)
                    }
                }
            }
            .build()
    }

    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json, tokens: TokenStore): Retrofit {
        // 业务 API 与其他有限请求共用客户端的总时限。
        val singleSubmission = singleSubmissionClient(okHttpClient)
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            // 在调用方创建请求时绑定账号，不在 OkHttp 队列出队后读取新账号的令牌。
            .callFactory { request ->
                val token = tokens.accessToken.value
                val bound =
                    if (
                        request.header("Authorization") == null &&
                            token.isNotBlank() &&
                            request.url.isHttps &&
                            request.url.host == API_HOST
                    )
                        request.newBuilder().header("Authorization", "Bearer $token").build()
                    else request
                val client =
                    if (request.tag(SingleSubmission::class.java) != null) singleSubmission
                    else okHttpClient
                client.newCall(bound)
            }
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 用户提交的写请求使用不重试、不跟随重定向的派生客户端；连接池和 dispatcher 与共享客户端相同。
     * 测试与直接撤销请求也必须使用这个生产工厂。
     */
    fun singleSubmissionClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(okhttp3.Authenticator.NONE)
            .build()

    fun provideGitHubApi(retrofit: Retrofit): GitHubApi = retrofit.create(GitHubApi::class.java)

    fun provideGitHubGraphQlApi(retrofit: Retrofit): GitHubGraphQlApi =
        retrofit.create(GitHubGraphQlApi::class.java)

    private fun buildUserAgent(): String =
        "Yumehub/1.0.0 (Android ${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}"

    private const val BASE_URL = "https://api.github.com/"
    private const val API_HOST = "api.github.com"
    private const val BearerPrefixLength = 7
    private val BodylessWriteMethods = setOf("PUT", "DELETE", "POST", "PATCH")
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}

private class SingleUseBody(private val body: RequestBody) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    // 请求可能已在服务端执行，不能自动重放评论或评审。
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
