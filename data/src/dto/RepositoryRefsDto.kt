package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.Serializable

@Serializable data class RepositoryRefCommitDto(val sha: String)

@Serializable
data class RepositoryBranchDto(
    val name: String,
    val commit: RepositoryRefCommitDto,
    val protected: Boolean = false,
)

@Serializable data class RepositoryTagDto(val name: String, val commit: RepositoryRefCommitDto)
