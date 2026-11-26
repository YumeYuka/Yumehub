package moe.yumeyuka.yumehub.data.repository

import android.net.Uri
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubApi
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.RepositoryContentDto
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.network.useStreamingBody
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.HttpUrl
import retrofit2.HttpException

/** 只缓存服务端内容；编辑会话与未提交的草稿始终由页面持有。 */
class RepositoryContentRepository(
    private val api: GitHubApi,
    private val cache: CachedDataRepository,
    private val graphQlApi: GitHubGraphQlApi,
    private val tokens: TokenStore,
) {
    fun details(account: String, owner: String, name: String): CachedResource<RepositoryDto> =
        resource(account, owner, name, "details-graphql", null, "", RepositoryDto.serializer())

    fun refs(account: String, owner: String, name: String): CachedResource<List<String>> =
        resource(account, owner, name, "refs", null, "", ListSerializer(String.serializer()))

    fun readme(
        account: String,
        owner: String,
        name: String,
        ref: String?,
    ): CachedResource<RepositoryTextContent> =
        resource(
            account,
            owner,
            name,
            "readme-graphql",
            ref,
            "",
            RepositoryTextContent.serializer(),
        )

    fun license(account: String, owner: String, name: String) =
        resource(account, owner, name, "license-graphql", null, "", String.serializer())

    fun directory(
        account: String,
        owner: String,
        name: String,
        ref: String?,
        path: String,
    ): CachedResource<List<RepositoryContentDto>> {
        require(path.isEmpty() || isRepositoryContentPath(path))
        return resource(
            account,
            owner,
            name,
            "directory",
            ref,
            path,
            ListSerializer(RepositoryContentDto.serializer()),
        )
    }

    fun preview(account: String, owner: String, name: String, ref: String, path: String) =
        resource(
            account,
            owner,
            name,
            "workspace-preview-v1",
            ref,
            path,
            WorkspaceFilePreview.serializer(),
        )

    fun file(
        account: String,
        owner: String,
        name: String,
        ref: String?,
        path: String,
    ): CachedResource<RepositoryTextContent> {
        require(isRepositoryContentPath(path))
        return resource(
            account,
            owner,
            name,
            "workspace-blob-v1",
            ref,
            path,
            RepositoryTextContent.serializer(),
        )
    }

    suspend fun loadDetails(account: String, owner: String, name: String): RepositoryDto = request {
        val node = queryRepository(account, owner, name, RepositoryDetailsQuery)
        val branch =
            when (val result = node["defaultBranchRef"]) {
                JsonNull -> null
                is JsonObject -> result.graphRequiredText("name")
                else -> throw GithubPageException()
            }
        repositoryItem(node)
            .copy(
                defaultBranch = branch,
                forksCount = node["forkCount"]?.jsonPrimitive?.intOrNull,
                openIssuesCount =
                    node.graphObject("issues")["totalCount"]?.jsonPrimitive?.intOrNull,
                updatedAt = node.graphText("updatedAt"),
            )
    }

    suspend fun loadReadme(
        account: String,
        owner: String,
        name: String,
        ref: String?,
    ): RepositoryTextContent = request {
        val branch = ref?.removePrefix("refs/heads/")
        val node =
            queryRepository(account, owner, name, RepositoryReadmeQuery) {
                if (branch != null) put("branchName", branch)
            }
        val readme = node["readme"]
        if (readme == JsonNull) {
            RepositoryTextContent(null, node.graphRequiredText("url"))
        } else {
            val payload = readme as? JsonObject ?: throw GithubPageException()
            val path =
                payload.graphRequiredText("path").takeIf(::isRepositoryContentPath)
                    ?: throw GithubPageException()
            val html = payload.graphText("contentHTML") ?: throw GithubPageException()
            if (html.length > MaxRawContentBytes) throw GithubPageException()
            val pageUrl =
                HttpUrl.Builder()
                    .scheme("https")
                    .host("github.com")
                    .addPathSegment(node.graphObject("owner").graphRequiredText("login"))
                    .addPathSegment(node.graphRequiredText("name"))
                    .addPathSegment("blob")
                    .addPathSegments(branch ?: "HEAD")
                    .addPathSegments(path)
                    .build()
                    .toString()
            RepositoryTextContent(null, pageUrl, path = path, html = html)
        }
    }

    suspend fun loadLicense(account: String, owner: String, name: String): String = request {
        queryRepository(account, owner, name, RepositoryLicenseQuery)
            .graphRequiredText("licenseContents")
            .takeIf { it.length <= MaxRawContentBytes } ?: throw GithubPageException()
    }

    private suspend fun queryRepository(
        account: String,
        owner: String,
        name: String,
        query: String,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        GithubGraphQlSession(graphQlApi, tokens, account)
            .execute(
                query,
                buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                    extra()
                },
            )
            .graphObject("repository")

    suspend fun loadDirectory(
        owner: String,
        name: String,
        ref: String?,
        path: String,
    ): List<RepositoryContentDto> = request {
        require(path.isEmpty() || isRepositoryContentPath(path))
        try {
            val directory = api.getRepositoryContents(owner, name, Uri.encode(path, "/"), ref)
            val entries = directory.entries ?: throw SerializationException("缺少目录条目")
            entries
                .filter { entry ->
                    !entry.name.isNullOrBlank() &&
                        entry.path?.let(::isRepositoryContentPath) == true
                }
                .distinctBy { it.path }
                .sortedWith(compareBy({ it.type != "dir" }, { it.name.orEmpty().lowercase() }))
        } catch (error: HttpException) {
            if (path.isNotEmpty() || !error.isEmptyRepository()) throw error
            emptyList()
        }
    }

    /** 先废弃旧请求，再写入已确认提交成功的正文，避免旧读取覆盖新 SHA。 */
    fun fileSaved(
        account: String,
        owner: String,
        name: String,
        ref: String?,
        path: String,
        content: RepositoryTextContent,
    ) {
        require(isRepositoryContentPath(path))
        val file = file(account, owner, name, ref, path)
        file.invalidate()
        file.update(content)
        if (ref != null) preview(account, owner, name, ref, path).invalidate()
        var parent = path.substringBeforeLast('/', "")
        while (true) {
            directory(account, owner, name, ref, parent).invalidate()
            if (parent.isEmpty()) break
            parent = parent.substringBeforeLast('/', "")
        }
        readme(account, owner, name, ref).invalidate()
        license(account, owner, name).invalidate()
        details(account, owner, name).invalidate()
    }

    private fun <T : Any> resource(
        account: String,
        owner: String,
        name: String,
        kind: String,
        ref: String?,
        path: String,
        serializer: KSerializer<T>,
    ): CachedResource<T> {
        require(account.isNotBlank())
        // 长度与分隔符由 JSON 编码，ref/path 大小写及 null/空串都保持独立。
        val key =
            Json.encodeToString(
                ListSerializer(String.serializer().nullable),
                listOf(
                    // v2 为 RepositoryTextContent 增加 path，旧快照缺少该字段。
                    "repository-content-v2",
                    owner.lowercase(),
                    name.lowercase(),
                    kind,
                    ref,
                    path,
                ),
            )
        return cache.resource(
            account = account,
            key = key,
            serializer = serializer,
            tags = setOf(repositoryCacheTag(owner, name)),
        )
    }

    private suspend fun RepositoryContentDto.textPreview(
        owner: String,
        name: String,
        ref: String?,
        fallbackUrl: String?,
    ): RepositoryTextContent {
        val encoded = content
        val contentPath = path
        val bytes =
            when {
                encoding == "base64" && encoded != null ->
                    withContext(Dispatchers.Default) {
                        runCatching { Base64.Mime.decode(encoded) }.getOrNull()
                    }
                encoding == "none" && contentPath != null && isRepositoryContentPath(contentPath) ->
                    api.getRepositoryRawContent(owner, name, Uri.encode(contentPath, "/"), ref)
                        .useStreamingBody { body ->
                            if (body.contentLength() > MaxRawContentBytes)
                                return@useStreamingBody null
                            val source = body.source()
                            if (source.request(MaxRawContentBytes + 1L)) null
                            else source.readByteArray()
                        }
                else -> null
            }
        val text = bytes?.let { data ->
            withContext(Dispatchers.Default) {
                runCatching { data.decodeToString(throwOnInvalidSequence = true) }
                    .getOrNull()
                    ?.takeIf { value -> value.none { it.isISOControl() && it !in "\r\n\t" } }
            }
        }
        return RepositoryTextContent(text, htmlUrl ?: fallbackUrl, sha = sha, path = contentPath)
    }

    private suspend fun <T : Any> request(block: suspend () -> T): T =
        withTimeoutOrNull(20_000L) { block() } ?: throw RepositoryContentTimeoutException()

    private fun HttpException.isEmptyRepository(): Boolean =
        when (code()) {
            409 -> true
            404 ->
                runCatching {
                        val body = response()?.errorBody()?.string() ?: return@runCatching false
                        Json.parseToJsonElement(body)
                            .jsonObject["message"]
                            ?.jsonPrimitive
                            ?.content == "This repository is empty."
                    }
                    .getOrDefault(false)
            else -> false
        }
}

