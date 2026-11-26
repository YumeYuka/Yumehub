package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.placeholders.TextLinePlaceholder
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors

@Composable
internal fun IssueMetadataSheet(
    visible: Boolean,
    state: IssueMetadataState,
    editor: IssueMetadataEditor,
) {
    val panel = state.panel
    val all = LocalGithubStrings.current
    val strings = all.WorkDetail
    val choices = state.choices
    val fields = state.fields
    var query by remember(panel) { mutableStateOf("") }
    var selected by remember(choices) { mutableStateOf(choices?.selected.orEmpty()) }
    var editing by remember(panel) { mutableStateOf<IssueField?>(null) }
    var acknowledgeVisible by remember(panel) { mutableStateOf(false) }
    val field = editing?.takeIf { candidate -> fields?.fields?.any { it == candidate } == true }
    LaunchedEffect(field) { if (field == null) editing = null }
    val title =
        when (panel) {
            IssueMetadataPanel.Type -> all.WorkList.IssueType
            IssueMetadataPanel.Assignees -> strings.Assignees
            IssueMetadataPanel.Fields -> strings.IssueFields
            null -> ""
        }
    GithubSearchSelectionSheet(
        visible = visible && panel != null && field == null,
        title = title,
        placeholder = strings.PropertySearch,
        queryKey = query,
        onQueryChange = { query = it },
        onDismiss = editor::dismiss,
        onConfirm = { editor.saveChoices(selected) },
        showConfirm = choices?.editable == true,
        confirmEnabled =
            !state.saving &&
                !state.uncertain &&
                !state.conflict &&
                choices != null &&
                selected.toSet() != choices.selected.toSet(),
    ) {
        if (panel == null) return@GithubSearchSelectionSheet
        if (state.loading && choices == null && fields == null) {
            item("loading") {
                GlasmioGroupedList {
                    TextLinePlaceholder(
                        GlasmioTheme.textStyles.body,
                        Modifier.padding(16.dp),
                        fraction = 0.55f,
                    )
                }
            }
        }
        if (state.loadFailed || state.conflict || state.saveFailed || state.uncertain) {
            item("error") {
                IssueMetadataError(
                    state = state,
                    onReload = { editor.open(panel) },
                    onAcknowledge = if (state.uncertain) ({ acknowledgeVisible = true }) else null,
                )
            }
        }
        if (choices != null) {
            val isType = panel == IssueMetadataPanel.Type
            val matches = choices.options.filter { it.title.matchesIssueQuery(it.subtitle, query) }
            item("choices") {
                GlasmioGroupedList {
                    matches.forEachIndexed { index, choice ->
                        val checked = selected.any { it.id == choice.id }
                        IssueChoiceRow(
                            choice.title,
                            choice.subtitle,
                            checked,
                            index > 0,
                            leading = {
                                if (isType) {
                                    GithubIcon(
                                        Lucide.CircleDot,
                                        contentDescription = null,
                                        size = 20.dp,
                                        tint = issueOptionColor(choice.color),
                                    )
                                } else GithubAvatar(choice.avatarUrl, choice.title, size = 20.dp)
                            },
                            onClick =
                                if (
                                    choices.editable &&
                                        choice.editable &&
                                        !state.saving &&
                                        !state.uncertain &&
                                        !state.conflict
                                ) {
                                    {
                                        editor.clearSaveError()
                                        selected =
                                            if (isType) {
                                                if (checked) emptyList() else listOf(choice)
                                            } else if (checked)
                                                selected.filterNot { it.id == choice.id }
                                            else selected + choice
                                    }
                                } else null,
                        )
                    }
                    if (matches.isEmpty()) {
                        GlasmioListRow(title = strings.NoPropertyOptions, showDisclosure = false)
                    }
                }
            }
        }
        if (fields != null)
            issueFieldRows(fields, query) { candidate ->
                if (!state.saving) {
                    editor.clearSaveError()
                    query = ""
                    editing = candidate
                }
            }
    }
    if (field != null && fields != null) {
        key(field.id) {
            IssueFieldValueSheet(
                visible = visible,
                field = field,
                editable = fields.editable,
                state = state,
                onDismiss = {
                    editor.clearSaveError()
                    editing = null
                },
                onReload = {
                    editing = null
                    editor.open(IssueMetadataPanel.Fields)
                },
                onAcknowledge = if (state.uncertain) ({ acknowledgeVisible = true }) else null,
                onSave = { editor.saveField(field, it) },
            )
        }
    }
    if (visible && acknowledgeVisible && state.uncertain) {
        GlasmioAlertDialog(
            visible = true,
            title = all.Common.MutationUncertain,
            message = all.Common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        all.Common.Cancel,
                        onClick = { acknowledgeVisible = false },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        all.Common.MutationAcknowledged,
                        onClick = {
                            acknowledgeVisible = false
                            editor.acknowledgeUncertain()
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeVisible = false },
        )
    }
}

