package moe.yumeyuka.yumehub.data.api

import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.GraphQlResponse
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Tag

/** 复用当前登录认证的 GraphQL 通道，供设备审批与首页收藏等请求使用 */
interface GitHubGraphQlApi {
    @POST("graphql")
    suspend fun execute(
        @Body request: GraphQlRequest,
        @HeaderMap headers: Map<String, String> = emptyMap(),
        @Tag policy: SingleSubmission? = null,
    ): GraphQlResponse
}