@Serializable
data class RepositoryTextContent(
    val text: String?,
    val htmlUrl: String?,
    val unavailableMessage: String = "此文件无法在应用中预览",
    val sha: String? = null,
    val path: String? = null,
    val html: String? = null,
)

class RepositoryContentTimeoutException : IOException("Repository content request timed out")

fun isRepositoryContentPath(path: String): Boolean =
    path.split('/').none { it.isEmpty() || it == "." || it == ".." }

private const val MaxRawContentBytes = 4 * 1024 * 1024

private val RepositoryDetailsQuery =
    """
    query RepositoryQuery(${'$'}owner: String!, ${'$'}name: String!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            $RepoFields
            defaultBranchRef { name }
            forkCount updatedAt
            issues(first: 0, states: [OPEN]) { totalCount }
        }
    }
    """
        .trimIndent()

private val RepositoryReadmeQuery =
    """
    query RepositoryReadme(${'$'}owner: String!, ${'$'}name: String!, ${'$'}branchName: String) {
        repository(owner: ${'$'}owner, name: ${'$'}name) {
            url name owner { login }
            readme(refName: ${'$'}branchName) { contentHTML path }
        }
    }
    """
        .trimIndent()

private val RepositoryLicenseQuery =
    """
    query RepositoryLicenseContents(${'$'}owner: String!, ${'$'}name: String!) {
        repository(owner: ${'$'}owner, name: ${'$'}name) { licenseContents }
    }
    """
        .trimIndent()
