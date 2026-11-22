package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ForkRepositoryRequest(
    val name: String,
    @SerialName("default_branch_only") val defaultBranchOnly: Boolean,
)
