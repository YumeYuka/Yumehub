package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.CollaborationItemDto
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.locale.WorkDetailStrings
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.GithubBrandIcon
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.SearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.SectionHeader
import moe.yumeyuka.yumehub.ui.component.formatActivityTime
import moe.yumeyuka.yumehub.ui.component.labelColor
import moe.yumeyuka.yumehub.ui.component.workStatusVisual
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.IconTilePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder
import moe.yumeyuka.yumehub.ui.component.MutationAcknowledgementDialog
import moe.yumeyuka.yumehub.ui.theme.WorkColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 工作项信息：评审、标签、里程碑、链接项与项目共用同一套选择面板。 */
@Composable
internal fun WorkInfoSheet(
    visible: Boolean,
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
    item: CollaborationItemDto?,
    titleEditable: Boolean = false,
    onSubmitTitle: (String) -> Unit = {},
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
    onOpenBaseBranch: (() -> Unit)?,
    onOpenIssueFields: () -> Unit,
    onDismiss: () -> Unit,
) {
    var opened by remember { mutableStateOf(false) }
    SideEffect { if (visible) opened = true }
    if (!visible && !opened) return
    val strings = LocalAppStrings.current.WorkDetail
    val common = LocalAppStrings.current.Common
    val scroll = rememberScrollState()
    val title =
        if (kind == CollaborationKind.PullRequest) strings.PullRequestInformation
        else strings.IssueInformation
    val viewModel = workInfoViewModel(owner, name, kind, number, login)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(visible, viewModel) { if (visible) viewModel.load() }
    LaunchedEffect(visible, state.saved, viewModel) {
        if (visible && state.saved) {
            viewModel.consumeSaved()
            onDismiss()
        }
    }
    if (state.failed) LoadFailureToast(Unit, strings.PropertyLoadFailed)
    if (state.saveFailed) LoadFailureToast(Unit, strings.PropertySaveFailed)
    // 保存写回在后台继续，弹窗提交期间不再禁止关闭与交互。
    AppBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        skipPartiallyExpanded = false,
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        AppIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    if (state.value != null) {
                        GlasmioIconButton(
                            onClick = { viewModel.saveAll() },
                            enabled = !state.submitting,
                        ) {
                            AppIcon(Lucide.Check, contentDescription = strings.SaveChanges)
                        }
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scroll)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item?.let { work ->
                val titleField = rememberTextFieldState(work.title)
                LaunchedEffect(work.title) {
                    if (titleField.text.toString() != work.title) {
                        titleField.setTextAndPlaceCursorAtEnd(work.title)
                    }
                }
                val trimmedTitle = titleField.text.toString().trim()
                val titleChanged = trimmedTitle.isNotEmpty() && trimmedTitle != work.title
                val submitTitleChange = {
                    if (titleEditable && !state.submitting && titleChanged) {
                        onSubmitTitle(trimmedTitle)
                    }
                }
                SectionHeader(strings.EditTitle)
                GlasmioGroupedList {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicTextField(
                            state = titleField,
                            modifier = Modifier.weight(1f),
                            enabled = titleEditable && !state.submitting,
                            textStyle =
                                GlasmioTheme.textStyles.body.copy(
                                    color = GlasmioTheme.colors.content,
                                    fontWeight = FontWeight.Medium,
                                ),
                            cursorBrush = SolidColor(GlasmioTheme.colors.accent),
                            lineLimits =
                                TextFieldLineLimits.MultiLine(
                                    minHeightInLines = 1,
                                    maxHeightInLines = 3,
                                ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            onKeyboardAction = { submitTitleChange() },
                        )
                        if (titleChanged) {
                            GlasmioIconButton(
                                onClick = submitTitleChange,
                                enabled = titleEditable && !state.submitting,
                            ) {
                                AppIcon(
                                    Lucide.Check,
                                    contentDescription = strings.SaveChanges,
                                    size = 18.dp,
                                )
                            }
                        }
                    }
                }
            }
            WorkInfoSections(
                state,
                viewModel,
                kind,
                item,
                "$owner/$name",
                onOpenWork,
                onOpenBaseBranch,
                onOpenIssueFields,
            )
        }
    }
    if (visible) WorkPropertySelectionSheet(state, viewModel, onDismiss = {})
}

