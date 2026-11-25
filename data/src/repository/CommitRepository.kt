package moe.yumeyuka.yumehub.data.repository

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

@Serializable
data class GithubCommitDetail(
    val oid: String,
    val title: String,
    val bodyHtml: String?,
    val url: String,
    val author: String?,
    val avatarUrl: String?,
    val committedDate: String,
    val message: String,
)

class CommitRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val paging: PagingRepository,
    private val diffs: GithubDiffRepository,
    private val cache: CachedDataRepository,
) {
    suspend fun load(
        account: String,
        owner: String,
        name: String,
        ref: String,
    ): GithubCommitDetail =
        cache
            .resource(
                account,
                "commit-detail.v1:${owner.lowercase(Locale.ROOT)}/${name.lowercase(Locale.ROOT)}:$ref",
                GithubCommitDetail.serializer(),
                maxAgeMillis = if (FullCommitOid.matches(ref)) StableCommitAgeMillis else 60_000L,
            )
            .await { loadNetwork(account, owner, name, ref) }

    private suspend fun loadNetwork(
        account: String,
        owner: String,
        name: String,
        ref: String,
    ): GithubCommitDetail {
        require(CommitRef.matches(ref))
        val session = GithubGraphQlSession(api, tokens, account)
        val data =
            session.execute(
                """
                query CommitDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}oid: String!) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) {
                        object(expression: ${'$'}oid) { ... on Commit {
                            oid message messageBodyHTML url committedDate author { name avatarUrl user { login } }
                        } }
                    }
                }
                """
                    .trimIndent(),
                variables(owner, name, ref),
            )
        val commit = data.graphObject("repository").graphObject("object")
        val oid = commit.graphRequiredText("oid")
        if (!oid.startsWith(ref, ignoreCase = true)) throw GithubPageException()
        val author = commit["author"] as? JsonObject
        return GithubCommitDetail(
            oid,
            commit.graphRequiredText("message").lineSequence().first(),
            commit.graphText("messageBodyHTML"),
            commit.graphRequiredText("url"),
            (author?.get("user") as? JsonObject)?.graphText("login") ?: author?.graphText("name"),
            author?.graphText("avatarUrl"),
            commit.graphRequiredText("committedDate"),
            message = commit.graphRequiredText("message"),
        )
    }

    fun files(account: String, owner: String, name: String, oid: String) =
        paging.pages(account, "commit-files.v1:$owner/$name:$oid") { context ->
            val load = diffs.createCommitPageLoader(account, owner, name, oid)
            PullRequestDiffPagingSource(context) { after ->
                if (!FullCommitOid.matches(oid)) load(after)
                else
                    context.await(
                        key = "diff-page:${after.orEmpty()}",
                        serializer = GithubDiffPage.serializer(),
                        maxAgeMillis = StableCommitAgeMillis,
                    ) {
                        load(after)
                    }
            }
        }

    fun links(account: String, owner: String, name: String, oid: String, parents: Boolean) =
        githubGraphQlPager(
            account,
            "commit-links.v3:$owner/$name:$oid:$parents",
            paging,
            RepositoryActivityEntry::id,
            serializer = RepositoryActivityEntry.serializer(),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val field = if (parents) "parents" else "associatedPullRequests"
            val fields =
                if (parents) {
                    "oid message url committedDate author { name avatarUrl user { login } }"
                } else "id number title headRefName url state isDraft"
            val query =
                """
                query CommitLinks(${'$'}owner: String!, ${'$'}name: String!, ${'$'}oid: String!, ${'$'}after: String) {
                    repository(owner: ${'$'}owner, name: ${'$'}name) { object(expression: ${'$'}oid) { ... on Commit {
                        oid $field(first: 30, after: ${'$'}after) {
                            pageInfo { hasNextPage endCursor } nodes { $fields }
                        }
                    } } }
                }
            """
                    .trimIndent()
            val load: suspend (String?) -> GithubGraphQlPage<RepositoryActivityEntry> = { after ->
                val data = session.execute(query, variables(owner, name, oid, after))
                val commit = data.graphObject("repository").graphObject("object")
                if (commit.graphRequiredText("oid") != oid) throw GithubPageException()
                val connection = commit.graphObject(field)
                GithubGraphQlPage(
                    connection.graphNodes().map {
                        val author = if (parents) it["author"] as? JsonObject else null
                        RepositoryActivityEntry(
                            id = it.graphRequiredText(if (parents) "oid" else "id"),
                            title =
                                if (parents) it.graphRequiredText("message").lineSequence().first()
                                else it.graphRequiredText("title"),
                            subtitle =
                                if (parents) it.graphRequiredText("oid").take(7)
                                else it.graphRequiredText("headRefName"),
                            url = it.graphRequiredText("url"),
                            state =
                                if (
                                    !parents &&
                                        it.graphText("state") == "OPEN" &&
                                        it["isDraft"]?.jsonPrimitive?.booleanOrNull == true
                                )
                                    "DRAFT"
                                else it.graphText("state"),
                            login =
                                (author?.get("user") as? JsonObject)?.graphText("login")
                                    ?: author?.graphText("name"),
                            avatarUrl = author?.graphText("avatarUrl"),
                            timestamp =
                                if (parents) it.graphRequiredText("committedDate") else null,
                            number =
                                if (parents) null
                                else
                                    (it["number"]?.jsonPrimitive?.intOrNull
                                        ?: throw GithubPageException()),
                        )
                    },
                    connection.graphCursor(after),
                )
            }
            load
        }

    private fun variables(owner: String, name: String, oid: String, after: String? = null) =
        buildJsonObject {
            put("owner", owner)
            put("name", name)
            put("oid", oid)
            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
        }
}

private val CommitRef = Regex("[a-fA-F0-9]{7,40}")
private val FullCommitOid = Regex("[a-fA-F0-9]{40}")
private const val StableCommitAgeMillis = 24 * 60 * 60 * 1_000L
