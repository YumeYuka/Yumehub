package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryOwnerDto(
    val type: String? = null,
    val login: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class RepositoryDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val owner: RepositoryOwnerDto? = null,
    val private: Boolean? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val description: String? = null,
    val fork: Boolean? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stargazersCount: Int? = null,
    @SerialName("forks_count") val forksCount: Int? = null,
    @SerialName("open_issues_count") val openIssuesCount: Int? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val openGraphImageUrl: String? = null,
)