@Composable
private fun workInfoViewModel(
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
): WorkInfoViewModel =
    koinViewModel(
        key = "work-info-$login-$owner-$name-${kind.name}-$number",
        parameters = { parametersOf(owner, name, kind, number, login) },
    )

/** 头部 chip 直接打开单个属性的选择面板，不再先展开信息 sheet。 */
@Composable
internal fun WorkPropertySheet(
    visible: Boolean,
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
    property: WorkProperty?,
    onDismiss: () -> Unit,
) {
    if (!visible || property == null) return
    val strings = LocalAppStrings.current.WorkDetail
    val viewModel = workInfoViewModel(owner, name, kind, number, login)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (property == WorkProperty.Reviewers) {
        WorkReviewRequestsSheet(state, viewModel, onDismiss)
        return
    }
    LaunchedEffect(viewModel) { viewModel.load() }
    var requested by remember { mutableStateOf<WorkProperty?>(null) }
    LaunchedEffect(property, state.value, viewModel) {
        if (state.value != null && requested != property) {
            requested = property
            viewModel.edit(property)
        }
    }
    if (state.failed) LoadFailureToast(Unit, strings.PropertyLoadFailed)
    if (state.saveFailed) LoadFailureToast(Unit, strings.PropertySaveFailed)
    WorkPropertySelectionSheet(
        state,
        viewModel,
        onDismiss,
        saveImmediately = true,
        requestedProperty = property,
    )
}

