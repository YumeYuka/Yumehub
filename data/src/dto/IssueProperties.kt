package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.Serializable

data class IssuePropertyChoices(
    val issueId: String,
    val selected: List<WorkPropertyOption>,
    val options: List<WorkPropertyOption>,
    val editable: Boolean,
)

enum class IssueFieldKind {
    Date,
    Number,
    Text,
    SingleSelect,
    MultiSelect,
}

@Serializable
sealed interface IssueFieldValue {
    @Serializable data class Date(val value: String) : IssueFieldValue

    @Serializable data class Number(val value: Double) : IssueFieldValue

    @Serializable data class Text(val value: String) : IssueFieldValue

    @Serializable data class SingleSelect(val id: String) : IssueFieldValue

    @Serializable data class MultiSelect(val ids: Set<String>) : IssueFieldValue
}

data class IssueField(
    val id: String,
    val name: String,
    val description: String?,
    val kind: IssueFieldKind,
    val options: List<WorkPropertyOption>,
    val value: IssueFieldValue?,
    val pinned: Boolean,
)

data class IssueFields(
    val issueId: String,
    val fields: List<IssueField>,
    val editable: Boolean,
)
