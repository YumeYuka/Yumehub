package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationSubjectDto(
    val title: String? = null,
    val type: String? = null,
    val url: String? = null,
)

@Serializable
data class NotificationRepositoryDto(
    val id: Long? = null,
    val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
)

@Serializable
data class NotificationDto(
    val id: String? = null,
    val unread: Boolean = false,
    val reason: String? = null,
    val subject: NotificationSubjectDto? = null,
    val repository: NotificationRepositoryDto? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val preview: NotificationPreviewDto? = null,
)

@Serializable
data class NotificationPreviewDto(
    val state: String? = null,
    val stateReason: String? = null,
    val merged: Boolean = false,
    val draft: Boolean = false,
    val author: UserDto? = null,
    val body: String? = null,
)

@Serializable
data class SearchRepositoriesResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("incomplete_results") val incompleteResults: Boolean = false,
    val items: List<RepositoryDto> = emptyList(),
)