@Composable
private fun WorkInfoSections(
    state: WorkPropertiesState,
    viewModel: WorkInfoViewModel,
    kind: CollaborationKind,
    item: CollaborationItemDto?,
    repo: String,
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
    onOpenBaseBranch: (() -> Unit)?,
    onOpenIssueFields: () -> Unit,
) {
    val strings = LocalAppStrings.current.WorkDetail
    item?.let {
        WorkInfoDetailsSection(it)
        if (kind == CollaborationKind.PullRequest) WorkInfoStatusSection(it, kind, onOpenBaseBranch)
    }
    if (kind == CollaborationKind.Issue && item?.viewerCanSeeIssueFields == true) {
        WorkEditableSection(
            title = strings.IssueFields,
            editable = state.issueFields?.editable == true && !state.submitting,
            onEdit = onOpenIssueFields,
        ) {
            val fields = state.issueFields?.fields
            if (state.fieldsFailed) WorkSheetRetry(viewModel::retryFields)
            LoadingContent(
                loading = fields == null && !state.fieldsFailed,
                placeholder = { WorkInfoRowPlaceholder() },
            ) {
                if (fields != null) {
                    if (fields.isEmpty()) {
                        GlasmioListRow(title = strings.NoIssueFieldValue, showDisclosure = false)
                    } else {
                        fields.forEachIndexed { index, field ->
                            GlasmioListRow(
                                title = field.name,
                                detail =
                                    field.value?.let { field.displayValue(it) }
                                        ?: strings.NoIssueFieldValue,
                                showTopSeparator = index > 0,
                                showDisclosure = false,
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.referencesFailed || !state.references.isNullOrEmpty()) {
        ListSection(strings.ReferencedItems) {
            if (state.referencesFailed) WorkSheetRetry(viewModel::retryReferences)
            state.references?.forEachIndexed { index, option ->
                WorkLinkedItemRow(option, repo, index > 0, onOpenWork)
            }
        }
    }
    val value = state.value
    if (state.failed && value != null) GlasmioGroupedList { WorkSheetRetry(viewModel::retry) }
    if (value == null) {
        if (!state.failed) {
            workInfoPlaceholders(kind, strings)
        } else {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = LocalAppStrings.current.Common.Retry,
                    subtitle = strings.PropertyLoadFailed,
                    showTopSeparator = false,
                    onClick = viewModel::retry,
                )
            }
        }
        return
    }
    val milestone = state.valuesFor(WorkProperty.Milestone).singleOrNull()?.title
    WorkEditableSection(
        title = strings.Milestone,
        editable = value.editable && !state.submitting,
        onEdit = { viewModel.edit(WorkProperty.Milestone) },
    ) {
        if (milestone == null) {
            GlasmioListRow(title = strings.NoMilestone, showDisclosure = false)
        } else {
            GlasmioListRow(
                title = milestone,
                leading = { AppIcon(Lucide.Milestone, contentDescription = null, size = 20.dp) },
                showTopSeparator = false,
                showDisclosure = false,
            )
        }
    }
    WorkLinkedSection(
        title = strings.LinkedItems,
        values = state.valuesFor(WorkProperty.LinkedItems),
        empty = strings.NoLinkedItems,
        editable = value.editable,
        submitting = state.submitting,
        onEdit = { viewModel.edit(WorkProperty.LinkedItems) },
        repo = repo,
        onOpenWork = onOpenWork,
    )
    if (kind == CollaborationKind.Issue) {
        WorkLinkedSection(
            title = strings.SubIssues,
            values = state.valuesFor(WorkProperty.SubIssues),
            empty = strings.NoSubIssues,
            editable = value.editable,
            submitting = state.submitting,
            onEdit = { viewModel.edit(WorkProperty.SubIssues) },
            repo = repo,
            onOpenWork = onOpenWork,
        )
    }
}

@Composable
private fun WorkSheetRetry(onRetry: () -> Unit) {
    GlasmioListRow(
        title = LocalAppStrings.current.Common.Retry,
        subtitle = LocalAppStrings.current.WorkDetail.PropertyLoadFailed,
        onClick = onRetry,
    )
}

@Composable
private fun WorkInfoStatusSection(
    item: CollaborationItemDto,
    kind: CollaborationKind,
    onOpenBaseBranch: (() -> Unit)?,
) {
    val all = LocalAppStrings.current
    val strings = all.WorkDetail
    val total = item.requiredStatusCheckCount ?: 0
    val checks = item.requiredStatusChecks
    val passed = checks.count { it.state == "SUCCESS" }
    val failed = checks.any { it.state == "FAILURE" || it.state == "ERROR" }
    val pending = checks.any {
        it.state == "PENDING" || it.state == "EXPECTED" || it.state == "IN_PROGRESS"
    }
    val checksColor =
        when {
            item.allStatusChecksPassed -> WorkColors.open
            failed -> WorkColors.closed
            pending -> WorkColors.draft
            else -> GlasmioTheme.colors.secondaryContent
        }
    val checksIcon =
        when {
            item.allStatusChecksPassed -> Lucide.CircleCheck
            failed -> Lucide.CircleSlash
            else -> Lucide.CircleDot
        }
    val reviewColor =
        when (item.reviewDecision) {
            "APPROVED" -> WorkColors.open
            "CHANGES_REQUESTED" -> WorkColors.closed
            else -> GlasmioTheme.colors.secondaryContent
        }
    ListSection(strings.Status) {
        if (kind == CollaborationKind.PullRequest) {
            GlasmioListRow(
                title = strings.ReviewResults,
                detail = strings.ReviewDecision(item.reviewDecision.orEmpty()),
                leading = {
                    AppIcon(
                        Lucide.MessageCircleCheck,
                        contentDescription = null,
                        size = 20.dp,
                        tint = reviewColor,
                    )
                },
                showTopSeparator = false,
            )
        }
        GlasmioListRow(
            title = strings.Checks,
            detail = if (total > 0) "$passed/$total" else null,
            leading = {
                AppIcon(checksIcon, contentDescription = null, size = 20.dp, tint = checksColor)
            },
            showTopSeparator = kind == CollaborationKind.PullRequest,
        )
    }
    val head = item.head?.ref?.takeIf(String::isNotBlank)
    val base = item.baseRefName?.takeIf(String::isNotBlank)
    if (head == null && base == null) return
    val branchColor = workStatusVisual(kind, item.state, item.draft, item.merged).color
    ListSection(all.Workspaces.Branches) {
        head?.let { ref ->
            GlasmioListRow(
                title = ref,
                leading = {
                    AppIcon(
                        Lucide.Feather,
                        contentDescription = all.WorkList.HeadBranch,
                        size = 20.dp,
                        tint = branchColor,
                    )
                },
                showTopSeparator = false,
            )
        }
        base?.let { ref ->
            GlasmioListRow(
                title = ref,
                leading = {
                    AppIcon(
                        Lucide.CornerDownRight,
                        contentDescription = all.WorkList.BaseBranch,
                        size = 20.dp,
                        tint = branchColor,
                    )
                },
                showTopSeparator = head != null,
                onClick = onOpenBaseBranch,
            )
        }
    }
}

@Composable
private fun WorkInfoDetailsSection(item: CollaborationItemDto) {
    val all = LocalAppStrings.current
    val login = item.user?.login?.takeIf(String::isNotBlank) ?: all.Workspaces.UnknownAuthor
    GlasmioGroupedList {
        GlasmioListRow(
            title = all.WorkDetail.Author,
            leading = {
                AppIcon(
                    Lucide.AtSign,
                    contentDescription = null,
                    size = 20.dp,
                    tint = GlasmioTheme.colors.secondaryContent,
                )
            },
            showTopSeparator = false,
            showDisclosure = false,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UserAvatar(item.user?.avatarUrl, login, size = 20.dp)
                    Text(
                        text = login,
                        style = GlasmioTheme.textStyles.body,
                        color = GlasmioTheme.colors.secondaryContent,
                    )
                }
            },
        )
        GlasmioListRow(
            title = all.WorkDetail.CreatedAt,
            detail = item.createdAt?.let(::formatActivityTime),
            leading = {
                AppIcon(
                    Lucide.Clock,
                    contentDescription = null,
                    size = 20.dp,
                    tint = GlasmioTheme.colors.secondaryContent,
                )
            },
            showDisclosure = false,
        )
    }
}

@Composable
private fun WorkLinkedSection(
    title: String,
    values: List<WorkPropertyOption>,
    empty: String,
    editable: Boolean,
    submitting: Boolean,
    onEdit: () -> Unit,
    repo: String,
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
) {
    WorkEditableSection(title, editable && !submitting, onEdit) {
        if (values.isEmpty()) {
            GlasmioListRow(title = empty, showDisclosure = false)
        } else {
            values.forEachIndexed { index, option ->
                WorkLinkedItemRow(option, repo, index > 0, onOpenWork)
            }
        }
    }
}

@Composable
private fun WorkEditableSection(
    title: String,
    editable: Boolean,
    onEdit: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ListSection(
        title = title,
        trailing =
            if (editable) {
                {
                    AppIcon(
                        Lucide.Pencil,
                        modifier =
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onEdit,
                            ),
                        contentDescription = LocalAppStrings.current.WorkDetail.EditProperty,
                        size = 14.dp,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                }
            } else {
                null
            },
        content = content,
    )
}

/** 复用提交详情「关联 PR」的行样式：品牌图标、编号与状态图标，点击直接跳转。 */
@Composable
private fun WorkLinkedItemRow(
    option: WorkPropertyOption,
    repo: String,
    showTopSeparator: Boolean,
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
) {
    val kind =
        if (option.kind == "PullRequest") CollaborationKind.PullRequest else CollaborationKind.Issue
    val number = option.number
    val owner = option.subtitle?.takeIf { it.contains('/') }?.substringBefore('/')
    val name = option.subtitle?.takeIf { it.contains('/') }?.substringAfter('/')
    val visual = workStatusVisual(kind, option.state, merged = option.state == "MERGED")
    GlasmioListRow(
        title = option.title,
        subtitle = option.subtitle?.takeIf { !it.equals(repo, ignoreCase = true) },
        showTopSeparator = showTopSeparator,
        showDisclosure = false,
        leading = {
            AppIcon(
                GithubBrandIcon,
                contentDescription = null,
                tint = GlasmioTheme.colors.secondaryContent,
            )
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                number?.let {
                    Text(
                        "#$it",
                        style = GlasmioTheme.textStyles.body,
                        color = GlasmioTheme.colors.secondaryContent,
                        maxLines = 1,
                    )
                }
                AppIcon(
                    visual.icon,
                    contentDescription = null,
                    size = 18.dp,
                    tint = visual.color,
                )
            }
        },
        onClick =
            if (number != null && owner != null && name != null) {
                { onOpenWork(owner, name, kind, number) }
            } else {
                null
            },
    )
}

@Composable
private fun workInfoPlaceholders(
    kind: CollaborationKind,
    strings: WorkDetailStrings,
) {
    WorkInfoSectionPlaceholder(strings.Milestone, showArrow = false)
    WorkInfoSectionPlaceholder(strings.LinkedItems)
    if (kind == CollaborationKind.Issue) {
        WorkInfoSectionPlaceholder(strings.SubIssues)
    }
}

@Composable
private fun WorkInfoSectionPlaceholder(title: String, showArrow: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        GlasmioGroupedList { WorkInfoRowPlaceholder(showArrow = showArrow) }
    }
}

