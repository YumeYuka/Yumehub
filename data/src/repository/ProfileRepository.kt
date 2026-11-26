package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.api.GitHubGraphQlApi
import moe.yumeyuka.yumehub.data.api.dto.GraphQlRequest
import moe.yumeyuka.yumehub.data.api.dto.ProfileBadgeDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileOrganizationDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileSocialAccountDto
import moe.yumeyuka.yumehub.data.api.dto.ProfileStatusDto
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository
import moe.yumeyuka.yumehub.data.cache.CachedResource
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.relationshipCacheTag
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore

/**
 * 读取自己与其他用户的个人主页资料，并维护共享关注状态。
 *
 * 扩展资料沿用官方 APK 1.283.0 的 `UserProfileFragment` / `UserOrganizationQuery`（sg70），扩展字段不可用时逐步回退到较小的
 * GraphQL 查询。
 */
class ProfileRepository(
    private val graphQlApi: GitHubGraphQlApi,
    private val cache: CachedDataRepository,
    private val tokenStore: TokenStore,
    private val json: Json,
) {
    val account: String
        get() = tokenStore.accountLogin.value

    suspend fun mutations(account: String): MutationCoordinator = cache.mutations(account)

    fun resource(account: String): CachedResource<ProfileDto> =
        cache.resource(
            account = account,
            key = "viewer-profile.v3",
            serializer = ProfileDto.serializer(),
            maxAgeMillis = 5 * 60_000L,
            tags = setOf(CacheTag(DataDomain.Profile, null)),
        )

    fun refresh(account: String, force: Boolean = false) =
        resource(account).refresh(force) { load(account) }

    fun followingResource(account: String, login: String): CachedResource<Boolean> =
        cache.resource(
            account = account,
            key = "following-state:${login.lowercase(java.util.Locale.ROOT)}",
            serializer = Boolean.serializer(),
            tags = setOf(relationshipCacheTag(login)),
        )

    fun relationshipChanged(account: String) {
        cache.changes(account).publish(DataDomain.Relationships, DataDomain.Profile)
    }

    suspend fun updateStatus(
        account: String,
        message: String?,
        emoji: String?,
        limitedAvailability: Boolean,
        expiresAt: String?,
        organizationId: String?,
    ): ProfileStatusDto? {
        val session = GithubGraphQlSession(graphQlApi, tokenStore, account)
        val data =
            session.execute(
                query = UpdateUserStatusMutation,
                variables =
                    buildJsonObject {
                        if (message == null) put("message", JsonNull) else put("message", message)
                        if (emoji == null) put("emoji", JsonNull) else put("emoji", emoji)
                        put("limitedAvailability", limitedAvailability)
                        if (expiresAt == null) put("expiresAt", JsonNull)
                        else put("expiresAt", expiresAt)
                        if (organizationId == null) put("organizationId", JsonNull)
                        else put("organizationId", organizationId)
                    },
                write = true,
            )
        val payload =
            json.decodeFromJsonElement(UpdateStatusData.serializer(), data).changeUserStatus
                ?: throw ProfileException()
        val status =
            payload.status?.let {
                ProfileStatusDto(
                    emoji = it.emoji,
                    message = it.message,
                    indicatesLimitedAvailability = it.indicatesLimitedAvailability,
                    expiresAt = it.expiresAt,
                    organization = it.organization?.toOrganizationOrNull(),
                )
            }
        synchronized(tokenStore.responseCacheSession.lock) {
            session.ensureCurrent()
            val resource = resource(account)
            val current = resource.cached
            if (current != null) resource.update(current.copy(status = status))
            else refresh(account, force = true)
            cache.changes(account).publish(DataDomain.Profile)
        }
        return status
    }

    /** 读取当前账号可设置状态的可见组织；官方私有查询 `ViewerOrganizationsQuery`。 */
    suspend fun organizations(account: String): List<ProfileOrganizationDto> {
        checkAccount(account)
        val response =
            graphQlApi.execute(
                GraphQlRequest(
                    query = ViewerOrganizationsQuery,
                    variables = buildJsonObject { put("first", 100) },
                ),
            )
        checkAccount(account)
        if (response.errors.isNotEmpty()) throw ProfileException()
        val data = response.data as? JsonObject ?: throw ProfileException()
        val nodes =
            json
                .decodeFromJsonElement(ViewerOrganizationsData.serializer(), data)
                .viewer
                ?.organizations
                ?.nodes
                .orEmpty()
        return nodes.mapNotNull { it.toOrganizationOrNull() }
    }

    suspend fun loadUser(login: String, nodeId: String? = null): ProfileDto =
        loadProfile(account, login, nodeId)

    suspend fun loadUserForAccount(
        expected: String,
        login: String,
        nodeId: String? = null,
    ): ProfileDto = loadProfile(expected, login, nodeId)

    private suspend fun load(expected: String): ProfileDto {
        val generation = tokenStore.sessionGeneration.value
        return loadProfile(expected, expected).also { profile ->
            if (profile.login.equals(expected, ignoreCase = true)) {
                profile.avatarUrl?.let { tokenStore.rememberAvatar(expected, generation, it) }
            }
        }
    }

    private suspend fun loadProfile(
        expected: String,
        target: String,
        nodeId: String? = null,
    ): ProfileDto {
        checkAccount(expected)
        val queries =
            listOf(
                userProfileQuery(includePrivateFields = true, byNode = nodeId != null),
                userProfileQuery(includePrivateFields = false, byNode = nodeId != null),
                userProfileQuery(
                    includePrivateFields = false,
                    includeReadme = false,
                    byNode = nodeId != null,
                ),
            )
        var unavailable = false
        for (query in queries) {
            val response =
                graphQlApi.execute(
                    GraphQlRequest(
                        query = query,
                        variables =
                            buildJsonObject {
                                if (nodeId != null) put("id", nodeId) else put("login", target)
                            },
                    ),
                )
            checkAccount(expected)
            val data = response.data as? JsonObject
            // 扩展字段校验失败使 user 不可用时降级；可用的部分响应仍保留。
            if (data?.get("user") !is JsonObject) {
                val missingUser =
                    response.errors.any {
                        it.type in setOf("NOT_FOUND", "FORBIDDEN") ||
                            (it.extensions?.get("type") as? JsonPrimitive)?.contentOrNull in
                                setOf("NOT_FOUND", "FORBIDDEN")
                    }
                if (missingUser || (data?.get("user") == JsonNull && response.errors.isEmpty())) {
                    unavailable = true
                    continue
                }
                if (response.errors.isNotEmpty()) continue
                throw ProfileException()
            }
            val user =
                json.decodeFromJsonElement(ProfileQueryData.serializer(), data).user
                    ?: throw ProfileException()
            val login = user.login.orEmpty()
            if (login.isBlank()) throw ProfileUnavailableException()
            check(
                if (nodeId != null) user.id == nodeId else login.equals(target, ignoreCase = true),
            ) {
                "GitHub returned a different profile"
            }
            val profile = user.toProfile()
            return if (profile.isOrganization) loadOrganizationReadme(expected, profile)
            else profile
        }
        if (unavailable) throw ProfileUnavailableException()
        throw ProfileException()
    }

    private suspend fun loadOrganizationReadme(
        expected: String,
        profile: ProfileDto,
    ): ProfileDto =
        try {
            val organization =
                GithubGraphQlSession(graphQlApi, tokenStore, expected)
                    .execute(
                        OrganizationReadmeQuery,
                        buildJsonObject { put("login", profile.login) },
                    )
                    .graphObject("organization")
            check(organization.graphRequiredText("login").equals(profile.login, true))
            val readme = organization["readme"]
            if (readme == JsonNull) {
                profile.copy(readmeResolved = true)
            } else {
                val payload = readme as? JsonObject ?: throw ProfileException()
                val html = payload.graphText("contentHTML") ?: throw ProfileException()
                val path = payload.graphRequiredText("path")
                if (html.length > 4 * 1024 * 1024 || !isRepositoryContentPath(path))
                    throw ProfileException()
                profile.copy(
                    readmeHtml = html.takeIf(String::isNotBlank),
                    readmePath = path,
                    readmeResolved = true,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            profile.copy(readmeResolved = false)
        }

    suspend fun setFollowing(expected: String, userId: String, selected: Boolean): Boolean {
        val session = GithubGraphQlSession(graphQlApi, tokenStore, expected)
        val field = if (selected) "followUser" else "unfollowUser"
        val data =
            session.execute(
                query =
                    """
                    mutation UpdateFollow(${'$'}userId: ID!) {
                      $field(input: {userId: ${'$'}userId}) {
                        user { id viewerIsFollowing }
                      }
                    }
                """
                        .trimIndent(),
                variables = buildJsonObject { put("userId", userId) },
                write = true,
            )
        val payload = data[field] as? JsonObject ?: throw ProfileException()
        val user = payload["user"] as? JsonObject ?: throw ProfileException()
        if (user["id"]?.jsonPrimitive?.contentOrNull != userId) throw ProfileException()
        val confirmed =
            user["viewerIsFollowing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: throw ProfileException()
        synchronized(tokenStore.responseCacheSession.lock) {
            session.ensureCurrent()
            cache.changes(expected).publish(DataDomain.Relationships, DataDomain.Profile)
        }
        return confirmed
    }

    private fun checkAccount(expected: String) {
        if (expected.isBlank() || !account.equals(expected, ignoreCase = true)) {
            throw CancellationException("Viewer account changed")
        }
    }
}

class ProfileException(message: String = "GitHub profile response was not usable") :
    Exception(message)

/** 用户不存在、被停用或当前账号不可访问；不能据此区分封禁与注销。 */
class ProfileUnavailableException : Exception("GitHub user is unavailable")

internal fun ProfileQueryUser.toProfile(): ProfileDto {
    val profileReadme = profileReadme?.takeIf { showProfileReadme != false }
    val isOrganization = type == "Organization"
    return ProfileDto(
        login = login.orEmpty(),
        name = name,
        avatarUrl = avatarUrl,
        pronouns = pronouns,
        bio = bio,
        company = company,
        location = location,
        websiteUrl = websiteUrl,
        email = if (isOrganization) organizationEmail else userEmail,
        status =
            status?.let {
                ProfileStatusDto(
                    emoji = it.emoji,
                    message = it.message,
                    indicatesLimitedAvailability = it.indicatesLimitedAvailability,
                    expiresAt = it.expiresAt,
                )
            },
        socialAccounts =
            socialAccounts?.nodes.orEmpty().mapNotNull { node ->
                node.url?.takeIf { it.isNotBlank() }?.let(::ProfileSocialAccountDto)
            },
        badges =
            achievements?.nodes.orEmpty().mapNotNull { node ->
                val imageUrl = node.tier?.badgeImageUrl ?: return@mapNotNull null
                ProfileBadgeDto(
                    name = node.achievable?.name ?: node.achievable?.slug.orEmpty(),
                    imageUrl = imageUrl,
                )
            },
        followers = followers?.totalCount ?: 0,
        following = following?.totalCount ?: 0,
        viewerIsFollowing = viewerIsFollowing,
        isDeveloperProgramMember = isDeveloperProgramMember,
        readmeHtml = profileReadme?.contentHTML?.takeIf { it.isNotEmpty() },
        readmePath = profileReadme?.path,
        nodeId = id,
        readmeResolved = isOrganization || showProfileReadme != null || this.profileReadme != null,
        audienceResolved =
            isOrganization || (followers?.totalCount != null && following?.totalCount != null),
        isOrganization = isOrganization,
        repositoriesCount = repositories?.totalCount,
        starredRepositoriesCount = starredRepositories?.totalCount,
        organizationsCount = organizations?.totalCount,
    )
}

@Serializable private data class ProfileQueryData(val user: ProfileQueryUser? = null)

@Serializable
internal data class ProfileQueryUser(
    @SerialName("__typename") val type: String? = null,
    val id: String? = null,
    val login: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val pronouns: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val websiteUrl: String? = null,
    @SerialName("userEmail") val userEmail: String? = null,
    @SerialName("organizationEmail") val organizationEmail: String? = null,
    val status: ProfileQueryStatus? = null,
    val socialAccounts: ProfileQuerySocialAccounts? = null,
    val achievements: ProfileQueryAchievements? = null,
    val followers: ProfileQueryTotalCount? = null,
    val following: ProfileQueryTotalCount? = null,
    val viewerIsFollowing: Boolean? = null,
    val repositories: ProfileQueryTotalCount? = null,
    val starredRepositories: ProfileQueryTotalCount? = null,
    val organizations: ProfileQueryTotalCount? = null,
    @SerialName("isDeveloperProgramMember") val isDeveloperProgramMember: Boolean = false,
    @SerialName("showProfileReadme") val showProfileReadme: Boolean? = null,
    @SerialName("profileReadme") val profileReadme: ProfileQueryReadme? = null,
)

@Serializable
internal data class ProfileQueryStatus(
    val emoji: String? = null,
    val message: String? = null,
    val indicatesLimitedAvailability: Boolean = false,
    val expiresAt: String? = null,
    val organization: ProfileQueryOrganization? = null,
)

@Serializable
internal data class ProfileQueryOrganization(
    val id: String? = null,
    val login: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
)

@Serializable private data class ViewerOrganizationsData(val viewer: ProfileQueryViewer? = null)

@Serializable
private data class ProfileQueryViewer(val organizations: ProfileQueryOrganizations? = null)

@Serializable
private data class ProfileQueryOrganizations(val nodes: List<ProfileQueryOrganization>? = null)

@Serializable
internal data class ProfileQuerySocialAccounts(val nodes: List<ProfileQuerySocialAccount>? = null)

@Serializable internal data class ProfileQuerySocialAccount(val url: String? = null)

@Serializable
internal data class ProfileQueryAchievements(val nodes: List<ProfileQueryAchievement>? = null)

@Serializable
internal data class ProfileQueryAchievement(
    val achievable: ProfileQueryAchievable? = null,
    val tier: ProfileQueryAchievementTier? = null,
)

@Serializable
internal data class ProfileQueryAchievable(val name: String? = null, val slug: String? = null)

@Serializable internal data class ProfileQueryAchievementTier(val badgeImageUrl: String? = null)

@Serializable internal data class ProfileQueryTotalCount(val totalCount: Int? = null)

@Serializable
internal data class ProfileQueryReadme(val contentHTML: String? = null, val path: String? = null)

private fun ProfileQueryOrganization.toOrganizationOrNull(): ProfileOrganizationDto? =
    id?.takeIf { it.isNotBlank() }
        ?.let {
            ProfileOrganizationDto(
                id = it,
                login = login.orEmpty(),
                name = name,
                avatarUrl = avatarUrl,
            )
        }

@Serializable private data class UpdateStatusData(val changeUserStatus: UpdateStatusPayload? = null)

@Serializable private data class UpdateStatusPayload(val status: ProfileQueryStatus? = null)

private val OrganizationReadmeQuery =
    """
    query UserOrganizationQuery(${'$'}login: String!) {
        organization(login: ${'$'}login) {
            login readme { contentHTML path }
        }
    }
    """
        .trimIndent()

private fun userProfileQuery(
    includePrivateFields: Boolean,
    includeReadme: Boolean = true,
    byNode: Boolean = false,
): String =
    """
    query UserProfile(${if (byNode) "${'$'}id: ID!" else "${'$'}login: String!"}) {
      ${if (byNode) "user: node(id: ${'$'}id)" else "user: repositoryOwner(login: ${'$'}login)"} {
        __typename
        ... on User {
          id
          login
          name
          avatarUrl
          pronouns
          bio
          company
          location
          websiteUrl
          userEmail: email
          status {
            emoji
            message
            indicatesLimitedAvailability
            expiresAt
            organization { id login name avatarUrl __typename }
            __typename
          }
          socialAccounts(first: 4) {
            nodes { url __typename }
          }
          followers { totalCount }
          following { totalCount }
          viewerIsFollowing
          repositories(ownerAffiliations: [OWNER]) { totalCount }
          starredRepositories { totalCount }
          organizations { totalCount }
          isDeveloperProgramMember
          ${if (includePrivateFields) ExtendedProfileFields else ""}
          ${if (includeReadme) "showProfileReadme profileReadme { contentHTML path __typename }" else ""}
          __typename
        }
        ... on Organization {
          id
          login
          name
          avatarUrl
          bio: description
          location
          websiteUrl
          organizationEmail: email
          repositories { totalCount }
        }
      }
    }
    """
        .trimIndent()

private val ExtendedProfileFields =
    """
    achievements(first: 10) {
      nodes {
        id
        achievable { name slug __typename }
        tier(number: 1) { id badgeImageUrl __typename }
        __typename
      }
    }
    """
        .trimIndent()

private val UpdateUserStatusMutation =
    """
    mutation UpdateUserStatus(${'$'}message: String, ${'$'}emoji: String, ${'$'}limitedAvailability: Boolean, ${'$'}expiresAt: DateTime, ${'$'}organizationId: ID) {
      changeUserStatus(input: {
        message: ${'$'}message
        emoji: ${'$'}emoji
        limitedAvailability: ${'$'}limitedAvailability
        expiresAt: ${'$'}expiresAt
        organizationId: ${'$'}organizationId
      }) {
        status {
          emoji
          message
          indicatesLimitedAvailability
          expiresAt
          organization { id login name avatarUrl __typename }
          __typename
        }
        __typename
      }
    }
    """
        .trimIndent()

private val ViewerOrganizationsQuery =
    """
    query ViewerOrganizations(${'$'}first: Int!) {
      viewer {
        organizations(first: ${'$'}first) {
          nodes { id login name avatarUrl __typename }
          __typename
        }
        __typename
      }
    }
    """
        .trimIndent()
