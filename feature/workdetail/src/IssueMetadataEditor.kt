package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import moe.yumeyuka.yumehub.data.api.dto.IssueField
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.IssueFields
import moe.yumeyuka.yumehub.data.api.dto.IssuePropertyChoices
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.NullableValue
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.WorkDetailReviewChangedException

internal enum class IssueMetadataPanel { Type, Assignees, Fields }

internal data class IssueMetadataState(
    val panel: IssueMetadataPanel? = null,
    val choices: IssuePropertyChoices? = null,
    val fields: IssueFields? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val conflict: Boolean = false,
    val uncertain: Boolean = false,
    val uncertainOperationId: String? = null,
    val closeChoices: Boolean = false,
)

internal class IssueMetadataEditor(
    private val detail: WorkDetailRepository.Detail,
    private val scope: CoroutineScope,
    private val onSaveFailure: (Exception) -> Unit,
) {
    private val mutableState = MutableStateFlow(IssueMetadataState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null

    private fun typeKey() =
        MutationKey(
            MutationKind.ISSUE_FIELD,
            "${detail.workTarget}:issue-type",
            NullableValue.serializer(String.serializer()),
        )

    private fun fieldKey(fieldId: String) =
        MutationKey(
            MutationKind.ISSUE_FIELD,
            "${detail.workTarget}:issue-field:$fieldId",
            NullableValue.serializer(String.serializer()),
        )

    fun open(panel: IssueMetadataPanel) {
        if (state.value.saving) return
        loadJob?.cancel()
        mutableState.value = IssueMetadataState(panel = panel, loading = true)
        loadJob = scope.launch {
            try {
                val coord = detail.mutations()
                val (choices, fields, activeKey) =
                    when (panel) {
                        IssueMetadataPanel.Type -> {
                            val key = typeKey()
                            Triple(detail.issueTypes(), null, key)
                        }
                        IssueMetadataPanel.Assignees -> {
                            val key = detail.propertyMutationKey(WorkProperty.Assignees)
                            Triple(detail.issueAssignees(), null, key)
                        }
                        IssueMetadataPanel.Fields ->
                            Triple(null, detail.issueFields(), null)
                    }
                val opId =
                    when (panel) {
                        IssueMetadataPanel.Type,
                        IssueMetadataPanel.Assignees ->
                            activeKey?.let { coord.findUncertainOperationId(it.kind, it.target) }
                        IssueMetadataPanel.Fields ->
                            fields?.fields
                                ?.firstNotNullOfOrNull {
                                    val key = fieldKey(it.id)
                                    coord.findUncertainOperationId(key.kind, key.target)
                                }
                    }
                mutableState.value =
                    IssueMetadataState(
                        panel = panel,
                        choices = choices,
                        fields = fields,
                        uncertain = opId != null,
                        uncertainOperationId = opId,
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = IssueMetadataState(panel, loadFailed = true)
            }
        }
    }

    fun dismiss() {
        loadJob?.cancel()
        val current = state.value
        if (current.saving && current.closeChoices) return
        mutableState.value = IssueMetadataState(saving = current.saving)
    }

    fun saveChoices(selected: List<WorkPropertyOption>) {
        val snapshot = state.value
        val baseline = snapshot.choices ?: return
        if (!baseline.editable || snapshot.closeChoices || snapshot.uncertain) return
        when (snapshot.panel) {
            IssueMetadataPanel.Type -> {
                if (selected.size > 1) return
                val id = selected.singleOrNull()?.id
                save(closeChoices = true) {
                    val coord = detail.mutations()
                    var failure: Exception? = null
                    val key = typeKey()
                    val result =
                        coord.submitAndAwait(
                            key = key,
                            confirmed = NullableValue(baseline.selected.singleOrNull()?.id),
                            desired = NullableValue(id),
                            tags = detail.mutationTags,
                            send = { desired ->
                                try {
                                    detail.saveIssueType(baseline, desired.value)
                                    desired
                                } catch (error: WorkDetailReviewChangedException) {
                                    failure = error
                                    throw error
                                }
                            },
                            readBack = {
                                NullableValue(detail.issueTypes().selected.singleOrNull()?.id)
                            },
                        )
                    handleWriteOutcome(coord, key.kind, key.target, result.phase, failure, closeChoices = true)
                }
            }
            IssueMetadataPanel.Assignees -> {
                save(closeChoices = true) {
                    val coord = detail.mutations()
                    var failure: Exception? = null
                    val key = detail.propertyMutationKey(WorkProperty.Assignees)
                    val desiredIds = selected.map { it.id }
                    val result =
                        coord.submitAndAwait(
                            key = key,
                            confirmed = baseline.selected.map { it.id },
                            desired = desiredIds,
                            tags = detail.mutationTags,
                            send = { _ ->
                                try {
                                    detail.saveIssueAssignees(baseline, selected)
                                    desiredIds
                                } catch (error: WorkDetailReviewChangedException) {
                                    failure = error
                                    throw error
                                }
                            },
                            readBack = { detail.issueAssignees().selected.map { it.id } },
                        )
                    handleWriteOutcome(coord, key.kind, key.target, result.phase, failure, closeChoices = true)
                }
            }
            else -> return
        }
    }

    fun saveField(field: IssueField, value: IssueFieldValue?): Boolean {
        val baseline = state.value.fields ?: return false
        if (!baseline.editable || baseline.fields.none { it.id == field.id } || state.value.uncertain) {
            return false
        }
        return save {
            val coord = detail.mutations()
            var failure: Exception? = null
            val key = fieldKey(field.id)
            val result =
                coord.submitAndAwait(
                    key = key,
                    confirmed = NullableValue(field.value?.toMutationToken()),
                    desired = NullableValue(value?.toMutationToken()),
                    tags = detail.mutationTags,
                    send = { desired ->
                        try {
                            detail.saveIssueField(baseline.issueId, field, value)
                            desired
                        } catch (error: WorkDetailReviewChangedException) {
                            failure = error
                            throw error
                        }
                    },
                    readBack = {
                        val refreshed = detail.issueFields().fields.firstOrNull { it.id == field.id }
                        NullableValue(refreshed?.value?.toMutationToken())
                    },
                )
            if (result.phase == WritePhase.IDLE) {
                mutableState.update { current ->
                    val active = current.fields ?: return@update current
                    if (active.issueId != baseline.issueId) current
                    else current.copy(fields = active.copy(fields = active.fields.map {
                        if (it.id == field.id) it.copy(value = value) else it
                    }))
                }
            } else {
                handleWriteOutcome(coord, key.kind, key.target, result.phase, failure, closeChoices = false)
            }
        }
    }

    fun acknowledgeUncertain() {
        val opId = state.value.uncertainOperationId ?: return
        scope.launch {
            detail.mutations().acknowledge(opId)
            mutableState.update {
                it.copy(uncertain = false, uncertainOperationId = null, saveFailed = false)
            }
        }
    }

    fun clearSaveError() {
        mutableState.update { if (!it.saving) it.copy(saveFailed = false) else it }
    }

    private fun handleWriteOutcome(
        coord: moe.yumeyuka.yumehub.data.cache.MutationCoordinator,
        kind: MutationKind,
        target: String,
        phase: WritePhase,
        failure: Exception?,
        closeChoices: Boolean,
    ) {
        when (phase) {
            WritePhase.IDLE -> {
                if (closeChoices) mutableState.value = IssueMetadataState()
            }
            WritePhase.REJECTED -> {
                if (failure is WorkDetailReviewChangedException) {
                    mutableState.update { it.copy(conflict = true, closeChoices = false) }
                    onSaveFailure(failure)
                } else {
                    mutableState.update { it.copy(saveFailed = true, closeChoices = false) }
                    onSaveFailure(failure ?: IllegalStateException("Save rejected"))
                }
            }
            WritePhase.UNCERTAIN -> {
                val opId = coord.findUncertainOperationId(kind, target)
                mutableState.update {
                    it.copy(
                        saveFailed = true,
                        uncertain = true,
                        uncertainOperationId = opId,
                        closeChoices = false,
                    )
                }
                onSaveFailure(IllegalStateException("Save unconfirmed"))
            }
            WritePhase.PENDING -> Unit
        }
    }

    private fun save(closeChoices: Boolean = false, write: suspend () -> Unit): Boolean {
        if (state.value.saving || state.value.uncertain) return false
        mutableState.update {
            it.copy(saving = true, saveFailed = false, conflict = false, closeChoices = closeChoices)
        }
        scope.launch {
            try {
                // 先提交 UI 状态并让出主线程，再开始可能较慢的 GraphQL 写请求。
                yield()
                write()
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkDetailReviewChangedException) {
                mutableState.update { it.copy(conflict = true, closeChoices = false) }
                onSaveFailure(error)
            } catch (error: Exception) {
                mutableState.update { it.copy(saveFailed = true, closeChoices = false) }
                onSaveFailure(error)
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
        return true
    }
}