@Composable
internal fun WorkInfoRowPlaceholder(
    showTopSeparator: Boolean = false,
    showArrow: Boolean = false,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(PlaceholderTokens.ListRowHeight)
                .padding(horizontal = GlasmioListRowDefaults.horizontalPadding)
                .glasmioGroupedListSeparator(
                    color = GlasmioTheme.colors.separator,
                    visible = showTopSeparator,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTilePlaceholder(20.dp, corner = 4.dp)
        TextPlaceholder(Modifier.weight(1f), height = 14.dp, fraction = 0.55f)
        if (showArrow) {
            TextPlaceholder(Modifier.width(12.dp), height = 12.dp, fraction = 1f)
        }
    }
}

@Composable
private fun WorkPropertySelectionSheet(
    state: WorkPropertiesState,
    viewModel: WorkInfoViewModel?,
    onDismiss: () -> Unit,
    saveImmediately: Boolean = false,
    requestedProperty: WorkProperty? = null,
) {
    val live =
        state.editor?.takeIf { requestedProperty == null || it.property == requestedProperty }
    // 保存成功后编辑器即被清空；保留最近一次快照，退出动画期间内容不变成空白。
    var retained by remember(requestedProperty) { mutableStateOf<WorkPropertyEditor?>(null) }
    SideEffect { if (live != null) retained = live }
    val editor = live ?: retained
    val strings = LocalAppStrings.current.WorkDetail
    if (state.saveFailed) LoadFailureToast(Unit, strings.PropertySaveFailed)
    if (editor?.failed == true || editor?.needsReconcile == true || editor?.moreFailed == true) {
        LoadFailureToast(
            Unit,
            if (editor.needsReconcile) strings.PropertySaveFailed else strings.PropertyLoadFailed,
        )
    }
    SearchSelectionSheet(
        // visible 保持 true 直到收起动画结束：动画完成后 onDismissRequest 才触发，
        // 由那里清空 retained 与调用方状态，收起期间内容用快照渲染。
        visible = requestedProperty != null || live != null || retained != null,
        title = (editor?.property ?: requestedProperty)?.selectionTitle(strings).orEmpty(),
        placeholder = strings.PropertySearch,
        queryKey = editor?.query.orEmpty(),
        onQueryChange = { viewModel?.search(it) },
        onDismiss = {
            viewModel?.closeEditor()
            retained = null
            onDismiss()
        },
        onConfirm = { viewModel?.applyEditor(saveImmediately) },
        showConfirm = editor?.editable == true,
        confirmEnabled =
            live != null &&
                !live.loading &&
                !live.failed &&
                !live.needsReconcile &&
                !state.submitting,
        requestClose = live == null && retained != null,
        loading =
            editor?.loading == true || editor == null && !(state.failed && state.value == null),
        loadingPlaceholder = {
            GlasmioGroupedList {
                repeat(3) { index -> WorkInfoRowPlaceholder(showTopSeparator = index > 0) }
            }
        },
    ) {
        if (editor == null) {
            if (state.failed && state.value == null) {
                item(key = "work-property-load-retry") {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = LocalAppStrings.current.Common.Retry,
                            subtitle = strings.PropertyLoadFailed,
                            showTopSeparator = false,
                            onClick = { viewModel?.retry() },
                        )
                    }
                }
            }
            return@SearchSelectionSheet
        }
        if (!editor.loading && (editor.failed || editor.needsReconcile)) {
            item(key = "work-property-retry") {
                var acknowledgeVisible by remember { mutableStateOf(false) }
                val common = LocalAppStrings.current.Common
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = if (state.uncertain) common.MutationReconcile else common.Retry,
                        subtitle =
                            when {
                                state.uncertain -> common.MutationUncertain
                                editor.needsReconcile -> strings.PropertySaveFailed
                                else -> strings.PropertyLoadFailed
                            },
                        onClick = { viewModel?.retryEditor() },
                    )
                    if (state.uncertain) {
                        GlasmioListRow(
                            title = common.MutationAcknowledged,
                            showTopSeparator = true,
                            showDisclosure = false,
                            onClick = { acknowledgeVisible = true },
                        )
                    }
                }
                if (acknowledgeVisible && state.uncertain) {
                    MutationAcknowledgementDialog(
                        onDismiss = { acknowledgeVisible = false },
                        onAcknowledge = {
                            acknowledgeVisible = false
                            viewModel?.acknowledgeUncertain()
                        },
                    )
                }
            }
            return@SearchSelectionSheet
        }
        when {
            editor.loading -> Unit
            else -> {
                val query = editor.query.trim()
                val options =
                    (editor.original + editor.options + editor.selected).distinctBy { it.id }
                val visible =
                    if (
                        editor.property == WorkProperty.LinkedItems ||
                            editor.property == WorkProperty.SubIssues ||
                            query.isEmpty()
                    )
                        options
                    else
                        options.filter { option ->
                            option.title.contains(query, ignoreCase = true) ||
                                option.subtitle?.contains(query, ignoreCase = true) == true
                        }
                val hasMore =
                    (editor.property == WorkProperty.LinkedItems ||
                        editor.property == WorkProperty.SubIssues) && editor.nextCursor != null
                val rows: List<WorkPropertyOption?> =
                    visible +
                        if (hasMore) {
                            listOf(null)
                        } else {
                            emptyList()
                        }
                if (rows.isEmpty()) {
                    item(key = "work-property-empty") {
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title = strings.NoPropertyOptions,
                                showDisclosure = false,
                            )
                        }
                    }
                } else if (editor.property == WorkProperty.Labels) {
                    item(key = "work-property-labels") {
                        GlasmioGroupedList {
                            visible.forEachIndexed { index, option ->
                                key(option.id) {
                                    WorkPropertyOptionRow(
                                        editor.property,
                                        option,
                                        checked = editor.selected.any { it.id == option.id },
                                        showTopSeparator = index > 0,
                                        onSelect =
                                            if (editor.editable && !state.submitting) {
                                                viewModel?.let { vm -> { vm.select(option) } }
                                            } else null,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        rows,
                        key = { _, row -> row?.id ?: "work-property-more" },
                    ) { index, option ->
                        // 单张连续卡片：每行自己绘制与首尾对应的圆角，滚动时仍是一整块。
                        GlasmioGroupedList(
                            framed = false,
                            modifier =
                                Modifier.glasmioGroupedListBackground(
                                    ContinuousRoundedRectangle(
                                        topStart = if (index == 0) 26.dp else 0.dp,
                                        topEnd = if (index == 0) 26.dp else 0.dp,
                                        bottomStart = if (index == rows.lastIndex) 26.dp else 0.dp,
                                        bottomEnd = if (index == rows.lastIndex) 26.dp else 0.dp,
                                    ),
                                ),
                        ) {
                            if (option == null) {
                                if (editor.moreFailed) {
                                    WorkSheetRetry { viewModel?.more() }
                                } else {
                                    LaunchedEffect(editor.query, editor.nextCursor) {
                                        viewModel?.more()
                                    }
                                    WorkInfoRowPlaceholder(showTopSeparator = index > 0)
                                }
                            } else {
                                WorkPropertyOptionRow(
                                    editor.property,
                                    option,
                                    checked = editor.selected.any { it.id == option.id },
                                    showTopSeparator = index > 0,
                                    onSelect =
                                        if (editor.editable && !state.submitting) {
                                            viewModel?.let { vm -> { vm.select(option) } }
                                        } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkPropertyOptionRow(
    property: WorkProperty,
    option: WorkPropertyOption,
    checked: Boolean,
    showTopSeparator: Boolean,
    onSelect: (() -> Unit)?,
) {
    GlasmioListRow(
        title = option.title,
        subtitle = option.subtitle,
        modifier = Modifier.semantics { selected = checked },
        showTopSeparator = showTopSeparator,
        showDisclosure = false,
        leading = { propertyOptionIcon(property, option) },
        trailing = {
            Box(Modifier.width(23.dp), contentAlignment = Alignment.Center) {
                if (checked) AppIcon(Lucide.Check, contentDescription = null)
            }
        },
        onClick = onSelect?.takeIf { option.editable },
    )
}

@Composable
private fun propertyOptionIcon(property: WorkProperty, option: WorkPropertyOption) {
    when (property) {
        WorkProperty.Reviewers -> {
            val avatar = option.avatarUrl?.takeIf(String::isNotBlank)
            if (avatar != null) UserAvatar(avatar, option.title, size = 20.dp)
            else AppIcon(Lucide.UsersRound, contentDescription = null, size = 20.dp)
        }
        WorkProperty.Labels ->
            AppIcon(
                Lucide.Tag,
                contentDescription = null,
                size = 20.dp,
                tint = labelColor(option.color),
            )
        WorkProperty.Milestone ->
            AppIcon(Lucide.Milestone, contentDescription = null, size = 20.dp)
        WorkProperty.LinkedItems -> LinkedItemIcon(option)
        WorkProperty.SubIssues ->
            AppIcon(Lucide.ListPlus, contentDescription = null, size = 20.dp)
        WorkProperty.Projects ->
            AppIcon(Lucide.FolderKanban, contentDescription = null, size = 20.dp)
        WorkProperty.Assignees -> {
            val avatar = option.avatarUrl?.takeIf(String::isNotBlank)
            if (avatar != null) UserAvatar(avatar, option.title, size = 20.dp)
            else AppIcon(Lucide.UsersRound, contentDescription = null, size = 20.dp)
        }
    }
}

@Composable
private fun LinkedItemIcon(option: WorkPropertyOption) {
    val closed = option.state == "CLOSED"
    val merged = option.state == "MERGED"
    AppIcon(
        imageVector =
            when {
                merged -> Lucide.GitMerge
                option.kind == "PullRequest" && closed -> Lucide.GitPullRequestClosed
                option.kind == "PullRequest" -> Lucide.GitPullRequest
                closed -> Lucide.CircleCheck
                else -> Lucide.CircleDot
            },
        contentDescription = null,
        size = 20.dp,
        tint =
            when {
                merged -> WorkColors.merged
                closed -> WorkColors.closed
                else -> WorkColors.open
            },
    )
}

internal fun WorkProperty.title(strings: WorkDetailStrings) =
    when (this) {
        WorkProperty.Reviewers -> strings.RequestedReviewers
        WorkProperty.Assignees -> strings.Assignees
        WorkProperty.Labels -> strings.Labels
        WorkProperty.Milestone -> strings.Milestone
        WorkProperty.LinkedItems -> strings.LinkedItems
        WorkProperty.SubIssues -> strings.SubIssues
        WorkProperty.Projects -> strings.Projects
    }

private fun WorkProperty.selectionTitle(strings: WorkDetailStrings) =
    if (this == WorkProperty.Reviewers) strings.RequestReview else title(strings)
