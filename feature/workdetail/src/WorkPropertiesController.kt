package moe.yumeyuka.yumehub.feature.workdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
                val value = detail.properties()
                update { it.copy(value = value, loading = false, failed = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun edit(property: WorkProperty) {
        val current = state()
        val value = current.value ?: return
        if (current.submitting || !value.editable) return
        if (current.editor?.property == property) return
        val original = value.values[property].orEmpty()
        val selected = current.drafts[property] ?: original
        editorJob?.cancel()
        update { it.copy(editor = WorkPropertyEditor(property, original, selected)) }
        loadOptions(property)
    }

    fun closeEditor() {
        if (state().submitting) return
        editorJob?.cancel()
        update { it.copy(editor = null) }
    }

    fun retryEditor() {
        val editor = state().editor ?: return
        if (state().submitting) return
        editorJob?.cancel()
        update {
            it.copy(
                editor = it.editor?.copy(loading = true, failed = false, needsReconcile = false),
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
                                    options = (actual + active.options).distinctBy { it.id },
                                    loading = false,
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
        val cursor = editor.nextCursor ?: return
        if (state().submitting || editor.loading || editor.loadingMore) return
        update { it.copy(editor = editor.copy(loadingMore = true, moreFailed = false)) }
        editorJob = scope.launch {
            try {
                val page = itemSearchPage(editor.property, editor.query, cursor)
                update { current ->
                    val active = current.editor ?: return@update current
                    if (active.query != editor.query) return@update current
                    current.copy(
                        editor =
                            active.copy(
                                options = (active.options + page.items).distinctBy { it.id },
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
            if (editor.loading || editor.needsReconcile) return@update current
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
        if (current.submitting || editor.loading || editor.failed || editor.needsReconcile) return
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
