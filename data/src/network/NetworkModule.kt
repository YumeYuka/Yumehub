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
                                request.body?.let { method(request.method, SingleUseBody(it)) }
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
                okHttpClient.newCall(bound)
            }
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }

    fun provideGitHubApi(retrofit: Retrofit): GitHubApi = retrofit.create(GitHubApi::class.java)

    fun provideGitHubGraphQlApi(retrofit: Retrofit): GitHubGraphQlApi =
        retrofit.create(GitHubGraphQlApi::class.java)

    private fun buildUserAgent(): String =
        "Yumehub/1.0.0 (Android ${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}"

    private const val BASE_URL = "https://api.github.com/"
    private const val API_HOST = "api.github.com"
    private const val BearerPrefixLength = 7
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
}

private class SingleUseBody(private val body: RequestBody) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    // 请求可能已在服务端执行，不能自动重放评论或评审。
    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
