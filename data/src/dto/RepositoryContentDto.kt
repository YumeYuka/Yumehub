package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryContentDto(
    val name: String? = null,
    val path: String? = null,
    val type: String? = null,
    val sha: String? = null,
    val size: Long? = null,
    val encoding: String? = null,
    val content: String? = null,
    val entries: List<RepositoryContentDto>? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("submodule_git_url") val submoduleGitUrl: String? = null,
)
