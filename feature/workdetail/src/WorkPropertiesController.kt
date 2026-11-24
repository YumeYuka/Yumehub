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
        if (current.submitting || (!value.editable && property != WorkProperty.Labels)) return
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
                    loading = value.editable,
                    editable = value.editable,
                )
            )
        }
        if (value.editable) loadOptions(property)
    }

    fun closeEditor() {
        if (state().submitting) return
        editorJob?.cancel()
        update { it.copy(editor = null) }
    }

    fun retryEditor() {
        val editor = state().editor ?: return
        if (state().submitting) return
        if (editor.property == WorkProperty.Reviewers) {
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
                    val value = detail.properties()
                    update { current ->
                        val active = current.editor ?: return@update current
                        val actual = value.values[active.property].orEmpty()
                        current.copy(
                            value = value,
                            editor =
                                active.copy(
                                    original = actual,
                                    selected = if (value.editable) active.selected else actual,
                                    options = (actual + active.options).distinctBy { it.id },
                                    loading = false,
                                    needsReconcile = false,
                                    editable = value.editable,
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

    fun applyEditor() {
        val current = state()
        val editor = current.editor ?: return
        if (
            current.submitting || !editor.editable || editor.loading || editor.failed ||
                editor.needsReconcile
        ) return
        val milestone = editor.property == WorkProperty.Milestone
        if (milestone && editor.original.singleOrNull()?.id == editor.selected.singleOrNull()?.id) {
            closeEditor()
            return
        }
        if (milestone || editor.property == WorkProperty.Reviewers) {
            if (!milestone && !editor.reviewRequestsChanged()) return
            editorJob?.cancel()
            update { it.copy(submitting = true) }
            editorJob = scope.launch {
                try {
                    if (milestone) detail.saveMilestone(editor.original, editor.selected)
                    else detail.saveReviewRequests(editor.original, editor.selected)
                    update { latest ->
                        val value = latest.value
                        latest.copy(
                            value =
                                if (milestone && value != null) {
                                    value.copy(
                                        values = value.values + (editor.property to editor.selected),
                                    )
                                } else value,
                            editor = null,
                            submitting = false,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update {
                        it.copy(submitting = false, editor = editor.copy(needsReconcile = true))
                    }
                }
            }
            return
        }
        val actual = current.value?.values?.get(editor.property).orEmpty()
        val drafts = current.drafts.toMutableMap()
        if (editor.selected.map { it.id }.toSet() == actual.map { it.id }.toSet()) {
            drafts.remove(editor.property)
        } else {
            drafts[editor.property] = editor.selected
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
        val originals = pending.keys.associateWith { value.values[it].orEmpty() }
        detail.savePropertiesInBackground(pending, originals)
        update { it.copy(drafts = emptyMap(), saved = true) }
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