private fun LazyListScope.issueFieldRows(
    fields: IssueFields,
    query: String,
    onOpen: (IssueField) -> Unit,
) {
    val matching = fields.fields.filter { it.name.matchesIssueQuery(it.description, query) }
    val current = matching.filter { it.value != null || it.pinned }
    val available =
        if (fields.editable) matching.filter { it.value == null && !it.pinned } else emptyList()
    if (current.isNotEmpty()) {
        item("current") {
            GlasmioGroupedList {
                current.forEachIndexed { index, field -> IssueFieldRow(field, index > 0, onOpen) }
            }
        }
    }
    if (available.isNotEmpty()) {
        item("available") {
            GithubSection(LocalGithubStrings.current.WorkDetail.AddIssueField) {
                available.forEachIndexed { index, field -> IssueFieldRow(field, index > 0, onOpen) }
            }
        }
    }
    if (current.isEmpty() && available.isEmpty()) {
        item("empty") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = LocalGithubStrings.current.WorkDetail.NoPropertyOptions,
                    showDisclosure = false,
                )
            }
        }
    }
}

@Composable
private fun IssueFieldRow(field: IssueField, separated: Boolean, onOpen: (IssueField) -> Unit) {
    GlasmioListRow(
        title = field.name,
        subtitle = field.description,
        detail = field.value?.let { field.displayValue(it) },
        showTopSeparator = separated,
        leading = {
            val icon =
                when (field.kind) {
                    IssueFieldKind.Date -> Lucide.Calendar
                    IssueFieldKind.Number -> Lucide.Hash
                    IssueFieldKind.Text -> Lucide.FileText
                    IssueFieldKind.SingleSelect,
                    IssueFieldKind.MultiSelect -> Lucide.ListChecks
                }
            GithubIcon(icon, contentDescription = null, size = 20.dp)
        },
        onClick = { onOpen(field) },
    )
}

