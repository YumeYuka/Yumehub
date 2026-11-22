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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.GitBranch
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.AssigneeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTemplateOptionDto
import moe.yumeyuka.yumehub.data.api.dto.IssueTypeOptionDto
import moe.yumeyuka.yumehub.data.api.dto.MilestoneOptionDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubChoiceSheet
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

@Composable
internal fun WorkCreationSheet(
    kind: CollaborationKind,
    visible: Boolean,
    branchPicker: BranchPickerState,
    issueTypes: List<IssueTypeOptionDto>,
    issueTemplates: List<IssueTemplateOptionDto>,
    milestones: List<MilestoneOptionDto>,
    labels: List<CollaborationLabelDto>,
    assigneePicker: AssigneePickerState,
    onDismiss: () -> Unit,
    onCreate:
        suspend (
            title: String,
            body: String,
            base: String,
            head: String,
            issueTypeId: String?,
            milestoneId: String?,
            labelIds: List<String>,
            assigneeIds: List<String>,
            issueTemplate: String?,
        ) -> Int,
    onBranchQueryChange: (String) -> Unit,
    onBranchRetry: () -> Unit,
    onBranchLoadMore: () -> Unit,
    onAssigneeQueryChange: (String) -> Unit,
    onAssigneeRetry: () -> Unit,
    onAssigneeLoadMore: () -> Unit,
    onCreated: (Int) -> Unit,
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
    val scope = rememberCoroutineScope()
    var branchTarget by rememberSaveable(visible) { mutableStateOf<BranchTarget?>(null) }
    var subject by rememberSaveable(visible) { mutableStateOf("") }
    var body by rememberSaveable(visible) { mutableStateOf("") }
    var base by rememberSaveable(visible) { mutableStateOf("") }
    var head by rememberSaveable(visible) { mutableStateOf("") }
    var issueTypeId by rememberSaveable(visible) { mutableStateOf<String?>(null) }
    var issueTemplate by remember(visible) { mutableStateOf<IssueTemplateOptionDto?>(null) }
    var preTemplateDraft by remember(visible) { mutableStateOf<IssueCreationDraft?>(null) }
    var milestoneId by rememberSaveable(visible) { mutableStateOf<String?>(null) }
    var labelIds by rememberSaveable(visible) { mutableStateOf(emptyList<String>()) }
    var draftLabelIds by rememberSaveable(visible) { mutableStateOf(emptyList<String>()) }
    var labelsVisible by rememberSaveable(visible) { mutableStateOf(false) }
    var labelQuery by rememberSaveable(visible) { mutableStateOf("") }
    var selectedAssignees by remember(visible) { mutableStateOf(emptyList<AssigneeOptionDto>()) }
    var draftAssignees by remember(visible) { mutableStateOf(emptyList<AssigneeOptionDto>()) }
    var assigneesVisible by rememberSaveable(visible) { mutableStateOf(false) }
    var submitting by rememberSaveable(visible) { mutableStateOf(false) }
    var failure by rememberSaveable(visible) { mutableStateOf(false) }
    val noIssueType = IssueTypeOptionDto("", work.NoIssueType)
    val selectedIssueType = issueTypes.firstOrNull { it.id == issueTypeId } ?: noIssueType
    val issueTypeChoices = listOf(noIssueType) + issueTypes
    val noMilestone = MilestoneOptionDto("", work.NoMilestone)
    val selectedMilestone = milestones.firstOrNull { it.id == milestoneId } ?: noMilestone
    val milestoneChoices = listOf(noMilestone) + milestones
    LaunchedEffect(visible, kind, onBranchQueryChange) {
        if (visible && kind == CollaborationKind.PullRequest) onBranchQueryChange("")
    }
    LaunchedEffect(visible, branchPicker.defaultBranch) {
        if (visible && base.isBlank()) base = branchPicker.defaultBranch.orEmpty()
    }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        dismissOnBackPress = !submitting,
        dismissOnClickOutside = !submitting,
        fitContent = true,
        title = title,
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !submitting) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled =
                            !submitting &&
                                subject.isNotBlank() &&
                                (kind == CollaborationKind.Issue ||
                                    base.isNotBlank() && head.isNotBlank() && base != head),
                        onClick = {
                            submitting = true
                            failure = false
                            scope.launch {
                                try {
                                    val number =
                                        onCreate(
                                            subject.trim(),
                                            body,
                                            base.trim(),
                                            head.trim(),
                                            issueTypeId,
                                            milestoneId,
                                            labelIds,
                                            selectedAssignees.map(AssigneeOptionDto::id),
                                            issueTemplate?.name,
                                        )
                                    onDismiss()
                                    onCreated(number)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    failure = true
                                    submitting = false
                                }
                            }
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
                .verticalScroll(rememberScrollState())
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
                                            subject = draft.title
                                            body = draft.body
                                            issueTypeId = draft.issueTypeId
                                            labelIds = draft.labelIds
                                            selectedAssignees = draft.assignees
                                        }
                                        preTemplateDraft = null
                                        issueTemplate = null
                                    } else {
                                        if (issueTemplate == null) {
                                            preTemplateDraft =
                                                IssueCreationDraft(
                                                    title = subject,
                                                    body = body,
                                                    issueTypeId = issueTypeId,
                                                    labelIds = labelIds,
                                                    assignees = selectedAssignees,
                                                )
                                        }
                                        issueTemplate = selected
                                        subject = selected.title.orEmpty()
                                        body = selected.body.orEmpty()
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
                { subject = it },
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
                    { base = it },
                    submitting,
                    work.BaseBranch,
                    { branchTarget = BranchTarget.Base },
                )
                WorkCreationBranchField(
                    work.HeadBranch,
                    head,
                    { head = it },
                    submitting,
                    work.HeadBranch,
                    { branchTarget = BranchTarget.Head },
                )
            }
            WorkCreationField(work.CreationBody, body, { body = it }, submitting)
            if (failure) {
                GlasmioGroupedList {
                    GlasmioListRow(title = work.CreationFailed, systemName = null)
                }
            }
        }
    }
    GithubChoiceSheet(
        visible = branchTarget != null,
        title = branchStrings.Branches,
        placeholder = branchStrings.BranchSearchPlaceholder,
        choices = branchPicker.branches,
        query = branchPicker.query,
        selectedValue =
            when (branchTarget) {
                BranchTarget.Base -> base.takeIf(String::isNotBlank)?.let { "refs/heads/$it" }
                BranchTarget.Head -> head.takeIf(String::isNotBlank)?.let { "refs/heads/$it" }
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
                BranchTarget.Base -> base = branch
                BranchTarget.Head -> head = branch
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
    value: String,
    onValueChange: (String) -> Unit,
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
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier.weight(1f).padding(vertical = 16.dp).heightIn(min = 24.dp).semantics {
                        contentDescription = label
                    },
                enabled = !busy,
                textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
                cursorBrush = SolidColor(colors.accent),
                singleLine = true,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
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
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    singleLine: Boolean = false,
) {
    val colors = GlasmioTheme.colors
    GlasmioGroupedList {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = if (singleLine) 24.dp else 120.dp)
                    .semantics { contentDescription = label },
            enabled = !busy,
            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            maxLines = if (singleLine) 1 else 8,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment =
                        if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty()) {
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
