package moe.yumeyuka.yumehub.data.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.core.model.GithubGitObject
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.core.model.githubGitRefCandidates
import moe.yumeyuka.yumehub.core.model.resolveGithubGitObject
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlError
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.GraphQlResponse
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/** 当前目录最新提交；字段与官方 APK 的 FileHistory 查询保持一致。 */
@Serializable
data class WorkspaceCommit(
    val oid: String? = null,
    val message: String = "",
    val committedDate: String? = null,
    val authorName: String? = null,
    val authorLogin: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class WorkspaceCommitDraft(val branch: String, val headline: String, val body: String = "")

@Serializable data class WorkspaceFileDraft(val blob: String?, val text: String)

@Serializable
data class WorkspaceCommitHistoryPage(
    val commits: List<WorkspaceCommit>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

@Serializable
data class WorkspaceBranchesPage(
    val branches: List<String>,
    val defaultBranch: String?,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

@Serializable
data class WorkspaceEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val submodule: Boolean,
    val submoduleGitUrl: String? = null,
)

data class WorkspaceLinkTarget(val gitObject: GithubGitObject, val repositoryUrl: String)

class WorkspacesGraphQlException(errors: List<GraphQlError>) :
    Exception(
        errors.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: "Workspaces GraphQL request failed",
        GithubPageException(errors = errors),
    )

class WorkspacesTimeoutException : IOException("Workspaces request timed out")

class WorkspaceFileConflict : moe.yumeyuka.yumehub.data.cache.MutationRejectedException()

class WorkspacesFileNotFound : Exception()

/**
 * Workspaces 页面专属的 GraphQL 读取：目录条目按官方 RepoFiles 查询，提交卡与每行更新时间按 ref/path 查询。
 *
 * 每行更新时间没有批量 REST 接口，因此按字段别名把多个 `history(first: 1)` 合并到一次查询；单批失败只影响 对应行的时间，不阻塞文件列表。
 */
class WorkspacesRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val cache: CachedDataRepository,
) {
    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    /** 在同一个目标提交上检查原始 blob，再通过 expectedHeadOid 拒绝并发推送。 */
    suspend fun commitFile(
        account: String,
        owner: String,
        name: String,
        ref: String,
        path: String,
        originalBlob: String,
        text: String,
        headline: String,
        body: String,
    ): String {
        require(ref.startsWith("refs/heads/") && ref.length > 11 && isRepositoryContentPath(path))
        require(headline.isNotBlank() && headline.none { it == '\n' || it == '\r' })
        val session = GithubGraphQlSession(api, tokens, account)
        val base =
            session
                .execute(
                    """query WorkspaceEditBase(${'$'}owner:String!,${'$'}name:String!,${'$'}ref:String!,${'$'}path:String!) {
                repository(owner:${'$'}owner,name:${'$'}name) { ref(qualifiedName:${'$'}ref) {
                    target { ... on Commit { oid file(path:${'$'}path) { oid type } } }
                } }
            }""",
                    buildJsonObject {
                        put("owner", owner)
                        put("name", name)
                        put("ref", ref)
                        put("path", path)
                    },
                )
                .graphObject("repository")["ref"]
                as? JsonObject ?: throw WorkspaceFileConflict()
        val commit = base.graphObject("target")
        val entry = commit["file"] as? JsonObject
        if (entry?.graphText("oid") != originalBlob || entry.graphText("type") != "blob") {
            throw WorkspaceFileConflict()
        }
        val addition = buildJsonObject {
            put("path", path)
            put("contents", kotlin.io.encoding.Base64.Default.encode(text.toByteArray()))
        }
        val input = buildJsonObject {
            put(
                "branch",
                buildJsonObject {
                    put("repositoryNameWithOwner", "$owner/$name")
                    put("branchName", ref.removePrefix("refs/heads/"))
                },
            )
            put("expectedHeadOid", commit.graphRequiredText("oid"))
            put(
                "message",
                buildJsonObject {
                    put("headline", headline)
                    if (body.isNotBlank()) put("body", body)
                },
            )
            put("fileChanges", buildJsonObject { put("additions", JsonArray(listOf(addition))) })
        }
        return session
            .execute(
                """mutation CreateCommitOnBranch(${'$'}input:CreateCommitOnBranchInput!,${'$'}path:String!) {
                createCommitOnBranch(input:${'$'}input) { commit { oid file(path:${'$'}path) { oid } } }
            }""",
                buildJsonObject {
                    put("input", input)
                    put("path", path)
                },
                write = true,
            )
            .graphObject("createCommitOnBranch")
            .graphObject("commit")
            .graphObject("file")
            .graphRequiredText("oid")
    }

    suspend fun loadBranchChoices(
        account: String,
        owner: String,
        name: String,
        query: String,
    ): WorkspaceBranchesPage = loadBranches(account, owner, name, query = query)

    suspend fun loadBranches(
        account: String,
        owner: String,
        name: String,
        after: String? = null,
        query: String = "",
    ): WorkspaceBranchesPage =
        loadRefPage(
            account,
            owner,
            name,
            "refs/heads/",
            after,
            query,
            tokens.sessionGeneration.value,
        )

    suspend fun loadRepositoryRefs(account: String, owner: String, name: String): List<String> =
        coroutineScope {
            val generation = tokens.sessionGeneration.value
            suspend fun collectRefs(prefix: String): List<String> = buildList {
                val seen = mutableSetOf<String>()
                var after: String? = null
                do {
                    val page = loadRefPage(account, owner, name, prefix, after, "", generation)
                    addAll(page.branches)
                    after = page.endCursor?.takeIf { page.hasNextPage }
                    if (after != null && !seen.add(after)) {
                        throw SerializationException("Repeated ref cursor")
                    }
                } while (after != null)
            }
            val branches = async { collectRefs("refs/heads/") }
            val tags = collectRefs("refs/tags/")
            (branches.await() + tags).distinct()
        }

    private suspend fun loadRefPage(
        account: String,
        owner: String,
        name: String,
        prefix: String,
        after: String?,
        query: String,
        generation: Long,
    ): WorkspaceBranchesPage {
        val response =
            execute(
                account,
                GraphQlRequest(
                    query = BranchesQuery,
                    variables =
                        buildJsonObject {
                            put("owner", JsonPrimitive(owner))
                            put("name", JsonPrimitive(name))
                            put("prefix", JsonPrimitive(prefix))
                            after?.let { put("after", JsonPrimitive(it)) }
                            query.takeIf(String::isNotBlank)?.let {
                                put("query", JsonPrimitive(it))
                            }
                        },
                    operationName = "WorkspaceBranches",
                ),
                generation,
            )
        if (response.errors.isNotEmpty()) throw WorkspacesGraphQlException(response.errors)
        val repository =
            (response.data as? JsonObject)?.nested("repository")
                ?: throw SerializationException("Missing branches repository")
        val refs =
            repository.nested("refs") ?: throw SerializationException("Missing branches connection")
        val nodes =
            refs["nodes"] as? JsonArray ?: throw SerializationException("Missing branch nodes")
        val pageInfo =
            refs.nested("pageInfo") ?: throw SerializationException("Missing branches page info")
        val hasNextPage =
            pageInfo.boolean("hasNextPage")
                ?: throw SerializationException("Missing branches continuation flag")
        val endCursor = pageInfo.string("endCursor")
        if (hasNextPage && (endCursor.isNullOrBlank() || endCursor == after)) {
            throw SerializationException("Invalid branches continuation cursor")
        }
        return WorkspaceBranchesPage(
            branches =
                nodes.filterIsInstance<JsonObject>().map { branch ->
                    val branchName =
                        branch.string("name") ?: throw SerializationException("Missing branch name")
                    "$prefix$branchName"
                },
            defaultBranch =
                repository.nested("defaultBranchRef")?.string("name")?.let { "refs/heads/$it" },
            hasNextPage = hasNextPage,
            endCursor = endCursor,
        )
    }

    /** 链接只决定选中哪个 ref；目录和文件仍由工作区正常打开。 */
    suspend fun resolveGitObject(
        account: String,
        owner: String,
        name: String,
        location: GithubRepositoryLocation.GitObject,
    ): WorkspaceLinkTarget? {
        val generation = tokens.sessionGeneration.value
        val candidates = githubGitRefCandidates(location)
        if (candidates.isEmpty()) return null
        val isHead = location.refAndPath.substringBefore('/') == "HEAD"
        val probes =
            if (isHead) emptyList()
            else
                candidates.flatMap { candidate ->
                    candidate.qualifiedRefs.map { it to candidate.path }
                }
        // 深层路径不应生成一个无上限的 GraphQL 请求。
        val batches = probes.chunked(24).ifEmpty { listOf(emptyList()) }
        for (batch in batches) {
            val response =
                execute(
                    account,
                    GraphQlRequest(
                        query = refCandidatesQuery(batch.size),
                        variables =
                            buildJsonObject {
                                put("owner", JsonPrimitive(owner))
                                put("name", JsonPrimitive(name))
                                batch.forEachIndexed { index, (ref, _) ->
                                    put("ref$index", JsonPrimitive(ref))
                                }
                            },
                        operationName = "WorkspaceRefCandidates",
                    ),
                    generation,
                )
            // 探测不存在的候选可能得到 NOT_FOUND，不能因此丢弃同批里的有效对象。
            val errors = response.errors.filterNot { it.type == "NOT_FOUND" }
            if (errors.isNotEmpty()) throw WorkspacesGraphQlException(errors)
            val repository = (response.data as? JsonObject)?.nested("repository") ?: return null
            val url =
                repository.string("url") ?: throw SerializationException("Missing repository URL")
            val defaultBranch = repository.nested("defaultBranchRef")?.string("name")
            if (isHead) {
                val defaultRef = defaultBranch?.let { "refs/heads/$it" }
                return resolveGithubGitObject(location, emptyList(), defaultRef)?.let {
                    WorkspaceLinkTarget(it, url)
                }
            }
            batch.forEachIndexed { index, (ref, path) ->
                val node = repository.nested("ref$index")
                if (
                    (defaultBranch != null && ref == "refs/heads/$defaultBranch") ||
                        (node != null && node.string("prefix") + node.string("name") == ref)
                ) {
                    return WorkspaceLinkTarget(GithubGitObject(ref, path, location.anchor), url)
                }
            }
            if (batch == batches.last()) {
                return resolveGithubGitObject(location, emptyList())?.let {
                    WorkspaceLinkTarget(it, url)
                }
            }
        }
        return null
    }

    suspend fun loadDirectory(
        account: String,
        owner: String,
        name: String,
        ref: String,
        path: String,
        requireTree: Boolean = false,
    ): List<WorkspaceEntry> {
        require(path.isEmpty() || isRepositoryContentPath(path))
        val response =
            execute(
                account,
                GraphQlRequest(
                    query = WorkspaceDirectoryQuery,
                    variables =
                        buildJsonObject {
                            put("owner", JsonPrimitive(owner))
                            put("name", JsonPrimitive(name))
                            // 官方 RepoFiles 无条件拼接 `<branch>:<path>`，根目录即 `<branch>:`。
                            put(
                                "branchAndPath",
                                JsonPrimitive("$ref:$path"),
                            )
                        },
                    operationName = "WorkspaceDirectory",
                ),
            )
        if (response.errors.isNotEmpty()) throw WorkspacesGraphQlException(response.errors)
        val tree = (response.data as? JsonObject)?.nested("repository")?.nested("gitObject")
        // 空仓库根目录没有 Tree；非根路径不存在时不能显示为一个空目录。
        if ((requireTree || path.isNotEmpty()) && tree?.get("entries") !is JsonArray) {
            throw SerializationException("Missing directory tree")
        }
        val entries = tree?.get("entries") as? JsonArray ?: return emptyList()
        return withContext(Dispatchers.Default) { parseWorkspaceDirectory(entries, path) }
    }

    suspend fun loadPreview(
        account: String,
        owner: String,
        name: String,
        ref: String,
        path: String,
    ): WorkspaceFilePreview {
        require(isRepositoryContentPath(path))
        val repository =
            readFileData(
                account,
                tokens.sessionGeneration.value,
                "WorkspaceRepoFile",
                WorkspaceRepoFileQuery,
                buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                    put("ref", ref)
                    put("path", path)
                },
            )
        return withContext(Dispatchers.Default) { parseWorkspaceFilePreview(repository) }
    }

    suspend fun loadFile(
        account: String,
        owner: String,
        name: String,
        ref: String,
        path: String,
        htmlUrl: String?,
        branch: String,
    ): RepositoryTextContent {
        require(isRepositoryContentPath(path))
        val generation = tokens.sessionGeneration.value
        val repository =
            readFileData(
                account,
                generation,
                "WorkspaceBlob",
                WorkspaceBlobQuery,
                buildJsonObject {
                    put("owner", owner)
                    put("name", name)
                    put("expression", "$ref:$path")
                    put("branchPath", "$branch:$path")
                },
            )
        val blob = repository.nested("object") ?: throw WorkspacesFileNotFound()
        if (blob.string("__typename") != "Blob") {
            return RepositoryTextContent(text = null, htmlUrl = htmlUrl, path = path)
        }
        val oid = blob.string("oid") ?: throw SerializationException("Missing blob OID")
        if (repository.nested("current")?.string("oid") != oid) throw WorkspaceFileConflict()
        val size =
            blob.string("byteSize")?.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw SerializationException("Invalid blob size")
        val truncated =
            blob.boolean("isTruncated")
                ?: throw SerializationException("Missing blob truncation flag")
        val text =
            when {
                size > MaxFileBytes || blob.boolean("isBinary") == true -> null
                !truncated -> blob.graphText("text")
                else -> {
                    val rawRepository =
                        readFileData(
                            account,
                            generation,
                            "WorkspaceFileContents",
                            WorkspaceFileContentsQuery,
                            buildJsonObject {
                                put("owner", owner)
                                put("name", name)
                                put("ref", ref)
                                put("path", path)
                            },
                        )
                    val revision = rawRepository.nested("object")
                    val file =
                        (revision?.nested("target") ?: revision)?.nested("file")
                            ?: throw WorkspacesFileNotFound()
                    // 分支可能已移动；不能把另一版本的正文与这次读取的 OID 一起缓存。
                    if (revision?.string("oid") != ref) throw WorkspacesFileNotFound()
                    file.nested("fileType")?.graphText("contentRaw")
                }
            }
        val completeText =
            withContext(Dispatchers.Default) {
                text?.takeIf {
                    it.length <= size && '\u0000' !in it && it.encodeToByteArray().size == size
                }
            }
        return RepositoryTextContent(text = completeText, htmlUrl = htmlUrl, sha = oid, path = path)
    }

    private suspend fun readFileData(
        account: String,
        generation: Long,
        operation: String,
        query: String,
        variables: JsonObject,
    ): JsonObject {
        val response = execute(account, GraphQlRequest(query, variables, operation), generation)
        if (response.errors.isNotEmpty()) throw WorkspacesGraphQlException(response.errors)
        val data = response.data as? JsonObject ?: throw SerializationException("Missing file data")
        return data.nested("repository") ?: throw WorkspacesFileNotFound()
    }

    suspend fun loadCommit(
        account: String,
        owner: String,
        name: String,
        ref: String,
        path: String?,
    ): WorkspaceCommit? {
        // 根目录没有 path 过滤条件；可选参数保持不出现，避免把 null 当成空路径。
        val withPath = path != null
        val response =
            execute(
                account,
                GraphQlRequest(
                    query = workspaceCommitQuery(withPath),
                    variables =
                        buildJsonObject {
                            put("owner", JsonPrimitive(owner))
                            put("name", JsonPrimitive(name))
                            put("ref", JsonPrimitive(ref))
                            if (path != null) put("path", JsonPrimitive(path))
                        },
                    operationName = "WorkspaceCommit",
                ),
            )
        if (response.errors.isNotEmpty()) throw WorkspacesGraphQlException(response.errors)
        val objectNode =
            (response.data as? JsonObject)?.nested("repository")?.nested("object") ?: return null
        val history = objectNode.nested("history") ?: objectNode.nested("target")?.nested("history")
        val node =
            (history?.get("nodes") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val author = node.nested("author") ?: node.nested("committer")
        return WorkspaceCommit(
            oid = node.string("oid"),
            message = node.string("messageHeadline").orEmpty(),
            committedDate = node.string("committedDate"),
            authorName = author?.string("name") ?: author?.nested("user")?.string("login"),
            authorLogin = author?.nested("user")?.string("login"),
            avatarUrl = author?.string("avatarUrl"),
        )
    }

    suspend fun loadCommitHistory(
        account: String,
        owner: String,
        name: String,
        ref: String,
        after: String? = null,
    ): WorkspaceCommitHistoryPage {
        val response =
            execute(
                account,
                GraphQlRequest(
                    query = CommitHistoryQuery,
                    variables =
                        buildJsonObject {
                            put("owner", JsonPrimitive(owner))
                            put("name", JsonPrimitive(name))
                            put("ref", JsonPrimitive(ref))
                            after?.let { put("after", JsonPrimitive(it)) }
                        },
                    operationName = "WorkspaceCommitHistory",
                ),
            )
        if (response.errors.isNotEmpty()) throw WorkspacesGraphQlException(response.errors)
        val history =
            (response.data as? JsonObject)
                ?.nested("repository")
                ?.nested("object")
                ?.nested("history") ?: return WorkspaceCommitHistoryPage(emptyList(), false, null)
        val commits =
            (history["nodes"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .mapNotNull(::parseCommit)
        val pageInfo = history.nested("pageInfo")
        return WorkspaceCommitHistoryPage(
            commits = commits,
            hasNextPage = pageInfo?.boolean("hasNextPage") == true,
            endCursor = pageInfo?.string("endCursor"),
        )
    }

    /** 返回 path 到最近提交时间的映射；失败或没有历史的路径不出现在结果里。 */
    suspend fun loadUpdatedAt(
        account: String,
        owner: String,
        name: String,
        ref: String,
        paths: List<String>,
    ): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val generation = tokens.sessionGeneration.value
        val result = mutableMapOf<String, String>()
        paths.chunked(UpdatedAtBatchSize).forEach { batch ->
            val request =
                GraphQlRequest(
                    query = updatedAtQuery(batch.size),
                    variables =
                        buildJsonObject {
                            put("owner", JsonPrimitive(owner))
                            put("name", JsonPrimitive(name))
                            put("ref", JsonPrimitive(ref))
                            batch.forEachIndexed { index, path ->
                                put("p$index", JsonPrimitive(path))
                            }
                        },
                    operationName = "WorkspaceUpdated",
                )
            val response =
                try {
                    execute(account, request, generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 单批失败时保留其他批次和文件列表。
                    return@forEach
                }
            val commit = (response.data as? JsonObject)?.nested("repository")?.nested("object")
            val target = commit?.nested("target")
            batch.forEachIndexed { index, path ->
                val date =
                    (((commit?.nested("p$index")?.get("nodes") as? JsonArray)?.firstOrNull()
                            as? JsonObject)
                            ?: ((target?.nested("t$index")?.get("nodes") as? JsonArray)
                                ?.firstOrNull() as? JsonObject))
                        ?.string("committedDate")
                if (date != null) result[path] = date
            }
        }
        return result
    }

    private suspend fun execute(
        account: String,
        request: GraphQlRequest,
        generation: Long = tokens.sessionGeneration.value,
    ): GraphQlResponse {
        val session = tokens.responseCacheSession
        currentCoroutineContext().ensureActive()
        val response =
            withTimeoutOrNull(RequestTimeoutMillis) {
                api.execute(request, headers = tokens.authorization(account, generation))
            } ?: throw WorkspacesTimeoutException()
        currentCoroutineContext().ensureActive()
        synchronized(session.lock) {
            checkAccount(account)
            if (generation != session.generation) {
                throw CancellationException("Workspaces session changed")
            }
        }
        return response
    }

    private fun checkAccount(account: String) {
        if (account.isBlank() || !tokens.accountLogin.value.equals(account, ignoreCase = true)) {
            throw CancellationException("Workspaces account changed")
        }
    }

    private fun refCandidatesQuery(size: Int): String {
        val variables = (0 until size).joinToString("\n") { index -> "${'$'}ref$index: String!" }
        val fields =
            (0 until size).joinToString("\n") { index ->
                "    ref$index: ref(qualifiedName: ${'$'}ref$index) { prefix name }"
            }
        return """
            query WorkspaceRefCandidates(
              ${'$'}owner: String!
              ${'$'}name: String!
              $variables
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                url
                defaultBranchRef { name }
            $fields
              }
            }
            """
            .trimIndent()
    }

    /** 标签的 object 可能返回 Tag，查询同时覆盖 Tag.target 上的 Commit。 */
    private fun updatedAtQuery(size: Int): String {
        val variables = (0 until size).joinToString(", ") { "${'$'}p$it: String" }
        val commitFields =
            (0 until size).joinToString("\n") {
                "        p$it: history(first: 1, path: ${'$'}p$it) { nodes { committedDate } }"
            }
        // 响应名不能跨分支复用，Tag.target 用独立别名。
        val targetFields =
            (0 until size).joinToString("\n") {
                "            t$it: history(first: 1, path: ${'$'}p$it) { nodes { committedDate } }"
            }
        return buildString {
            appendLine("query WorkspaceUpdated(")
            appendLine("  ${'$'}owner: String!")
            appendLine("  ${'$'}name: String!")
            appendLine("  ${'$'}ref: String!")
            appendLine("  $variables")
            appendLine(") {")
            appendLine("  repository(owner: ${'$'}owner, name: ${'$'}name) {")
            appendLine("    object(expression: ${'$'}ref) {")
            appendLine("      __typename")
            appendLine("      ... on Commit {")
            appendLine(commitFields)
            append("      }")
            appendLine()
            appendLine("      ... on Tag {")
            appendLine("        target {")
            appendLine("          __typename")
            appendLine("          ... on Commit {")
            appendLine(targetFields)
            appendLine("          }")
            appendLine("        }")
            append("      }")
            appendLine()
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }

    private companion object {
        const val RequestTimeoutMillis = 20_000L
        const val UpdatedAtBatchSize = 20
        const val MaxFileBytes = 4 * 1024 * 1024

        val WorkspaceRepoFileQuery =
            """
            query WorkspaceRepoFile(${'$'}owner: String!, ${'$'}name: String!, ${'$'}ref: String!, ${'$'}path: String!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                object(expression: ${'$'}ref) {
                  ...WorkspacePreviewAtCommit
                  ... on Tag { target { ...WorkspacePreviewAtCommit } }
                }
              }
            }
            fragment WorkspacePreviewAtCommit on Commit {
              oid
              file(path: ${'$'}path) {
                fileType {
                  __typename
                  ... on TextFileType { fileLines { number html } }
                  ... on MarkdownFileType { contentHTML fileLines { number html } }
                }
              }
            }
            """
                .trimIndent()

        val WorkspaceBlobQuery =
            """
            query WorkspaceBlob(
              ${'$'}owner: String!, ${'$'}name: String!, ${'$'}expression: String!, ${'$'}branchPath: String!
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                current: object(expression: ${'$'}branchPath) { oid }
                object(expression: ${'$'}expression) {
                  __typename
                  ... on Blob { oid byteSize isBinary isTruncated text }
                }
              }
            }
            """
                .trimIndent()

        val WorkspaceFileContentsQuery =
            """
            query WorkspaceFileContents(
              ${'$'}owner: String!, ${'$'}name: String!, ${'$'}ref: String!, ${'$'}path: String!
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                object(expression: ${'$'}ref) {
                  ...WorkspaceFileAtCommit
                  ... on Tag { target { ...WorkspaceFileAtCommit } }
                }
              }
            }
            fragment WorkspaceFileAtCommit on Commit {
              oid
              file(path: ${'$'}path) {
                fileType {
                  ... on MarkdownFileType { contentRaw }
                  ... on TextFileType { contentRaw }
                }
              }
            }
            """
                .trimIndent()

        val CommitHistorySelection =
            """
            history(first: 1, path: ${'$'}path) {
              nodes {
                oid
                committedDate
                messageHeadline
                author { name avatarUrl user { login } }
                committer { name avatarUrl user { login } }
              }
            }
            """
                .trimIndent()

        // 对齐官方 RepositoryBranches：首屏 50 条，搜索与续页均由服务端处理。
        val BranchesQuery =
            """
            query WorkspaceBranches(
              ${'$'}owner: String!
              ${'$'}name: String!
              ${'$'}prefix: String!
              ${'$'}after: String
              ${'$'}query: String
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                defaultBranchRef { name }
                refs(first: 50, after: ${'$'}after, refPrefix: ${'$'}prefix, query: ${'$'}query) {
                  pageInfo { hasNextPage endCursor }
                  nodes { name }
                }
              }
            }
            """
                .trimIndent()

        val CommitHistoryQuery =
            """
            query WorkspaceCommitHistory(
              ${'$'}owner: String!
              ${'$'}name: String!
              ${'$'}ref: String!
              ${'$'}after: String
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                object(expression: ${'$'}ref) {
                  __typename
                  ... on Commit {
                    history(first: 30, after: ${'$'}after) {
                      nodes {
                        oid
                        committedDate
                        messageHeadline
                        author { name avatarUrl user { login } }
                        committer { name avatarUrl user { login } }
                      }
                      pageInfo { hasNextPage endCursor }
                    }
                  }
                }
              }
            }
            """
                .trimIndent()

        val WorkspaceDirectoryQuery =
            """
            query WorkspaceDirectory(
              ${'$'}owner: String!
              ${'$'}name: String!
              ${'$'}branchAndPath: String!
            ) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                gitObject: object(expression: ${'$'}branchAndPath) {
                  __typename
                  ... on Tree {
                    entries {
                      name
                      type
                      mode
                      submodule { gitUrl }
                    }
                    id
                  }
                }
              }
            }
            """
                .trimIndent()
    }

    private fun workspaceCommitQuery(withPath: Boolean): String {
        val selection =
            if (withPath) CommitHistorySelection
            else
                CommitHistorySelection.replace(
                    "history(first: 1, path: \$path)",
                    "history(first: 1)",
                )
        return buildString {
            appendLine("query WorkspaceCommit(")
            appendLine("  ${'$'}owner: String!")
            appendLine("  ${'$'}name: String!")
            appendLine("  ${'$'}ref: String!")
            if (withPath) appendLine("  ${'$'}path: String!")
            appendLine(") {")
            appendLine("  repository(owner: ${'$'}owner, name: ${'$'}name) {")
            appendLine("    object(expression: ${'$'}ref) {")
            appendLine("      __typename")
            appendLine("      ... on Commit {")
            appendLine(selection.prependIndent("        "))
            append("      }")
            appendLine()
            appendLine("      ... on Tag {")
            appendLine("        target {")
            appendLine("          __typename")
            appendLine("          ... on Commit {")
            appendLine(selection.prependIndent("            "))
            appendLine("          }")
            appendLine("        }")
            append("      }")
            appendLine()
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
    }

    private fun parseCommit(node: JsonObject): WorkspaceCommit? {
        val oid = node.string("oid") ?: return null
        val author = node.nested("author")
        val committer = node.nested("committer")
        val identity = author ?: committer
        return WorkspaceCommit(
            oid = oid,
            message = node.string("messageHeadline").orEmpty(),
            committedDate = node.string("committedDate"),
            authorName = identity?.string("name") ?: identity?.nested("user")?.string("login"),
            authorLogin = identity?.nested("user")?.string("login"),
            avatarUrl = identity?.string("avatarUrl"),
        )
    }
}

internal fun parseWorkspaceDirectory(entries: JsonArray, path: String): List<WorkspaceEntry> {
    val names = mutableSetOf<String>()
    val rows = ArrayList<Pair<String, WorkspaceEntry>>(entries.size)
    for (element in entries) {
        val entry = element as? JsonObject ?: continue
        val name = entry.string("name") ?: continue
        if (!names.add(name)) continue
        val submodule = entry.nested("submodule")?.string("gitUrl")
        val row =
            WorkspaceEntry(
                name = name,
                path = if (path.isEmpty()) name else "$path/$name",
                directory = entry.string("type") == "tree",
                submodule = submodule != null,
                submoduleGitUrl = submodule,
            )
        rows.add(name.lowercase(java.util.Locale.ROOT) to row)
    }
    rows.sortWith(compareBy({ !it.second.directory }, { it.first }))
    return rows.map { it.second }
}

private fun JsonObject.nested(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