@Composable
private fun IssueFieldValueSheet(
    visible: Boolean,
    field: IssueField,
    editable: Boolean,
    state: IssueMetadataState,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onAcknowledge: (() -> Unit)?,
    onSave: (IssueFieldValue?) -> Boolean,
) {
    val all = LocalGithubStrings.current
    val strings = all.WorkDetail
    val scope = rememberCoroutineScope()
    val canEdit = editable && !state.saving && !state.conflict && !state.uncertain
    val sheet = rememberGlasmioSheetState()
    var text by
        rememberSaveable(field.id, field.value) {
            mutableStateOf(field.value?.let { field.displayValue(it) }.orEmpty())
        }
    var options by remember(field) { mutableStateOf(field.value.optionIds()) }
    val value =
        when (field.kind) {
            IssueFieldKind.Date ->
                text
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        try {
                            IssueFieldValue.Date(LocalDate.parse(it).toString())
                        } catch (_: DateTimeParseException) {
                            null
                        }
                    }
            IssueFieldKind.Number ->
                text
                    .trim()
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.let {
                        IssueFieldValue.Number(if (it == 0.0) 0.0 else it)
                    }
            IssueFieldKind.Text ->
                text.takeUnless(String::isBlank)?.let { IssueFieldValue.Text(it) }
            IssueFieldKind.SingleSelect ->
                options.singleOrNull()?.let { IssueFieldValue.SingleSelect(it) }
            IssueFieldKind.MultiSelect ->
                options
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        IssueFieldValue.MultiSelect(it)
                    }
        }
    val isChoice =
        field.kind == IssueFieldKind.SingleSelect || field.kind == IssueFieldKind.MultiSelect
    val valid = isChoice || text.isBlank() || value != null
    val scroll = rememberScrollState()
    val fadeDistance = with(LocalDensity.current) { 56.dp.toPx() }
    GlasmioBottomSheet(
        visible = visible,
        title = field.name,
        sheetState = sheet,
        fitContent = true,
        topBarBlurProgress = (scroll.value / fadeDistance).coerceIn(0f, 1f),
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        onDismissRequest = onDismiss,
        topBar = {
            GlasmioSheetTopToolbar(
                title = field.name,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = all.Common.Close)
                    }
                },
                actions = {
                    if (editable) {
                        GlasmioIconButton(
                            onClick = { if (onSave(value)) scope.launch { sheet.hide() } },
                            enabled = canEdit && valid && value != field.value,
                        ) {
                            GithubIcon(Lucide.Check, contentDescription = all.Common.Confirm)
                        }
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(scroll)
                .padding(LocalGlasmioSheetContentPadding.current)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.conflict || state.saveFailed || state.uncertain) {
                IssueMetadataError(state, onReload, onAcknowledge)
            }
            GlasmioGroupedList {
                if (!editable) {
                    GlasmioListRow(
                        title =
                            field.value?.let { field.displayValue(it) }
                                ?: strings.NoIssueFieldValue,
                        subtitle = field.description,
                        showDisclosure = false,
                    )
                } else if (isChoice) {
                    field.options.forEachIndexed { index, option ->
                        val checked = option.id in options
                        IssueChoiceRow(
                            option.title,
                            option.subtitle,
                            checked,
                            index > 0,
                            leading = {
                                GithubIcon(
                                    Lucide.CircleDot,
                                    contentDescription = null,
                                    size = 20.dp,
                                    tint = issueOptionColor(option.color),
                                )
                            },
                            onClick =
                                if (canEdit) {
                                    {
                                        options =
                                            when {
                                                checked -> options - option.id
                                                field.kind == IssueFieldKind.SingleSelect ->
                                                    setOf(option.id)
                                                else -> options + option.id
                                            }
                                    }
                                } else null,
                        )
                    }
                    if (field.options.isEmpty()) {
                        GlasmioListRow(title = strings.NoPropertyOptions, showDisclosure = false)
                    }
                } else {
                    GlasmioTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder =
                            if (field.kind == IssueFieldKind.Date) strings.IssueFieldDateFormat
                            else field.name,
                        enabled = canEdit,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    if (field.kind == IssueFieldKind.Number) KeyboardType.Decimal
                                    else KeyboardType.Text,
                            ),
                    )
                    if (!valid && field.kind == IssueFieldKind.Number) {
                        GlasmioListRow(
                            title = strings.IssueFieldNumberFormat,
                            showDisclosure = false,
                        )
                    }
                    if (text.isNotEmpty()) {
                        GlasmioListRow(
                            title = strings.ClearIssueFieldValue,
                            showTopSeparator = true,
                            showDisclosure = false,
                            onClick = if (canEdit) ({ text = "" }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueMetadataError(
    state: IssueMetadataState,
    onReload: () -> Unit,
    onAcknowledge: (() -> Unit)? = null,
) {
    val all = LocalGithubStrings.current
    val strings = all.WorkDetail
    val reload = state.loadFailed || state.conflict || state.uncertain
    GlasmioGroupedList {
        GlasmioListRow(
            title =
                when {
                    state.uncertain -> all.Common.MutationUncertain
                    state.conflict -> strings.PropertyChanged
                    state.loadFailed -> strings.PropertyLoadFailed
                    else -> strings.PropertySaveFailed
                },
            detail =
                if (state.uncertain) all.Common.MutationReconcile
                else if (reload) all.Common.Retry else null,
            onClick = onReload.takeIf { reload },
            showDisclosure = false,
        )
        if (state.uncertain && onAcknowledge != null) {
            GlasmioListRow(
                title = all.Common.MutationAcknowledged,
                showTopSeparator = true,
                showDisclosure = false,
                onClick = onAcknowledge,
            )
        }
    }
}

@Composable
private fun IssueChoiceRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    separated: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    GlasmioListRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.semantics { selected = checked },
        showTopSeparator = separated,
        showDisclosure = false,
        leading = leading,
        trailing = if (checked) ({ GithubIcon(Lucide.Check, contentDescription = null) }) else null,
        onClick = onClick,
    )
}

@Composable
internal fun issueOptionColor(color: String?) =
    when (color) {
        "BLUE" -> GithubWorkColors.branchForeground
        "GREEN" -> GithubWorkColors.open
        "RED" -> GithubWorkColors.closed
        "PURPLE" -> GithubWorkColors.merged
        "YELLOW",
        "ORANGE" -> GithubWorkColors.releasePrerelease
        else -> GlasmioTheme.colors.secondaryContent
    }

private fun String.matchesIssueQuery(description: String?, query: String): Boolean {
    val term = query.trim()
    return contains(term, ignoreCase = true) ||
        description?.contains(term, ignoreCase = true) == true
}

private fun IssueFieldValue?.optionIds(): Set<String> =
    when (this) {
        is IssueFieldValue.SingleSelect -> setOf(id)
        is IssueFieldValue.MultiSelect -> ids
        else -> emptySet()
    }

internal fun IssueField.displayValue(value: IssueFieldValue): String =
    when (value) {
        is IssueFieldValue.Date -> value.value
        is IssueFieldValue.Number ->
            java.math.BigDecimal.valueOf(value.value).stripTrailingZeros().toPlainString()
        is IssueFieldValue.Text -> value.value
        is IssueFieldValue.SingleSelect,
        is IssueFieldValue.MultiSelect -> {
            val ids = value.optionIds()
            options.filter { it.id in ids }.joinToString(", ") { it.title }
        }
    }
