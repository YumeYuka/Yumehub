package moe.yumeyuka.yumehub.feature.workdetail

import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.IssueFields
import moe.yumeyuka.yumehub.data.api.dto.WorkProperties
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.MutationCoordinator
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.cache.WriteState
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

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
    val issueFields: IssueFields? = null,
    val editor: WorkPropertyEditor? = null,
    val drafts: Map<WorkProperty, List<WorkPropertyOption>> = emptyMap(),
    val submitting: Boolean = false,
    val saveFailed: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
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

internal fun WorkDetailRepository.Detail.propertyMutationKey(
    property: WorkProperty,
): MutationKey<List<String>> =
    MutationKey(
        MutationKind.WORK_PROPERTY,
        "$workTarget:property:${property.name}",
        ListSerializer(String.serializer()),
    )

internal fun IssueFieldValue.toMutationToken(): String =
    when (this) {
        is IssueFieldValue.Date -> "date:$value"
        is IssueFieldValue.Number -> "number:${BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()}"
        is IssueFieldValue.Text -> "text:$value"
        is IssueFieldValue.SingleSelect -> "single:$id"
        is IssueFieldValue.MultiSelect -> "multi:${ids.sorted().joinToString(",")}"
    }

internal suspend fun <T : Any> MutationCoordinator.submitAndAwait(
    key: MutationKey<T>,
    confirmed: T?,
    desired: T,
    tags: Set<CacheTag>,
    send: suspend (T) -> T,
    readBack: suspend () -> T,
): WriteState<T> {
    val accepted = submit(key, confirmed, desired, tags, send, readBack)
    if (!accepted) return current(key)
    return observe(key).first { it.phase != WritePhase.PENDING }
}

internal fun MutationCoordinator.findUncertainOperationId(
    kind: MutationKind,
    target: String,
): String? =
    pending.value.firstOrNull { it.kind == kind && it.target == target }?.operationId
