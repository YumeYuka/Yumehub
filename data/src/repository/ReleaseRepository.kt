package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.core.model.githubWebUri
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedDataState
import moe.yumeyuka.yumehub.data.cache.RemoteFetch
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.repositoryCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

@Serializable
data class GithubReleaseVersion(
    val id: String,
    val name: String?,
    val tag: String,
    val date: String,
    val latest: Boolean,
    val prerelease: Boolean,
    val draft: Boolean,
) {
    val title: String?
        get() = name?.takeIf(String::isNotBlank) ?: tag.takeIf(String::isNotBlank)
}

@Serializable
data class GithubReleaseDetail(
    val version: GithubReleaseVersion,
    val url: String,
    val repositoryUrl: String,
    val author: String?,
    val authorAvatarUrl: String?,
    val hasTag: Boolean,
    val html: String?,
    val immutable: Boolean,
    val canReact: Boolean,
    val reactions: List<GithubReactionGroup>,
)

@Serializable
data class GithubReleaseAsset(
    val id: String,
    val name: String,
    val size: Long,
    val url: String,
    val downloadCount: Int,
    val digest: String?,
)

// 包装允许「没有发行版」作为有效的缓存结果。
@Serializable data class GithubReleaseResult(val detail: GithubReleaseDetail?)

