package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateRepositoryContentRequest(
    val message: String,
    val content: String,
    val sha: String? = null,
    val branch: String? = null,
)

@Serializable data class UpdateRepositoryContentResponse(val content: RepositoryContentDto? = null)
