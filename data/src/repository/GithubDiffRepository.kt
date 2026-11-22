package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffLineDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffLineType
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffUnavailableReason
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

class GithubDiffRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
) {
    internal fun createCommitPageLoader(
        account: String,
        owner: String,
        repository: String,
        oid: String,
    ): suspend (String?) -> GithubDiffPage {
        val session = session(account)
        return { after ->
            val data =
                execute(
                    session,
                    CommitFilesQuery,
                    buildJsonObject {
                        put("owner", owner)
                        put("repository", repository)
                        put("oid", oid)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            val commit = data.requiredObject("repository").requiredObject("object")
            if (commit.requiredText("oid") != oid) throw GithubDiffException()
            val page =
                withContext(Dispatchers.Default) {
                    parseGithubDiffPage(commit.requiredObject("diff"), after)
                }
            if (page.headCommitOid != oid) throw GithubDiffException()
            checkSession(session)
            page
        }
    }

    internal fun createPullRequestPageLoader(
        account: String,
        owner: String,
        repository: String,
        number: Int,
        head: String,
    ): suspend (String?) -> GithubDiffPage {
        val session = session(account)
        return { after ->
            val data =
                execute(
                    session,
                    PullRequestFilesQuery,
                    buildJsonObject {
                        put("owner", owner)
                        put("repository", repository)
                        put("number", number)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            val request = data.requiredObject("repository").requiredObject("issueOrPullRequest")
            if (request.text("__typename") != "PullRequest" || request.text("headRefOid") != head) {
                throw WorkDetailReviewChangedException()
            }
            val page =
                withContext(Dispatchers.Default) {
                    parseGithubDiffPage(request.requiredObject("diff"), after)
                }
            if (page.headCommitOid != head) throw WorkDetailReviewChangedException()
            checkSession(session)
            page
        }
    }

    private fun session(account: String): Session =
        synchronized(tokens.responseCacheSession.lock) {
            Session(account, tokens.responseCacheSession.generation, tokens.accessToken.value)
                .also(::checkSession)
        }

    private fun checkSession(session: Session) =
        synchronized(tokens.responseCacheSession.lock) {
            if (
                session.account.isBlank() ||
                    session.token.isBlank() ||
                    !tokens.accountLogin.value.equals(session.account, true) ||
                    tokens.responseCacheSession.generation != session.generation
            )
                throw CancellationException("Diff session changed")
        }

    private suspend fun execute(
        session: Session,
        query: String,
        variables: JsonObject,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        checkSession(session)
        val response = api.execute(GraphQlRequest(query, variables))
        currentCoroutineContext().ensureActive()
        checkSession(session)
        if (response.errors.isNotEmpty()) throw GithubDiffException()
        return response.data as? JsonObject ?: throw GithubDiffException()
    }

    private data class Session(val account: String, val generation: Long, val token: String)
}

@Serializable
internal data class GithubDiffPage(
    val files: List<PullRequestFileDto>,
    val baseCommitOid: String,
    val headCommitOid: String,
    val nextCursor: String?,
)

class GithubDiffException : Exception()

internal const val MaxDiffPages = 1_000
private const val MaxPageCharacters = 2_000_000L
private const val MaxPageLines = 30_000

internal fun parseGithubDiffPage(diff: JsonObject, after: String?): GithubDiffPage {
    val base = diff.requiredText("baseCommitOid")
    val head = diff.requiredText("headCommitOid")
    val patches = diff.requiredObject("patches")
    val info = patches.requiredObject("pageInfo")
    val next =
        if (info.requiredFlag("hasNextPage")) {
            info.requiredText("endCursor").also { if (it == after) throw GithubDiffException() }
        } else null
    val nodes = patches["nodes"] as? JsonArray ?: throw GithubDiffException()
    if (nodes.size > 50) throw GithubDiffException()
    var characters = 0L
    var lineCount = 0L
    val identities = mutableSetOf<String>()
    val paths = mutableSetOf<String>()
    val files = nodes.map { node ->
        val patch = node as? JsonObject ?: throw GithubDiffException()
        val lines = patch["diffLines"] as? JsonArray
        lineCount += lines?.size?.toLong() ?: 0L
        characters +=
            lines?.sumOf { (it as? JsonObject)?.text("text")?.length?.toLong() ?: 0L } ?: 0L
        if (lineCount > MaxPageLines || characters > MaxPageCharacters) throw GithubDiffException()
        val file = parseDiffFile(patch, base, head)
        val identity = file.structuredDiff?.id ?: throw GithubDiffException()
        if (!identities.add(identity) || !paths.add(file.filename)) {
            throw GithubDiffException()
        }
        file
    }
    return GithubDiffPage(files, base, head, next)
}

private fun parseDiffFile(patch: JsonObject, base: String, head: String): PullRequestFileDto {
    val oldPath = patch.treePath("oldTreeEntry")
    val newPath = patch.treePath("newTreeEntry")
    val path = newPath ?: oldPath ?: throw GithubDiffException()
    val binary = patch.requiredFlag("isBinary")
    val large = patch.requiredFlag("isLargeDiff")
    val submodule = patch.requiredFlag("isSubmodule")
    val additions = patch.requiredNumber("linesAdded")
    val deletions = patch.requiredNumber("linesDeleted")
    val rawLines = patch["diffLines"] as? JsonArray
    var unavailable =
        when {
            binary -> StructuredDiffUnavailableReason.Binary
            large -> StructuredDiffUnavailableReason.LargeDiff
            submodule -> StructuredDiffUnavailableReason.Submodule
            rawLines == null -> StructuredDiffUnavailableReason.MissingLines
            else -> null
        }
    val lines = mutableListOf<StructuredDiffLineDto>()
    if (unavailable == null && rawLines != null) {
        for (rawLine in rawLines) {
            val line = rawLine as? JsonObject
            val type =
                when (line?.text("type")) {
                    "ADDITION" -> StructuredDiffLineType.Addition
                    "CONTEXT" -> StructuredDiffLineType.Context
                    "DELETION" -> StructuredDiffLineType.Deletion
                    "HUNK" -> StructuredDiffLineType.Hunk
                    // INJECTED_CONTEXT 的定位语义未获证明，不能当普通上下文使用。
                    else -> null
                }
            if (type == null || line == null) {
                unavailable = StructuredDiffUnavailableReason.UnsupportedLineType
                break
            }
            val text = line.text("text")
            val left = line.optionalNumber("left")
            val right = line.optionalNumber("right")
            val missingNewline = (line["isMissingNewlineAtEnd"] as? JsonPrimitive)?.booleanOrNull
            val validNumbers =
                when (type) {
                    StructuredDiffLineType.Addition -> right != null && right > 0
                    StructuredDiffLineType.Deletion -> left != null && left > 0
                    StructuredDiffLineType.Context ->
                        left != null && left > 0 && right != null && right > 0
                    StructuredDiffLineType.Hunk -> true
                }
            if (
                text == null ||
                    missingNewline == null ||
                    !validNumbers ||
                    (left != null && left < 0) ||
                    (right != null && right < 0)
            ) {
                unavailable = StructuredDiffUnavailableReason.InvalidLines
                break
            }
            lines.add(StructuredDiffLineDto(type, text, left, right, missingNewline))
        }
    }
    if (
        unavailable == null &&
            (lines.count { it.type == StructuredDiffLineType.Addition } != additions ||
                lines.count { it.type == StructuredDiffLineType.Deletion } != deletions)
    )
        unavailable = StructuredDiffUnavailableReason.InvalidLines
    val status =
        when (patch.requiredText("status")) {
            "ADDED" -> "added"
            "CHANGED" -> "changed"
            "COPIED" -> "copied"
            "DELETED" -> "removed"
            "MODIFIED" -> "modified"
            "RENAMED" -> "renamed"
            else -> throw GithubDiffException()
        }
    return PullRequestFileDto(
        filename = path,
        status = status,
        additions = additions,
        deletions = deletions,
        previousFilename = oldPath?.takeIf { it != path },
        structuredDiff =
            StructuredDiffDto(
                id = patch.requiredText("id"),
                baseCommitOid = base,
                headCommitOid = head,
                oldPath = oldPath,
                newPath = newPath,
                isBinary = binary,
                isLargeDiff = large,
                isSubmodule = submodule,
                lines = if (unavailable == null) lines else emptyList(),
                unavailableReason = unavailable,
            ),
    )
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.requiredText(key: String): String =
    text(key)?.takeIf(String::isNotBlank) ?: throw GithubDiffException()

private fun JsonObject.requiredObject(key: String): JsonObject =
    get(key) as? JsonObject ?: throw GithubDiffException()

private fun JsonObject.requiredFlag(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: throw GithubDiffException()

private fun JsonObject.optionalNumber(key: String): Int? {
    val value = get(key)
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.intOrNull ?: throw GithubDiffException()
}

private fun JsonObject.requiredNumber(key: String): Int =
    optionalNumber(key)?.takeIf { it >= 0 } ?: throw GithubDiffException()

private fun JsonObject.treePath(key: String): String? {
    val entry = get(key)
    if (entry == null || entry == JsonNull) return null
    return (entry as? JsonObject)?.requiredText("path") ?: throw GithubDiffException()
}

private val PatchFields =
    """
    id linesAdded linesDeleted status isBinary isLargeDiff isSubmodule
    oldTreeEntry { path }
    newTreeEntry { path }
    diffLines { type text left right isMissingNewlineAtEnd }
    """
        .trimIndent()

private val PullRequestFilesQuery =
    """
    query PullRequestDiffFiles(${'$'}owner: String!, ${'$'}repository: String!, ${'$'}number: Int!, ${'$'}after: String) {
        repository(owner: ${'$'}owner, name: ${'$'}repository) {
            issueOrPullRequest(number: ${'$'}number) {
                __typename
                ... on PullRequest {
                    headRefOid
                    diff {
                        baseCommitOid headCommitOid
                        patches(first: 50, after: ${'$'}after) {
                            pageInfo { endCursor hasNextPage }
                            nodes { $PatchFields }
                        }
                    }
                }
            }
        }
    }
"""
        .trimIndent()

private val CommitFilesQuery =
    """
    query CommitDiffFiles(${'$'}owner: String!, ${'$'}repository: String!, ${'$'}oid: String!, ${'$'}after: String) {
        repository(owner: ${'$'}owner, name: ${'$'}repository) {
            object(expression: ${'$'}oid) { ... on Commit {
                oid diff {
                    baseCommitOid headCommitOid
                    patches(first: 50, after: ${'$'}after) {
                        pageInfo { endCursor hasNextPage }
                        nodes { $PatchFields }
                    }
                }
            } }
        }
    }
"""
        .trimIndent()
