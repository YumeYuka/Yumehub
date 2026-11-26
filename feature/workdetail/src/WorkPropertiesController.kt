package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent

internal class WorkPropertiesController(
    private val detail: WorkDetailRepository.Detail,
    private val scope: CoroutineScope,
    private val state: () -> WorkPropertiesState,
    private val update: ((WorkPropertiesState) -> WorkPropertiesState) -> Unit,
    private val loadIssueFields: Boolean,
) {
    private var loadJob: Job? = null
    private var referencesJob: Job? = null
    private var fieldsJob: Job? = null
    private var editorJob: Job? = null
    private val coord = detail.mutations()
    private var fieldObserver: Job? = null

    init {
        for (property in WorkProperty.entries) scope.launch {
            val key = detail.keys.property(property)
            coord.observe(key).collect { write ->
                update { current ->
                    val editor = current.editor
                    val active = editor?.property == property
                    val uncertain = write.phase == WritePhase.UNCERTAIN
                    val changed =
                        write.confirmed != null &&
                            editor != null &&
                            active &&
                            editor.original != write.confirmed &&
                            !editor.reviewRequestsChanged()
                    current.copy(
                        writes = current.writes + (property to write),
                        editor =
                            if (!active || editor == null) editor
                            else
                                editor.copy(
                                    original = if (changed) write.confirmed!! else editor.original,
                                    selected =
                                        if (changed) write.visible ?: editor.selected
                                        else editor.selected,
                                    needsReconcile = uncertain,
                                ),
                        uncertain = if (active) uncertain else current.uncertain,
                        uncertainOperationId =
                            if (active) coord.findUncertainOperationId(key.kind, key.target)
                            else current.uncertainOperationId,
                        saveFailed = current.saveFailed || write.phase == WritePhase.REJECTED,
                    )
                }
            }
        }
    }

    fun load() {
        val current = state()
        if (current.value == null && !current.failed && !current.loading) refresh()
        if (
            current.references == null &&
                !current.referencesFailed &&
                referencesJob?.isActive != true
        )
            loadReferences()
        if (
            loadIssueFields &&
                current.issueFields == null &&
                !current.fieldsFailed &&
                fieldsJob?.isActive != true
        )
            loadFields()
    }

    fun refresh() {
        if (state().submitting) return
        loadJob?.cancel()
        update { it.copy(loading = true, failed = false) }
        loadJob = scope.launch {
            try {
                val properties = detail.properties()
                update { it.copy(value = properties, loading = false, failed = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(
                        value = it.value.takeUnless { error.invalidatesCachedContent() },
                        loading = false,
                        failed = true,
                    )
                }
            }
        }
    }

    fun loadReferences() {
        if (referencesJob?.isActive == true) return
        update { it.copy(referencesFailed = false) }
        referencesJob = scope.launch {
            try {
                val references = detail.crossReferences()
                update { it.copy(references = references) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(
                        references = it.references.takeUnless { error.invalidatesCachedContent() },
                        referencesFailed = true,
                    )
                }
            }
        }
    }

    fun loadFields() {
        if (fieldsJob?.isActive == true) return
        update { it.copy(fieldsFailed = false) }
        fieldsJob = scope.launch {
            try {
                val fields = detail.issueFields()
                fieldObserver?.cancel()
                update { it.copy(issueFields = fields) }
                fieldObserver = scope.launch {
                    detail.projectedFields(fields).collect { projected ->
                        update { it.copy(issueFields = projected) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.invalidatesCachedContent()) fieldObserver?.cancel()
                update {
                    it.copy(
                        issueFields =
                            it.issueFields.takeUnless { error.invalidatesCachedContent() },
                        fieldsFailed = true,
                    )
                }
            }
        }
    }

    fun edit(property: WorkProperty) {
        val current = state()
        if (property == WorkProperty.Reviewers) {
            if (!current.submitting && current.editor?.property != property) {
                loadReviewRequests(WorkPropertyEditor(property, emptyList(), emptyList()))
            }
            return
        }
        val value = current.value ?: return
        val editable = if (property == WorkProperty.Labels) value.labelsEditable else value.editable
        if (current.submitting) return
        if (current.editor?.property == property) return
        val key = detail.keys.property(property)
        val write = coord.current(key)
        val original = write.confirmed ?: value.values[property].orEmpty()
        val selected = current.drafts[property] ?: write.visible ?: original
        editorJob?.cancel()
        update {
            it.copy(
                saveFailed = false,
                uncertain = write.phase == WritePhase.UNCERTAIN,
                uncertainOperationId = coord.findUncertainOperationId(key.kind, key.target),
                editor =
                    WorkPropertyEditor(
                        property,
                        original,
                        selected,
                        needsReconcile = write.phase == WritePhase.UNCERTAIN,
                        loading = editable,
                        editable = editable,
                    ),
            )
        }
        if (editable) loadOptions(property)
    }

    fun closeEditor() {
        editorJob?.cancel()
        update { it.copy(editor = null) }
    }

    fun retryEditor() {
        val editor = state().editor ?: return
        if (state().submitting) return
        if (editor.needsReconcile) {
            val key = detail.keys.property(editor.property)
            coord.reconcile(key) { detail.readProperty(editor.property) }
        }
        if (editor.property == WorkProperty.Reviewers) loadReviewRequests(editor)
        else loadOptions(editor.property)
    }

    private fun loadReviewRequests(editor: WorkPropertyEditor) {
        editorJob?.cancel()
        update { it.copy(editor = editor.copy(loading = true, failed = false)) }
        editorJob = scope.launch {
            try {
                val data = detail.reviewRequests()
                val originalIds = editor.original.map(WorkPropertyOption::id).toSet()
                val selectedIds = editor.selected.map(WorkPropertyOption::id).toSet()
                val removed = originalIds - selectedIds
                val added = selectedIds - originalIds
                val choices = (data.reviewed + editor.selected).distinctBy { it.id }
                val selected =
                    (data.requested.filterNot { it.editable && it.id in removed } +
                            choices.filter {
                                it.editable &&
                                    it.id in added &&
                                    !it.title.equals(data.authorLogin, true)
                            })
                        .distinctBy { it.id }
                val key = detail.keys.property(WorkProperty.Reviewers)
                val write = coord.current(key)
                update { current ->
                    val active =
                        current.editor?.takeIf { it.property == WorkProperty.Reviewers }
                            ?: return@update current
                    current.copy(
                        uncertain = write.phase == WritePhase.UNCERTAIN,
                        uncertainOperationId = coord.findUncertainOperationId(key.kind, key.target),
                        editor =
                            active.copy(
                                original = write.confirmed ?: data.requested,
                                selected =
                                    if (write.phase == WritePhase.PENDING) write.visible ?: selected
                                    else if (data.editable) selected else data.requested,
                                options = emptyList(),
                                nextCursor = null,
                                loadingMore = false,
                                moreFailed = false,
                                hasReviewerCandidates = false,
                                reviewerAuthorLogin = data.authorLogin,
                                reviewed = data.reviewed,
                                editable = data.editable,
                                loading = false,
                                failed = false,
                                needsReconcile = write.phase == WritePhase.UNCERTAIN,
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { current ->
                    val active =
                        current.editor?.takeIf { it.property == WorkProperty.Reviewers }
                            ?: return@update current
                    current.copy(editor = active.copy(loading = false, failed = true))
                }
            }
        }
    }

    private fun loadOptions(property: WorkProperty) {
        if (property.isSearchBacked()) {
            searchItems(property, state().editor?.query.orEmpty())
            return
        }
        editorJob = scope.launch {
            try {
                val options = detail.propertyOptions(property)
                update { current ->
                    val editor = current.editor ?: return@update current
                    current.copy(
                        editor =
                            editor.copy(
                                options =
                                    (editor.original + editor.selected + options).distinctBy {
                                        it.id
                                    },
                                loading = false,
                                failed = false,
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(
                        editor = it.editor?.copy(loading = false, failed = true),
                    )
                }
            }
        }
    }

    fun search(query: String) {
        val editor = state().editor ?: return
        if (state().submitting) return
        if (editor.property == WorkProperty.Reviewers) {
            if (query == editor.query) return
            if (!editor.loading && !editor.needsReconcile) editorJob?.cancel()
            update { current ->
                current.copy(
                    editor =
                        current.editor?.copy(
                            query = query,
                            options = emptyList(),
                            nextCursor = null,
                            loadingMore = false,
                            moreFailed = false,
                            hasReviewerCandidates = false,
                        ),
                )
            }
            return
        }
        if (editor.property.isSearchBacked()) {
            searchItems(editor.property, query)
            return
        }
        update { current ->
            val active = current.editor ?: return@update current
            current.copy(editor = active.copy(query = query))
        }
    }

    private fun searchItems(property: WorkProperty, query: String) {
        val editor = state().editor ?: return
        if (state().submitting || editor.property != property) return
        editorJob?.cancel()
        update {
            it.copy(
                editor =
                    it.editor?.copy(
                        query = query,
                        options = if (editor.query == query) editor.options else emptyList(),
                        loading = true,
                        failed = false,
                        nextCursor = null,
                        loadingMore = false,
                        moreFailed = false,
                    ),
            )
        }
        editorJob = scope.launch {
            try {
                delay(250)
                val page = itemSearchPage(property, query, null)
                update { current ->
                    val active = current.editor ?: return@update current
                    if (active.query != query) return@update current
                    current.copy(
                        editor =
                            active.copy(
                                options = page.items,
                                nextCursor = page.nextCursor,
                                loading = false,
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(
                        editor = it.editor?.copy(loading = false, failed = true),
                    )
                }
            }
        }
    }

    fun more() {
        val editor = state().editor ?: return
        val reviewers = editor.property == WorkProperty.Reviewers
        val cursor = editor.nextCursor
        if (cursor == null && (!reviewers || editor.hasReviewerCandidates)) return
        if (
            state().submitting ||
                editor.loading ||
                editor.loadingMore ||
                editor.needsReconcile ||
                editor.failed
        )
            return
        update { it.copy(editor = editor.copy(loadingMore = true, moreFailed = false)) }
        editorJob = scope.launch {
            try {
                val page =
                    if (reviewers) {
                        if (cursor == null && editor.query.isNotBlank()) delay(250)
                        detail.reviewerCandidates(editor.query, cursor)
                    } else itemSearchPage(editor.property, editor.query, cursor)
                val items =
                    if (reviewers)
                        page.items.filterNot { it.title.equals(editor.reviewerAuthorLogin, true) }
                    else page.items
                update { current ->
                    val active = current.editor ?: return@update current
                    if (active.query != editor.query) return@update current
                    current.copy(
                        editor =
                            active.copy(
                                options = (active.options + items).distinctBy { it.id },
                                hasReviewerCandidates = reviewers,
                                nextCursor = page.nextCursor,
                                loadingMore = false,
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(
                        editor = it.editor?.copy(loadingMore = false, moreFailed = true),
                    )
                }
            }
        }
    }

    fun select(option: WorkPropertyOption) {
        if (state().submitting || !option.editable) return
        update { current ->
            val editor = current.editor ?: return@update current
            if (!editor.editable || editor.loading || editor.failed || editor.needsReconcile) {
                return@update current
            }
            if (editor.original.any { it.id == option.id && !it.editable }) return@update current
            val selected =
                when {
                    editor.selected.any { it.id == option.id } ->
                        editor.selected.filterNot { it.id == option.id }
                    editor.property == WorkProperty.Milestone -> listOf(option)
                    else -> editor.selected + option
                }
            current.copy(editor = editor.copy(selected = selected))
        }
    }

    private fun submit(
        property: WorkProperty,
        original: List<WorkPropertyOption>,
        selected: List<WorkPropertyOption>,
    ): Boolean {
        val key = detail.keys.property(property)
        val write = coord.current(key)
        if (write.phase == WritePhase.PENDING || write.phase == WritePhase.UNCERTAIN) return false
        return detail.submitProperty(property, original, selected)
    }

    fun applyEditor(saveImmediately: Boolean = false) {
        val current = state()
        val editor = current.editor ?: return
        if (
            current.submitting ||
                !editor.editable ||
                editor.loading ||
                editor.failed ||
                editor.needsReconcile
        )
            return
        val property = editor.property
        if (
            saveImmediately ||
                property == WorkProperty.Milestone ||
                property == WorkProperty.Reviewers
        ) {
            if (
                editor.reviewRequestsChanged() &&
                    !submit(property, editor.original, editor.selected)
            ) {
                update { it.copy(saveFailed = true) }
                return
            }
            editorJob?.cancel()
            update { it.copy(editor = null, drafts = it.drafts - property, saveFailed = false) }
            return
        }
        val actual =
            coord.current(detail.keys.property(property)).confirmed
                ?: current.value?.values?.get(property).orEmpty()
        val drafts = current.drafts.toMutableMap()
        if (editor.selected.map { it.id }.toSet() == actual.map { it.id }.toSet()) {
            drafts.remove(property)
        } else {
            drafts[property] = editor.selected
        }
        editorJob?.cancel()
        update { it.copy(editor = null, drafts = drafts) }
    }

    fun saveAll() {
        val current = state()
        val value = current.value ?: return
        if (current.editor != null) return
        val remaining = current.drafts.toMutableMap()
        for ((property, selected) in current.drafts) {
            val actual =
                coord.current(detail.keys.property(property)).confirmed
                    ?: value.values[property].orEmpty()
            if (
                actual.map { it.id }.toSet() == selected.map { it.id }.toSet() ||
                    submit(property, actual, selected)
            ) {
                remaining.remove(property)
            }
        }
        update {
            it.copy(
                drafts = remaining,
                saved = remaining.isEmpty(),
                saveFailed = remaining.isNotEmpty(),
            )
        }
    }

    fun acknowledgeUncertain() {
        val opId = state().uncertainOperationId ?: return
        scope.launch {
            val coord = detail.mutations()
            coord.acknowledge(opId).join()
            if (
                coord.pending.value.any { it.operationId == opId } ||
                    state().uncertainOperationId != opId
            )
                return@launch
            update {
                it.copy(
                    uncertain = false,
                    uncertainOperationId = null,
                    saveFailed = false,
                    editor = it.editor?.copy(needsReconcile = false),
                )
            }
        }
    }

    fun consumeSaved() {
        if (state().saved) update { it.copy(saved = false) }
    }

    private suspend fun itemSearchPage(
        property: WorkProperty,
        query: String,
        cursor: String?,
    ) =
        when (property) {
            WorkProperty.LinkedItems -> detail.linkedOptions(query, cursor)
            WorkProperty.SubIssues -> detail.subIssueOptions(query, cursor)
            else -> error("Property does not use search")
        }
}

private fun WorkProperty.isSearchBacked() =
    this == WorkProperty.LinkedItems || this == WorkProperty.SubIssues