class ReleaseRepository(
    private val api: GitHubGraphQlApi,
    private val tokens: TokenStore,
    private val cache: CachedDataRepository,
    private val paging: PagingRepository,
) {
    fun observe(
        account: String,
        owner: String,
        name: String,
        tag: String?,
        versionId: String?,
    ): Flow<CachedDataState<GithubReleaseResult>> =
        if (versionId != null) flow {
            val routeResource = releaseResource(account, owner, name, tag)
            if (routeResource.cached?.detail?.version?.id == versionId) {
                emitAll(observe(account, owner, name, tag, versionId = null).takeWhile {
                    it.data?.detail?.version?.id == versionId || it.error?.invalidatesCachedContent() == true
                })
            }
            emitAll(versionResource(account, versionId).observe { RemoteFetch.Fresh(readVersion(account, versionId)) })
        } else releaseResource(account, owner, name, tag).observe {
                val data =
                    GithubGraphQlSession(api, tokens, account)
                        .execute(
                            if (tag == null) LatestReleaseQuery else TaggedReleaseQuery,
                            buildJsonObject {
                                put("owner", owner)
                                put("name", name)
                                tag?.let { put("tag", it) }
                            },
                        )
                val repo = data.graphObject("repository")
                val release =
                    if (tag == null) {
                        repo.nullableRelease("latestRelease")
                            ?: repo.graphObject("releases").graphNodes().firstOrNull()
                    } else {
                        repo.nullableRelease("release")
                    }
                RemoteFetch.Fresh(GithubReleaseResult(release?.releaseDetail()))
            }

    suspend fun readVersion(account: String, id: String): GithubReleaseResult {
            val data =
                GithubGraphQlSession(api, tokens, account)
                    .execute(ReleaseVersionQuery, buildJsonObject { put("id", id) })
            val node = data.nullableRelease("node")
            if (node != null && node.graphRequiredText("id") != id) throw GithubPageException()
            return GithubReleaseResult(node?.releaseDetail())
        }

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun reactionUpdates(account: String): GithubReactionUpdates = cache.reactionUpdates(account)

    suspend fun toggleReaction(
        account: String,
        owner: String,
        name: String,
        release: GithubReleaseDetail,
        content: GithubReactionContent,
        reacted: Boolean,
    ): List<GithubReactionGroup> {
        val id = release.version.id
        val session = GithubGraphQlSession(api, tokens, account)
        val version = versionResource(account, id)
        val tagged = release.version.tag.takeIf(String::isNotBlank)?.let { tag ->
            releaseResource(account, owner, name, tag)
        }
        val latest = releaseResource(account, owner, name, null)
        val reactions =
            session.toggleGithubReaction(id, content, reacted)
        val updatedVersion = (version.cached?.detail ?: release).copy(reactions = reactions)
        version.update(GithubReleaseResult(updatedVersion))
        listOfNotNull(tagged, latest).forEach { resource ->
            val current = resource.cached?.detail
            if (current != null && current.version.id == id) {
                resource.update(GithubReleaseResult(current.copy(reactions = reactions)))
            } else {
                resource.invalidate()
            }
        }
        return reactions
    }

    private fun releaseResource(account: String, owner: String, name: String, tag: String?) =
        cache.resource(
            account,
            "release.v5:${owner.lowercase()}/${name.lowercase()}:${tag.orEmpty()}",
            GithubReleaseResult.serializer(),
            tags = setOf(repositoryCacheTag(owner, name)),
        )

    private fun versionResource(account: String, id: String) =
        cache.resource(
            account,
            "release-node.v5:$id",
            GithubReleaseResult.serializer(),
            tags = setOf(CacheTag(DataDomain.Content, null)),
        )

    fun assetsKey(id: String): String = "release-assets.v1:$id"

    fun versionsKey(owner: String, name: String): String =
        "release-timeline.v1:${owner.lowercase()}/${name.lowercase()}"

    fun revalidation(account: String, key: String) = paging.revalidation(account, key)

    fun retryRevalidation(account: String, key: String) = paging.retryRevalidation(account, key)

    fun assets(account: String, id: String) =
        githubGraphQlPager(
            account,
            assetsKey(id),
            paging,
            GithubReleaseAsset::id,
            serializer = GithubReleaseAsset.serializer(),
            tags = setOf(CacheTag(DataDomain.Content, null)),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val load: suspend (String?) -> GithubGraphQlPage<GithubReleaseAsset> = { after ->
                val data =
                    session.execute(
                        ReleaseAssetsQuery,
                        buildJsonObject {
                            put("id", id)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                val assets = data.graphObject("node").graphObject("releaseAssets")
                GithubGraphQlPage(
                    assets.graphNodes().map { it.releaseAsset() },
                    assets.graphCursor(after),
                )
            }
            load
        }

    fun versions(account: String, owner: String, name: String) =
        githubGraphQlPager(
            account,
            versionsKey(owner, name),
            paging,
            GithubReleaseVersion::id,
            serializer = GithubReleaseVersion.serializer(),
            tags = setOf(repositoryCacheTag(owner, name)),
        ) {
            val session = GithubGraphQlSession(api, tokens, account)
            val load: suspend (String?) -> GithubGraphQlPage<GithubReleaseVersion> = { after ->
                val data =
                    session.execute(
                        ReleaseTimelineQuery,
                        buildJsonObject {
                            put("owner", owner)
                            put("name", name)
                            put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                val releases = data.graphObject("repository").graphObject("releases")
                GithubGraphQlPage(
                    releases.graphNodes().map { it.releaseVersion() },
                    releases.graphCursor(after),
                )
            }
            load
        }
}

private fun JsonObject.nullableRelease(key: String): JsonObject? {
    val value = get(key)
    return if (value == JsonNull) null else value as? JsonObject ?: throw GithubPageException()
}

private fun JsonObject.releaseFlag(key: String): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: throw GithubPageException()

private fun JsonObject.releaseVersion() =
    GithubReleaseVersion(
        id = graphRequiredText("id"),
        name = graphText("name"),
        tag = graphText("tagName") ?: throw GithubPageException(),
        date = graphText("publishedAt") ?: graphRequiredText("createdAt"),
        latest = releaseFlag("isLatest"),
        prerelease = releaseFlag("isPrerelease"),
        draft = releaseFlag("isDraft"),
    )

private fun JsonObject.releaseDetail() =
    GithubReleaseDetail(
        version = releaseVersion(),
        url = graphRequiredText("url"),
        repositoryUrl = graphObject("repository").graphRequiredText("url"),
        author = (get("author") as? JsonObject)?.graphText("login"),
        authorAvatarUrl = (get("author") as? JsonObject)?.graphText("avatarUrl"),
        hasTag = nullableRelease("tag") != null,
        html = graphText("descriptionHTML"),
        immutable = releaseFlag("immutable"),
        canReact = releaseFlag("viewerCanReact"),
        reactions = if (get("reactionGroups") == JsonNull) emptyList() else reactionGroups(),
    )

private fun JsonObject.releaseAsset() =
    GithubReleaseAsset(
        id = graphRequiredText("id"),
        name = graphRequiredText("name"),
        size =
            (get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                ?: throw GithubPageException(),
        url =
            githubWebUri(graphRequiredText("downloadUrl"))?.toASCIIString()
                ?: throw GithubPageException(),
        downloadCount =
            (get("downloadCount") as? JsonPrimitive)?.intOrNull ?: throw GithubPageException(),
        digest = graphText("digest")?.takeIf(String::isNotBlank),
    )

private const val VersionFields =
    "id name tagName createdAt publishedAt isLatest isPrerelease isDraft"
private const val DetailFields =
    "$VersionFields immutable descriptionHTML url author { login avatarUrl } " +
        "repository { url } " +
        "tag { id } viewerCanReact $ReactionFragment"
private const val NewestFirst = "{field: CREATED_AT, direction: DESC}"

private val LatestReleaseQuery =
    """
    query LatestRelease(${'$'}owner: String!, ${'$'}name: String!) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        latestRelease { $DetailFields }
        releases(first: 1, orderBy: $NewestFirst) { nodes { $DetailFields } }
      }
    }
"""
        .trimIndent()

private val TaggedReleaseQuery =
    """
    query TaggedRelease(${'$'}owner: String!, ${'$'}name: String!, ${'$'}tag: String!) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        release(tagName: ${'$'}tag) { $DetailFields }
      }
    }
"""
        .trimIndent()

private val ReleaseVersionQuery =
    """
    query ReleaseVersion(${'$'}id: ID!) {
      node(id: ${'$'}id) { ... on Release { $DetailFields } }
    }
"""
        .trimIndent()

private val ReleaseTimelineQuery =
    """
    query ReleaseTimeline(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        releases(first: 30, after: ${'$'}after, orderBy: $NewestFirst) {
          nodes { $VersionFields }
          pageInfo { hasNextPage endCursor }
        }
      }
    }
"""
        .trimIndent()

private val ReleaseAssetsQuery =
    """
    query ReleaseAssets(${'$'}id: ID!, ${'$'}after: String) {
      node(id: ${'$'}id) {
        ... on Release {
          releaseAssets(first: 30, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            nodes { id name size downloadUrl downloadCount digest }
          }
        }
      }
    }
    """
        .trimIndent()
