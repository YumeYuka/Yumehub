package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.WorkDetailRepository

internal class WorkPropertiesController(
    private val detail: WorkDetailRepository.Detail,
    private val scope: CoroutineScope,
    private val state: () -> WorkPropertiesState,
    private val update: ((WorkPropertiesState) -> WorkPropertiesState) -> Unit,
    private val loadIssueFields: Boolean,
) {
    private var loadJob: Job? = null
    private var editorJob: Job? = null
    private var saveJob: Job? = null

    fun load() {
        val current = state()
        if (current.value != null || current.loading || current.failed) return
        refresh()
    }

    fun refresh() {
        if (state().submitting) return
        loadJob?.cancel()
        update { it.copy(loading = true, failed = false) }
        loadJob = scope.launch {
            try {
                coroutineScope {
                    val value = async { detail.properties() }
                    // 引用列表只读，失败不影响属性面板本身。
                    val references = async {
                        try {
                            detail.crossReferences()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            emptyList<WorkPropertyOption>()
                        }
                    }
                    // 议题字段只属于 Issue，失败时不影响属性面板。
                    val fields = async {
                        if (!loadIssueFields) return@async null
                        try {
                            detail.issueFields()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val props = value.await()
                    val refs = references.await()
                    val issues = fields.await()
                    update { it.copy(value = props, references = refs, issueFields = issues, loading = false, failed = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(loading = false, failed = true) }
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
        if (current.submitting || (!editable && property != WorkProperty.Labels)) return
        if (current.editor?.property == property) return
        val original = value.values[property].orEmpty()
        val selected = current.drafts[property] ?: original
        editorJob?.cancel()
        update {
            it.copy(
                editor = WorkPropertyEditor(
                    property,
                    original,
                    selected,
                    loading = editable,
                    editable = editable,
                )
            )
        }
        if (editable) loadOptions(property)
    }


    fun closeEditor() {
        // 保存写入在独立的 saveJob 中继续，关闭编辑器不会取消进行中的网络写回。
        editorJob?.cancel()
        update { it.copy(editor = null) }
    }

    fun retryEditor() {
        val editor = state().editor ?: return
        if (state().submitting) return
        if (editor.property == WorkProperty.Reviewers) {
            if (editor.needsReconcile) {
                editorJob?.cancel()
                update { it.copy(editor = it.editor?.copy(loading = true, failed = false)) }
                editorJob = scope.launch {
                    val coord = detail.mutations()
                    val key = detail.propertyMutationKey(WorkProperty.Reviewers)
                    coord.reconcile(key) { detail.reviewRequests().requested.map { it.id } }
                    val opId = coord.findUncertainOperationId(key.kind, key.target)
                    update { it.copy(uncertain = opId != null, uncertainOperationId = opId) }
                    loadReviewRequests(editor)
                }
                return
            }
            loadReviewRequests(editor)
            return
        }
        editorJob?.cancel()
        update {
            it.copy(
                editor = it.editor?.copy(loading = true, failed = false),
            )
        }
        if (editor.needsReconcile) {
            editorJob = scope.launch {
                try {
                    val coord = detail.mutations()
                    val key = detail.propertyMutationKey(editor.property)
                    coord.reconcile(key) {
                        val fresh = detail.properties()
                        fresh.values[editor.property].orEmpty().map { it.id }
                    }
                    val value = detail.properties()
                    val opId = coord.findUncertainOperationId(key.kind, key.target)
                    update { current ->
                        val active = current.editor ?: return@update current
                        val actual = value.values[active.property].orEmpty()
                        val editable = if (active.property == WorkProperty.Labels) value.labelsEditable else value.editable
                        current.copy(
                            value = value,
                            uncertain = opId != null,
                            uncertainOperationId = opId,
                            editor =
                                active.copy(
                                    original = actual,
                                    selected = if (editable) active.selected else actual,
                                    options = (actual + active.options).distinctBy { it.id },
                                    loading = false,
                                    needsReconcile = opId != null,
                                    editable = editable,
                                ),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update { it.copy(editor = it.editor?.copy(loading = false, failed = true)) }
                }
            }
            return
        }
        loadOptions(editor.property)
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
                            choices.filter { it.editable && it.id in added && !it.title.equals(data.authorLogin, true) })
                        .distinctBy { it.id }
                update { current ->
                    val active = current.editor?.takeIf { it.property == WorkProperty.Reviewers }
                        ?: return@update current
                    current.copy(
                        editor =
                            active.copy(
                                original = data.requested,
                                selected = if (data.editable) selected else data.requested,
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
                                needsReconcile = false,
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { current ->
                    val active = current.editor?.takeIf { it.property == WorkProperty.Reviewers }
                        ?: return@update current
                    current.copy(editor = active.copy(loading = false, failed = true))
                }
            }
        }
    }

    private fun loadOptions(property: WorkProperty) {
        if (property.isSearchBacked()) {
            searchItems(property, "")
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
                current.copy(editor = current.editor?.copy(query = query, options = emptyList(),
                    nextCursor = null, loadingMore = false, moreFailed = false, hasReviewerCandidates = false))
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
        if (state().submitting || editor.loading || editor.loadingMore || editor.needsReconcile || editor.failed) return
        update { it.copy(editor = editor.copy(loadingMore = true, moreFailed = false)) }
        editorJob = scope.launch {
            try {
                val page = if (reviewers) {
                    if (cursor == null && editor.query.isNotBlank()) delay(250)
                    detail.reviewerCandidates(editor.query, cursor)
                } else itemSearchPage(editor.property, editor.query, cursor)
                val items = if (reviewers) page.items.filterNot { it.title.equals(editor.reviewerAuthorLogin, true) } else page.items
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

    private fun WorkPropertiesState.withConfirmed(property: WorkProperty, candidates: List<WorkPropertyOption>, ids: List<String>?): WorkPropertiesState {
        val base = value ?: return this
        if (ids == null) return this
        val byId = candidates.associateBy { it.id }
        return copy(value = base.copy(values = base.values + (property to ids.mapNotNull(byId::get))))
    }

    fun applyEditor(saveImmediately: Boolean = false) {
        val current = state()
        val editor = current.editor ?: return
        if (
            current.submitting || !editor.editable || editor.loading || editor.failed ||
                editor.needsReconcile
        ) return
        val property = editor.property
        val reviewers = property == WorkProperty.Reviewers
        val persist = saveImmediately || property == WorkProperty.Milestone || reviewers
        if (persist) {
            if (editor.original.mapTo(hashSetOf()) { it.id } == editor.selected.mapTo(hashSetOf()) { it.id }) {
                closeEditor()
                return
            }
            loadJob?.cancel()
            editorJob?.cancel()
            update { it.copy(loading = false, submitting = true, saveFailed = false) }
            saveJob?.cancel()
            saveJob = scope.launch {
                try {
                    val coord = detail.mutations()
                    val key = detail.propertyMutationKey(property)
                    val desiredIds = editor.selected.map { it.id }
                    val result =
                        coord.submitAndAwait(
                            key = key,
                            confirmed = editor.original.map { it.id },
                            desired = desiredIds,
                            tags = detail.mutationTags,
                            send = { _ ->
                                if (reviewers) detail.saveReviewRequests(editor.original, editor.selected)
                                else detail.saveProperty(property, editor.original, editor.selected) { coord.confirmStep(key, it) }
                                if (reviewers) detail.reviewRequests().requested.map { it.id }
                                else detail.properties().values.getValue(property).map { it.id }
                            },
                            readBack = {
                                if (reviewers) detail.reviewRequests().requested.map { it.id }
                                else detail.properties().values.getValue(property).map { it.id }
                            },
                        )
                    if (result.phase == WritePhase.IDLE) {
                        update { latest ->
                            val value = latest.value
                            latest.copy(
                                value = value?.let {
                                    if (reviewers) it else it.copy(values = it.values + (property to (editor.original + editor.selected).distinctBy { option -> option.id }.filter { option -> option.id in result.confirmed.orEmpty() }))
                                },
                                drafts = latest.drafts - property,
                                editor = null,
                                submitting = false,
                                saveFailed = false,
                                uncertain = false,
                                uncertainOperationId = null,
                            )
                        }
                    } else {
                        val isUncertain = result.phase == WritePhase.UNCERTAIN
                        val opId = if (isUncertain) coord.findUncertainOperationId(key.kind, key.target) else null
                        update { latest ->
                            val partial = latest.withConfirmed(property, editor.original + editor.selected, result.confirmed)
                            if (latest.editor != null) {
                                partial.copy(
                                    submitting = false,
                                    uncertain = isUncertain,
                                    uncertainOperationId = opId,
                                    editor = latest.editor.copy(original = partial.value?.values?.get(property) ?: editor.original, needsReconcile = true),
                                )
                            } else {
                                partial.copy(
                                    drafts = partial.drafts + (property to editor.selected),
                                    submitting = false,
                                    saveFailed = true,
                                    uncertain = isUncertain,
                                    uncertainOperationId = opId,
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // 编辑器仍在时保留选择供重新核对；用户已关闭则只报失败。
                    update { latest ->
                        if (latest.editor != null)
                            latest.copy(submitting = false, editor = latest.editor.copy(needsReconcile = true))
                        else latest.copy(submitting = false, saveFailed = true)
                    }
                }
            }
            return
        }
        val actual = current.value?.values?.get(property).orEmpty()
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
        if (current.submitting || current.editor != null) return
        val value = current.value ?: return
        val pending =
            current.drafts.filter { (property, selected) ->
                selected.map { it.id }.toSet() !=
                    value.values[property].orEmpty().map { it.id }.toSet()
            }
        if (pending.isEmpty()) {
            update { it.copy(saved = true) }
            return
        }
        editorJob?.cancel()
        loadJob?.cancel()
        update { it.copy(loading = false, submitting = true, saveFailed = false, uncertain = false) }
        scope.launch {
            try {
                val coord = detail.mutations()
                for ((property, selected) in pending) {
                    val actual = state().value?.values?.get(property).orEmpty()
                    val key = detail.propertyMutationKey(property)
                    val desiredIds = selected.map { it.id }
                    val result =
                        coord.submitAndAwait(
                            key = key,
                            confirmed = actual.map { it.id },
                            desired = desiredIds,
                            tags = detail.mutationTags,
                            send = { _ ->
                                detail.saveProperty(property, actual, selected) { coord.confirmStep(key, it) }
                                detail.properties().values.getValue(property).map { it.id }
                            },
                            readBack = {
                                detail.properties().values.getValue(property).map { it.id }
                            },
                        )
                    if (result.phase == WritePhase.IDLE) {
                        update { latest ->
                            latest.copy(
                                value = latest.value?.let { value -> value.copy(values = value.values + (property to (actual + selected).distinctBy { it.id }.filter { it.id in result.confirmed.orEmpty() })) },
                                drafts = latest.drafts - property,
                            )
                        }
                    } else {
                        val isUncertain = result.phase == WritePhase.UNCERTAIN
                        val opId = if (isUncertain) coord.findUncertainOperationId(key.kind, key.target) else null
                        update {
                            it.withConfirmed(property, actual + selected, result.confirmed).copy(
                                submitting = false,
                                saveFailed = true,
                                uncertain = isUncertain,
                                uncertainOperationId = opId,
                            )
                        }
                        return@launch
                    }
                }
                update {
                    it.copy(
                        drafts = emptyMap(),
                        submitting = false,
                        uncertain = false,
                        uncertainOperationId = null,
                        saved = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                update { it.copy(submitting = false, saveFailed = true) }
            }
        }
    }

    fun acknowledgeUncertain() {
        val opId = state().uncertainOperationId ?: return
        scope.launch {
            val coord = detail.mutations()
            coord.acknowledge(opId).join()
            if (coord.pending.value.any { it.operationId == opId } || state().uncertainOperationId != opId) return@launch
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
