package moe.yumeyuka.yumehub.feature.worklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.GitBranch
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.WorkCreationDraft
import androidx.compose.runtime.snapshotFlow
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTemplateOptionDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTypeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.MilestoneOptionDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubChoiceSheet
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.replaceFormText
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

@Composable
internal fun WorkCreationSheet(
    kind: CollaborationKind,
    visible: Boolean,
    creationState: WorkCreationState,
    branchPicker: BranchPickerState,
    issueTypes: List<IssueTypeOptionDto>,
    issueTemplates: List<IssueTemplateOptionDto>,
    milestones: List<MilestoneOptionDto>,
    labels: List<CollaborationLabelDto>,
    assigneePicker: AssigneePickerState,
    onDraftChange: (WorkCreationDraft) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (WorkCreationDraft) -> Unit,
    onAcknowledgeUncertain: () -> Unit,
    onBranchQueryChange: (String) -> Unit,
    onBranchRetry: () -> Unit,
    onBranchLoadMore: () -> Unit,
    onAssigneeQueryChange: (String) -> Unit,
    onAssigneeRetry: () -> Unit,
    onAssigneeLoadMore: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val work = strings.WorkList
    val branchStrings = strings.Workspaces
    val availableLabels =
        remember(labels, issueTemplates) {
            (labels + issueTemplates.flatMap(IssueTemplateOptionDto::labels)).distinctBy {
                it.id ?: it.name
            }
        }
    val title =
        if (kind == CollaborationKind.Issue) strings.Repository.NewIssue
        else strings.Repository.NewPullRequest
    var branchTarget by rememberSaveable { mutableStateOf<BranchTarget?>(null) }
    val ready = creationState.draftRestored
    val saved = creationState.draft
    val subject = remember(visible, ready) { TextFieldState(saved.title) }
    val body = remember(visible, ready) { TextFieldState(saved.body) }
    val base = remember(visible, ready) { TextFieldState(saved.base) }
    val head = remember(visible, ready) { TextFieldState(saved.head) }
    var issueTypeId by remember(visible, ready) { mutableStateOf(saved.issueTypeId) }
    var templateName by remember(visible, ready) { mutableStateOf(saved.issueTemplate) }
    val issueTemplate = issueTemplates.firstOrNull { it.name == templateName }
    var preTemplateDraft by remember(visible, ready) { mutableStateOf<IssueCreationDraft?>(null) }
    var milestoneId by remember(visible, ready) { mutableStateOf(saved.milestoneId) }
    var labelIds by remember(visible, ready) { mutableStateOf(saved.labelIds) }
    var draftLabelIds by remember { mutableStateOf(emptyList<String>()) }
    var labelsVisible by rememberSaveable { mutableStateOf(false) }
    var labelQuery by rememberSaveable { mutableStateOf("") }
    var selectedAssignees by remember(visible, ready) { mutableStateOf(saved.assignees) }
    var draftAssignees by remember { mutableStateOf(emptyList<AssigneeOptionDto>()) }
    var assigneesVisible by rememberSaveable { mutableStateOf(false) }
    var acknowledgeVisible by remember { mutableStateOf(false) }
    val submitting = creationState.submitting
    val uncertain = creationState.uncertain
    val failure = creationState.failed
    fun draft() = WorkCreationDraft(subject.text.toString(), body.text.toString(), base.text.toString(), head.text.toString(),
        issueTypeId, milestoneId, labelIds, selectedAssignees, templateName)
    LaunchedEffect(visible, ready, submitting, uncertain) {
        if (visible && ready && !submitting && !uncertain) snapshotFlow { draft() }.collect(onDraftChange)
    }
    val noIssueType = IssueTypeOptionDto("", work.NoIssueType)
    val selectedIssueType = issueTypes.firstOrNull { it.id == issueTypeId } ?: noIssueType
    val issueTypeChoices = listOf(noIssueType) + issueTypes
    val noMilestone = MilestoneOptionDto("", work.NoMilestone)
    val selectedMilestone = milestones.firstOrNull { it.id == milestoneId } ?: noMilestone
    val milestoneChoices = listOf(noMilestone) + milestones
    LaunchedEffect(visible, kind, onBranchQueryChange) {
        if (visible && kind == CollaborationKind.PullRequest) onBranchQueryChange("")
    }
    LaunchedEffect(visible, ready, branchPicker.defaultBranch) {
        if (visible && ready && base.text.isBlank()) base.replaceFormText(branchPicker.defaultBranch.orEmpty())
    }
    val scroll = rememberScrollState()
    val fadeDistance = with(LocalDensity.current) { 56.dp.toPx() }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        fitContent = true,
        title = title,
        topBarBlurProgress = (scroll.value / fadeDistance).coerceIn(0f, 1f),
        topBar = {
            GlasmioSheetTopToolbar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled =
                            ready && !submitting &&
                                !uncertain &&
                                subject.text.isNotBlank() &&
                                (kind == CollaborationKind.Issue ||
                                    base.text.isNotBlank() && head.text.isNotBlank() &&
                                        !base.text.contentEquals(head.text)),
                        onClick = {
                            onSubmit(draft())
                        },
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
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
            if (kind == CollaborationKind.Issue && issueTemplates.isNotEmpty()) {
                val noTemplate =
                    IssueTemplateOptionDto(
                        name = "",
                        title = null,
                        body = null,
                        labels = emptyList(),
                        assignees = emptyList(),
                        issueTypeId = null,
                    )
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = work.IssueTemplate,
                        systemName = null,
                        trailing = {
                            GlasmioPopUpButton(
                                selected = issueTemplate ?: noTemplate,
                                items = listOf(noTemplate) + issueTemplates,
                                onSelected = { selected ->
                                    if (selected.name.isBlank()) {
                                        preTemplateDraft?.let { draft ->
                                            subject.replaceFormText(draft.title)
                                            body.replaceFormText(draft.body)
                                            issueTypeId = draft.issueTypeId
                                            labelIds = draft.labelIds
                                            selectedAssignees = draft.assignees
                                        }
                                        preTemplateDraft = null
                                        templateName = null
                                    } else {
                                        if (issueTemplate == null) {
                                            preTemplateDraft =
                                                IssueCreationDraft(
                                                    title = subject.text.toString(),
                                                    body = body.text.toString(),
                                                    issueTypeId = issueTypeId,
                                                    labelIds = labelIds,
                                                    assignees = selectedAssignees,
                                                )
                                        }
                                        templateName = selected.name
                                        subject.replaceFormText(selected.title.orEmpty())
                                        body.replaceFormText(selected.body.orEmpty())
                                        issueTypeId = selected.issueTypeId
                                        labelIds = selected.labels.mapNotNull { it.id }
                                        selectedAssignees = selected.assignees
                                    }
                                },
                                label = { it.name.ifBlank { work.NoIssueTemplate } },
                                enabled = !submitting,
                            )
                        },
                    )
                }
            }
            WorkCreationField(
                work.CreationTitle,
                subject,
                submitting,
                singleLine = true,
            )
            if (kind == CollaborationKind.Issue && issueTypes.isNotEmpty()) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = work.IssueType,
                        systemName = null,
                        trailing = {
                            GlasmioPopUpButton(
                                selected = selectedIssueType,
                                items = issueTypeChoices,
                                onSelected = { issueTypeId = it.id.takeIf(String::isNotBlank) },
                                label = { it.name },
                                enabled = !submitting,
                            )
                        },
                    )
                }
            }
            if (kind == CollaborationKind.Issue && milestones.isNotEmpty()) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = work.Milestone,
                        systemName = null,
                        trailing = {
                            GlasmioPopUpButton(
                                selected = selectedMilestone,
                                items = milestoneChoices,
                                onSelected = { milestoneId = it.id.takeIf(String::isNotBlank) },
                                label = { it.title },
                                enabled = !submitting,
                            )
                        },
                    )
                }
            }
            if (kind == CollaborationKind.Issue && availableLabels.isNotEmpty()) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = work.Labels,
                        subtitle =
                            availableLabels
                                .filter { it.id in labelIds }
                                .joinToString { it.name }
                                .ifEmpty { work.NoLabels },
                        systemName = null,
                        onClick = {
                            draftLabelIds = labelIds
                            labelQuery = ""
                            labelsVisible = true
                        },
                        enabled = !submitting,
                    )
                }
            }
            if (kind == CollaborationKind.Issue) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.WorkDetail.Assignees,
                        subtitle =
                            selectedAssignees.joinToString { it.login }.ifEmpty { work.NoAssignee },
                        systemName = null,
                        onClick = {
                            draftAssignees = selectedAssignees
                            onAssigneeQueryChange("")
                            assigneesVisible = true
                        },
                        enabled = !submitting,
                    )
                }
            }
            if (kind == CollaborationKind.PullRequest) {
                WorkCreationBranchField(
                    work.BaseBranch,
                    base,
                    submitting,
                    work.BaseBranch,
                    { branchTarget = BranchTarget.Base },
                )
                WorkCreationBranchField(
                    work.HeadBranch,
                    head,
                    submitting,
                    work.HeadBranch,
                    { branchTarget = BranchTarget.Head },
                )
            }
            WorkCreationField(work.CreationBody, body, submitting || uncertain)
            if (submitting) {
                GlasmioGroupedList {
                    GlasmioListRow(title = strings.Common.MutationPending, systemName = null)
                }
            } else if (uncertain) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Common.MutationUncertain,
                        subtitle = creationState.errorMessage ?: strings.Common.MutationNotSubmitted,
                        systemName = null,
                    )
                    GlasmioListRow(
                        title = strings.Common.MutationAcknowledged,
                        showTopSeparator = true,
                        showDisclosure = false,
                        onClick = { acknowledgeVisible = true },
                    )
                }
            } else if (failure) {
                GlasmioGroupedList {
                    GlasmioListRow(title = work.CreationFailed, systemName = null)
                }
            }
        }
    }
    if (acknowledgeVisible && uncertain) {
        GlasmioAlertDialog(
            visible = true,
            title = strings.Common.MutationUncertain,
            message = strings.Common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        strings.Common.Cancel,
                        onClick = { acknowledgeVisible = false },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        strings.Common.MutationAcknowledged,
                        onClick = {
                            acknowledgeVisible = false
                            onAcknowledgeUncertain()
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeVisible = false },
        )
    }
    GithubChoiceSheet(
        visible = branchTarget != null,
        title = branchStrings.Branches,
        placeholder = branchStrings.BranchSearchPlaceholder,
        choices = branchPicker.branches,
        query = branchPicker.query,
        selectedValue =
            when (branchTarget) {
                BranchTarget.Base -> base.text.takeIf { it.isNotBlank() }?.let { "refs/heads/$it" }
                BranchTarget.Head -> head.text.takeIf { it.isNotBlank() }?.let { "refs/heads/$it" }
                null -> null
            },
        loading = branchPicker.loading && branchPicker.branches.isEmpty(),
        hasMore = branchPicker.hasMore,
        error = branchStrings.BranchesLoadFailed.takeIf { branchPicker.failed },
        empty =
            if (branchPicker.query.isEmpty()) branchStrings.NoBranches
            else branchStrings.NoMatchingBranches,
        onDismiss = { branchTarget = null },
        onRetry = onBranchRetry,
        onLoadMore = onBranchLoadMore,
        onQueryChange = onBranchQueryChange,
        onSelect = { selected ->
            val branch = selected.removePrefix("refs/heads/")
            when (branchTarget) {
                BranchTarget.Base -> base.replaceFormText(branch)
                BranchTarget.Head -> head.replaceFormText(branch)
                null -> Unit
            }
            branchTarget = null
        },
        label = { it.removePrefix("refs/heads/") },
    )
    GithubSearchSelectionSheet(
        visible = labelsVisible,
        title = work.Labels,
        placeholder = work.Labels,
        queryKey = labelQuery,
        onQueryChange = { labelQuery = it },
        onDismiss = { labelsVisible = false },
        onConfirm = {
            labelIds = draftLabelIds
            labelsVisible = false
        },
        enabled = !submitting,
    ) {
        val filteredLabels = availableLabels.filter {
            it.name.contains(labelQuery.trim(), ignoreCase = true)
        }
        items(filteredLabels, key = { it.id.orEmpty() }) { label ->
            val id = label.id ?: return@items
            val checked = id in draftLabelIds
            GlasmioGroupedList {
                GlasmioListRow(
                    title = label.name,
                    modifier = Modifier.semantics { selected = checked },
                    onClick = {
                        draftLabelIds = if (checked) draftLabelIds - id else draftLabelIds + id
                    },
                    trailing = { if (checked) GithubIcon(Lucide.Check, contentDescription = null) },
                )
            }
        }
    }
    if (assigneesVisible && assigneePicker.failed) {
        GithubLoadFailureToast(Unit, strings.WorkDetail.PropertyLoadFailed)
    }
    val visibleAssignees =
        (assigneePicker.assignees + draftAssignees).distinctBy(AssigneeOptionDto::id)
    GithubSearchSelectionSheet(
        visible = assigneesVisible,
        title = strings.WorkDetail.Assignees,
        placeholder = strings.WorkDetail.PropertySearch,
        queryKey = assigneePicker.query,
        onQueryChange = onAssigneeQueryChange,
        onDismiss = { assigneesVisible = false },
        onConfirm = {
            selectedAssignees = draftAssignees
            assigneesVisible = false
        },
        enabled = !submitting,
    ) {
        when {
            assigneePicker.loading && visibleAssignees.isEmpty() ->
                item {
                    GlasmioGroupedList {
                        repeat(4) { index -> TitleRowPlaceholder(showTopSeparator = index > 0) }
                    }
                }
            assigneePicker.failed && visibleAssignees.isEmpty() ->
                item {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.Common.Retry,
                            onClick = onAssigneeRetry,
                        )
                    }
                }
            !assigneePicker.loading && visibleAssignees.isEmpty() && !assigneePicker.hasMore ->
                item {
                    GlasmioGroupedList {
                        GlasmioListRow(title = strings.WorkDetail.NoPropertyOptions)
                    }
                }
            else -> {
                items(visibleAssignees, key = AssigneeOptionDto::id) { assignee ->
                    val checked = draftAssignees.any { it.id == assignee.id }
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = assignee.name?.takeIf(String::isNotBlank) ?: assignee.login,
                            subtitle = assignee.login.takeIf { !assignee.name.isNullOrBlank() },
                            modifier = Modifier.semantics { selected = checked },
                            onClick = {
                                draftAssignees =
                                    if (checked) draftAssignees.filterNot { it.id == assignee.id }
                                    else draftAssignees + assignee
                            },
                            trailing = {
                                if (checked) GithubIcon(Lucide.Check, contentDescription = null)
                            },
                        )
                    }
                }
                if (assigneePicker.loadingMore) {
                    item(key = "assignee-loading-more") {
                        GlasmioGroupedList { TitleRowPlaceholder(showTopSeparator = true) }
                    }
                } else if (assigneePicker.hasMore && !assigneePicker.failed) {
                    item(key = "assignee-next-page-${assigneePicker.cursor}") {
                        LaunchedEffect(
                            assigneesVisible,
                            assigneePicker.query,
                            assigneePicker.cursor,
                        ) {
                            if (assigneesVisible) onAssigneeLoadMore()
                        }
                        GlasmioGroupedList { TitleRowPlaceholder(showTopSeparator = true) }
                    }
                }
                if (assigneePicker.failed && visibleAssignees.isNotEmpty()) {
                    item(key = "assignee-retry") {
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = strings.Common.Retry,
                                onClick = onAssigneeRetry,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class IssueCreationDraft(
    val title: String,
    val body: String,
    val issueTypeId: String?,
    val labelIds: List<String>,
    val assignees: List<AssigneeOptionDto>,
)

private enum class BranchTarget {
    Base,
    Head,
}

@Composable
private fun WorkCreationBranchField(
    label: String,
    state: TextFieldState,
    busy: Boolean,
    branchPickerLabel: String,
    onPickBranch: () -> Unit,
) {
    val colors = GlasmioTheme.colors
    GlasmioGroupedList {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                state = state,
                modifier =
                    Modifier.weight(1f).padding(vertical = 16.dp).heightIn(min = 24.dp).semantics {
                        contentDescription = label
                    },
                enabled = !busy,
                textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.SingleLine,
                decorator = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty()) {
                            Text(
                                label,
                                style = GlasmioTheme.textStyles.body,
                                color = colors.tertiaryContent,
                            )
                        }
                        inner()
                    }
                },
            )
            GlasmioIconButton(onClick = onPickBranch, enabled = !busy) {
                GithubIcon(
                    Lucide.GitBranch,
                    contentDescription = branchPickerLabel,
                    size = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun WorkCreationField(
    label: String,
    state: TextFieldState,
    busy: Boolean,
    singleLine: Boolean = false,
) {
    val colors = GlasmioTheme.colors
    GlasmioGroupedList {
        BasicTextField(
            state = state,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = if (singleLine) 24.dp else 120.dp)
                    .semantics { contentDescription = label },
            enabled = !busy,
            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            lineLimits =
                if (singleLine) TextFieldLineLimits.SingleLine
                else TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8),
            decorator = { inner ->
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment =
                        if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(
                            label,
                            style = GlasmioTheme.textStyles.body,
                            color = colors.tertiaryContent,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
