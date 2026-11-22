package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 个人主页资料；README 使用 GraphQL 直接返回的官方 HTML，缺失信息由 resolved 标记区分。 */
@Serializable
data class ProfileDto(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val pronouns: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val websiteUrl: String? = null,
    val email: String? = null,
    val status: ProfileStatusDto? = null,
    val socialAccounts: List<ProfileSocialAccountDto> = emptyList(),
    val badges: List<ProfileBadgeDto> = emptyList(),
    val followers: Int = 0,
    val following: Int = 0,
    val viewerIsFollowing: Boolean? = null,
    @SerialName("isDeveloperProgramMember") val isDeveloperProgramMember: Boolean = false,
    val readmeHtml: String? = null,
    val readmePath: String? = null,
    val nodeId: String? = null,
    val readmeResolved: Boolean = true,
    val audienceResolved: Boolean = true,
    val isOrganization: Boolean = false,
    val repositoriesCount: Int? = null,
    val starredRepositoriesCount: Int? = null,
    val organizationsCount: Int? = null,
)

@Serializable
data class ProfileStatusDto(
    val emoji: String? = null,
    val message: String? = null,
    val indicatesLimitedAvailability: Boolean = false,
    val expiresAt: String? = null,
    val organization: ProfileOrganizationDto? = null,
)

@Serializable
data class ProfileOrganizationDto(
    val id: String,
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
)

@Serializable data class ProfileSocialAccountDto(val url: String)

@Serializable data class ProfileBadgeDto(val name: String, val imageUrl: String)
