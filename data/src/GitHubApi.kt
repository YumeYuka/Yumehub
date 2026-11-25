package moe.yumeyuka.yumehub.data.api

import moe.yumeyuka.yumehub.data.api.dto.FeedEventDto
import moe.yumeyuka.yumehub.data.api.dto.ForkRepositoryRequest
import moe.yumeyuka.yumehub.data.api.dto.RepositoryContentDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.api.dto.SearchRepositoriesResponse
import moe.yumeyuka.yumehub.data.api.dto.UserDto
import moe.yumeyuka.yumehub.data.markdown.GithubMarkdownRequest
import moe.yumeyuka.yumehub.data.network.SingleSubmission
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.SkipCallbackExecutor
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag

interface GitHubApi {
    @SkipCallbackExecutor
    @Streaming
    @POST("markdown")
    fun renderMarkdown(@Body request: GithubMarkdownRequest): Call<ResponseBody>

    @GET("users/{username}/received_events")
    suspend fun receivedEvents(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Response<List<FeedEventDto>>

    @GET("users/{username}") suspend fun getUser(@Path("username") username: String): UserDto

    @GET("user/starred/{owner}/{repo}")
    suspend fun isStarred(@Path("owner") owner: String, @Path("repo") repo: String): Response<Unit>

    @PUT("user/starred/{owner}/{repo}")
    suspend fun starRepository(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Tag policy: SingleSubmission = SingleSubmission,
    ): Response<Unit>

    @DELETE("user/starred/{owner}/{repo}")
    suspend fun unstarRepository(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Tag policy: SingleSubmission = SingleSubmission,
    ): Response<Unit>

    @GET("user/following/{username}")
    suspend fun isFollowing(@Path("username") username: String): Response<Unit>

    @PUT("user/following/{username}")
    suspend fun followUser(
        @Path("username") username: String,
        @Tag policy: SingleSubmission = SingleSubmission,
    ): Response<Unit>

    @DELETE("user/following/{username}")
    suspend fun unfollowUser(
        @Path("username") username: String,
        @Tag policy: SingleSubmission = SingleSubmission,
    ): Response<Unit>

    @GET("user/starred")
    suspend fun listStarredRepositories(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Response<List<RepositoryDto>>

    @GET("users/{username}/repos")
    suspend fun listUserRepositories(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
        @Query("type") type: String = "owner",
        @Query("sort") sort: String = "updated",
    ): Response<List<RepositoryDto>>

    @GET("orgs/{org}/repos")
    suspend fun listOrganizationRepositories(
        @Path("org") organization: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
        @Query("sort") sort: String = "updated",
    ): Response<List<RepositoryDto>>

    @GET("users/{username}/starred")
    suspend fun listUserStarredRepositories(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Response<List<RepositoryDto>>

    @GET("users/{username}/orgs")
    suspend fun listUserOrganizations(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Response<List<UserDto>>

    @GET("user/orgs")
    suspend fun listViewerOrganizations(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Response<List<UserDto>>

    @POST("repos/{owner}/{repo}/forks")
    suspend fun forkRepository(
        @Path("owner") owner: String,
        @Path("repo") repository: String,
        @Body request: ForkRepositoryRequest,
        @Header("Authorization") authorization: String? = null,
        @Tag policy: SingleSubmission = SingleSubmission,
    ): RepositoryDto

    @GET("repos/{owner}/{repo}/contributors")
    suspend fun listRepositoryContributors(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 1,
    ): Response<List<UserDto>>

    @SkipCallbackExecutor
    @Streaming
    @Headers("Accept: application/vnd.github.raw+json")
    @GET("repos/{owner}/{repo}/contents/{path}")
    fun getRepositoryRawContent(
        @Path("owner") owner: String,
        @Path("repo") repository: String,
        @Path("path", encoded = true) encodedPath: String,
        @Query("ref") ref: String?,
    ): Call<ResponseBody>

    @Headers("Accept: application/vnd.github.object+json")
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getRepositoryContents(
        @Path("owner") owner: String,
        @Path("repo") repository: String,
        @Path("path", encoded = true) encodedPath: String,
        @Query("ref") ref: String?,
    ): RepositoryContentDto

    @GET("search/repositories")
    suspend fun searchRepositories(
        @Query("q") query: String,
        @Query("sort") sort: String = "stars",
        @Query("order") order: String = "desc",
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = 30,
    ): Response<SearchRepositoriesResponse>
}
