package moe.yumeyuka.yumehub.data.repository

import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.network.useResponse
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class WorkflowArtifact(
    val id: Long,
    val name: String,
    val bytes: Long,
    val digest: String?,
    val expired: Boolean,
)

data class WorkflowArtifacts(val files: List<WorkflowArtifact>, val total: Int, val next: Int?)

private fun artifactApi(owner: String, name: String) =
    HttpUrl.Builder()
        .scheme("https")
        .host("api.github.com")
        .addPathSegment("repos")
        .addPathSegment(owner)
        .addPathSegment(name)
        .addPathSegment("actions")

private suspend fun <T> OkHttpClient.artifactRequest(
    tokens: TokenStore,
    account: String,
    url: HttpUrl,
    read: (Response) -> T,
): T {
    val generation =
        synchronized(tokens.responseCacheSession.lock) { tokens.responseCacheSession.generation }
    val headers = tokens.authorization(account, generation)
    val request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
    return newCall(request).useResponse(read).also { tokens.requireSession(account, generation) }
}

internal suspend fun listWorkflowArtifacts(
    client: OkHttpClient,
    tokens: TokenStore,
    account: String,
    owner: String,
    name: String,
    run: Long,
    page: Int,
): WorkflowArtifacts {
    require(run > 0 && page > 0)
    val url =
        artifactApi(owner, name)
            .addPathSegments("runs/$run/artifacts")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "100")
            .build()
    return client.artifactRequest(tokens, account, url) { response ->
        if (!response.isSuccessful) throw GithubPageException()
        val data = Json.parseToJsonElement(response.body.string()).jsonObject
        val files =
            data.getValue("artifacts").jsonArray.map {
                val file = it.jsonObject
                WorkflowArtifact(
                    file.getValue("id").jsonPrimitive.long,
                    file.graphRequiredText("name"),
                    file.getValue("size_in_bytes").jsonPrimitive.long,
                    file.graphText("digest"),
                    file.getValue("expired").jsonPrimitive.boolean,
                )
            }
        WorkflowArtifacts(
            files,
            data.getValue("total_count").jsonPrimitive.int,
            (page + 1).takeIf { response.header("Link").orEmpty().contains("rel=\"next\"") },
        )
    }
}

internal suspend fun workflowArtifactDownload(
    client: OkHttpClient,
    tokens: TokenStore,
    account: String,
    owner: String,
    name: String,
    id: Long,
): String {
    require(id > 0)
    return client.artifactRequest(
        tokens,
        account,
        artifactApi(owner, name).addPathSegments("artifacts/$id/zip").build(),
    ) {
        if (it.code == 410) throw WorkflowArtifactExpired()
        if (it.code != 302) throw GithubPageException()
        val location = it.header("Location")?.toHttpUrlOrNull() ?: throw GithubPageException()
        if (!location.isHttps || location.username.isNotEmpty() || location.password.isNotEmpty())
            throw GithubPageException()
        location.toString()
    }
}

class WorkflowArtifactExpired : Exception()
