package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: Long? = null,
    val type: String? = null,
    val login: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val name: String? = null,
    val bio: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val followers: Int? = null,
    val following: Int? = null,
    @SerialName("public_repos") val publicRepos: Int? = null,
)
