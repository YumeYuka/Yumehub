package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.Serializable

/** 官方 GraphQL 结构化行，不是重建的 unified/raw patch。 */
@Serializable
data class StructuredDiffDto(
    val id: String,
    val baseCommitOid: String,
    val headCommitOid: String,
    val oldPath: String?,
    val newPath: String?,
    val isBinary: Boolean,
    val isLargeDiff: Boolean,
    val isSubmodule: Boolean,
    val lines: List<StructuredDiffLineDto>,
    val unavailableReason: StructuredDiffUnavailableReason? = null,
)

@Serializable
data class StructuredDiffLineDto(
    val type: StructuredDiffLineType,
    val text: String,
    val left: Int?,
    val right: Int?,
    val isMissingNewlineAtEnd: Boolean,
)

@Serializable
enum class StructuredDiffLineType {
    Addition,
    Context,
    Deletion,
    Hunk,
}

@Serializable
enum class StructuredDiffUnavailableReason {
    Binary,
    LargeDiff,
    Submodule,
    MissingLines,
    UnsupportedLineType,
    InvalidLines,
}
