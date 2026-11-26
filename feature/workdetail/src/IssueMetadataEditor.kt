package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

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
)

internal class IssueMetadataEditor(
    private val detail: WorkDetailRepository.Detail,
    private val scope: CoroutineScope,
    private val onSaveFailure: (Exception) -> Unit,
) {
    private val mutableState = MutableStateFlow(IssueMetadataState())
    val state = mutableState.asStateFlow()
    private val coord get() = detail.mutations()
    private var loadJob: Job? = null

    fun open(panel: IssueMetadataPanel) {
        loadJob?.cancel()
        val previous = state.value.takeIf { it.panel == panel } ?: IssueMetadataState(panel)
        mutableState.value = previous.copy(loading = true, loadFailed = false)
        loadJob = scope.launch {
            try {
                when (panel) {
                    IssueMetadataPanel.Type -> {
                        val choices = detail.issueTypes()
                        observeChoices(panel, choices, detail.keys.issueType) { listOfNotNull(it.value) }
                    }
                    IssueMetadataPanel.Assignees -> {
                        val choices = detail.issueAssignees()
                        observeChoices(panel, choices, detail.keys.property(WorkProperty.Assignees)) { it }
                    }
                    IssueMetadataPanel.Fields -> {
                        val fields = detail.issueFields()
                        combine(detail.projectedFields(fields), coord.pending) { shown, pending ->
                            val marker = pending.firstOrNull { it.kind == MutationKind.ISSUE_FIELD && it.target.startsWith(detail.keys.fieldPrefix) }
                            IssueMetadataState(panel, fields = shown, uncertain = marker != null, uncertainOperationId = marker?.operationId)
                        }.collect { next -> mutableState.update { next.copy(saveFailed = it.saveFailed, conflict = it.conflict) } }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val retained = if (error.invalidatesCachedContent()) IssueMetadataState(panel) else previous
                mutableState.value = retained.copy(loading = false, loadFailed = true)
            }
        }
    }

    private suspend fun <T : Any> observeChoices(panel: IssueMetadataPanel, base: IssuePropertyChoices, key: MutationKey<T>, options: (T) -> List<WorkPropertyOption>) {
        combine(coord.observe(key), coord.pending) { write, pending ->
            val selected = write.visible?.let(options) ?: base.selected
            val marker = pending.firstOrNull { it.kind == key.kind && it.target == key.target }
            IssueMetadataState(panel, choices = base.copy(selected = selected, options = (base.options + selected).distinctBy { it.id }),
                saving = write.phase == WritePhase.PENDING, saveFailed = write.phase == WritePhase.REJECTED,
                uncertain = write.phase == WritePhase.UNCERTAIN, uncertainOperationId = marker?.operationId)
        }.collect { mutableState.value = it }
    }

    fun dismiss() {
        loadJob?.cancel()
        mutableState.value = IssueMetadataState()
    }

    fun saveChoices(selected: List<WorkPropertyOption>) {
        val snapshot = state.value
        val base = snapshot.choices ?: return
        if (!base.editable || snapshot.saving || snapshot.uncertain) return
        when (snapshot.panel) {
            IssueMetadataPanel.Type -> {
                if (selected.size > 1) return
                val key = detail.keys.issueType
                val accepted = coord.submit(key, NullableValue(base.selected.singleOrNull()), NullableValue(selected.singleOrNull()), detail.mutationTags,
                    send = { detail.saveIssueType(base, it.value) }, readBack = { detail.readIssueType() })
                if (accepted) dismiss()
                reportOutcome(key)
            }
            IssueMetadataPanel.Assignees -> {
                val accepted = detail.submitProperty(WorkProperty.Assignees, base.selected, selected, base.issueId)
                if (accepted) dismiss()
                // Work property failures are reported by the detail's shared observer.
            }
            else -> Unit
        }
    }

    fun saveField(field: IssueField, value: IssueFieldValue?): Boolean {
        val fields = state.value.fields ?: return false
        if (!fields.editable || fields.fields.none { it == field }) return false
        val key = detail.keys.field(field.id)
        val write = coord.current(key)
        if (write.phase == WritePhase.PENDING || write.phase == WritePhase.UNCERTAIN) return false
        val accepted = coord.submit(key, NullableValue(field.value), NullableValue(value), detail.mutationTags,
            send = { desired -> NullableValue(detail.saveIssueField(fields.issueId, field, desired.value)) },
            readBack = { NullableValue(detail.readFieldValue(fields.issueId, field.id)) })
        reportOutcome(key)
        return accepted
    }

    private fun <T : Any> reportOutcome(key: MutationKey<T>) {
        scope.launch {
            val outcome = coord.observe(key).first { it.phase != WritePhase.PENDING }
            if (outcome.phase == WritePhase.REJECTED || outcome.phase == WritePhase.UNCERTAIN) {
                mutableState.update { it.copy(saveFailed = true) }
                onSaveFailure(IllegalStateException(outcome.message))
            }
        }
    }

    fun acknowledgeUncertain() {
        state.value.uncertainOperationId?.let(coord::acknowledge)
    }

    fun clearSaveError() { mutableState.update { it.copy(saveFailed = false) } }
}
