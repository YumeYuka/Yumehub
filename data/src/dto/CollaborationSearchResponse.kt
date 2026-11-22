package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSearchResponse(
    val items: List<CollaborationItemDto> = emptyList(),
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("incomplete_results") val incompleteResults: Boolean = false,
)
