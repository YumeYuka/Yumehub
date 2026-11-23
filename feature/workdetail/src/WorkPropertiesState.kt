package moe.yumeyuka.yumehub.feature.workdetail

import moe.yumeyuka.yumehub.data.api.dto.WorkProperties
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption

internal data class WorkPropertyEditor(
    val property: WorkProperty,
    val original: List<WorkPropertyOption>,
    val selected: List<WorkPropertyOption>,
    val options: List<WorkPropertyOption> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val needsReconcile: Boolean = false,
    val query: String = "",
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val reviewed: List<WorkPropertyOption> = emptyList(),
    val reviewerAuthorLogin: String? = null,
    val hasReviewerCandidates: Boolean = false,
    val editable: Boolean = true,
)

internal fun WorkPropertyEditor.reviewRequestsChanged(): Boolean =
    original.map(WorkPropertyOption::id).toSet() != selected.map(WorkPropertyOption::id).toSet()

internal data class WorkPropertiesState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val value: WorkProperties? = null,
    val references: List<WorkPropertyOption> = emptyList(),
    val editor: WorkPropertyEditor? = null,
    val drafts: Map<WorkProperty, List<WorkPropertyOption>> = emptyMap(),
    val submitting: Boolean = false,
    val saved: Boolean = false,
)

internal fun WorkPropertiesState.valuesFor(property: WorkProperty): List<WorkPropertyOption> =
    drafts[property] ?: value?.values?.get(property).orEmpty()

internal fun WorkPropertiesState.hasPendingChanges(): Boolean {
    val actual = value ?: return false
    return drafts.any { (property, selected) ->
        selected.map { it.id }.toSet() != actual.values[property].orEmpty().map { it.id }.toSet()
    }
}
