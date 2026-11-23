package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.IssueField
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.IssueFields
import moe.yumeyuka.yumehub.data.api.dto.IssuePropertyChoices
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
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
)

internal class IssueMetadataEditor(
    private val detail: WorkDetailRepository.Detail,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(IssueMetadataState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun open(panel: IssueMetadataPanel) {
        if (state.value.saving) return
        job?.cancel()
        mutableState.value = IssueMetadataState(panel = panel, loading = true)
        job = scope.launch {
            try {
                mutableState.value = when (panel) {
                    IssueMetadataPanel.Type -> IssueMetadataState(panel, choices = detail.issueTypes())
                    IssueMetadataPanel.Assignees -> IssueMetadataState(panel, choices = detail.issueAssignees())
                    IssueMetadataPanel.Fields -> IssueMetadataState(panel, fields = detail.issueFields())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = IssueMetadataState(panel, loadFailed = true)
            }
        }
    }

    fun dismiss() {
        if (state.value.saving) return
        job?.cancel()
        mutableState.value = IssueMetadataState()
    }

    fun saveChoices(selected: List<WorkPropertyOption>) {
        val snapshot = state.value
        val baseline = snapshot.choices ?: return
        if (!baseline.editable) return
        save(snapshot) {
            when (snapshot.panel) {
                IssueMetadataPanel.Type -> detail.saveIssueType(baseline, selected.singleOrNull()?.id)
                IssueMetadataPanel.Assignees -> detail.saveIssueAssignees(baseline, selected)
                else -> error("Not an issue choice panel")
            }
            IssueMetadataState()
        }
    }

    fun saveField(field: IssueField, value: IssueFieldValue?) {
        val snapshot = state.value
        val baseline = snapshot.fields ?: return
        if (!baseline.editable || baseline.fields.none { it == field }) return
        save(snapshot) {
            detail.saveIssueField(baseline.issueId, field, value)
            snapshot.copy(fields = baseline.copy(fields = baseline.fields.map {
                if (it.id == field.id) it.copy(value = value) else it
            }))
        }
    }

    fun clearSaveError() {
        val current = state.value
        if (!current.saving) mutableState.value = current.copy(saveFailed = false)
    }

    private fun save(snapshot: IssueMetadataState, write: suspend () -> IssueMetadataState) {
        if (snapshot.saving || snapshot.conflict) return
        mutableState.value = snapshot.copy(saving = true, saveFailed = false)
        job = scope.launch {
            try {
                mutableState.value = write().copy(saving = false, saveFailed = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkDetailReviewChangedException) {
                mutableState.value = snapshot.copy(saving = false, saveFailed = false, conflict = true)
            } catch (_: Exception) {
                mutableState.value = snapshot.copy(saving = false, saveFailed = true)
            }
        }
    }
}
