package moe.yumeyuka.yumehub.feature.workdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

internal class WorkInfoViewModel(
    repository: WorkDetailRepository,
    owner: String,
    repo: String,
    kind: CollaborationKind,
    number: Int,
    viewer: String,
) : ViewModel() {
    private val detail = repository.detail(viewer, owner, repo, kind, number)
    private val mutableUiState = MutableStateFlow(WorkPropertiesState())
    val uiState = mutableUiState.asStateFlow()
    private val controller =
        WorkPropertiesController(
            detail,
            viewModelScope,
            { mutableUiState.value },
            { transform -> mutableUiState.update(transform) },
            loadIssueFields = kind == CollaborationKind.Issue,
        )

    fun load() = controller.load()

    fun refresh() = controller.refresh()

    fun retry() = controller.refresh()

    fun edit(property: WorkProperty) = controller.edit(property)

    fun retryEditor() = controller.retryEditor()

    fun closeEditor() = controller.closeEditor()

    fun search(query: String) = controller.search(query)

    fun more() = controller.more()

    fun select(option: WorkPropertyOption) = controller.select(option)

    fun applyEditor() = controller.applyEditor()

    fun saveAll() = controller.saveAll()

    fun consumeSaved() = controller.consumeSaved()
}
