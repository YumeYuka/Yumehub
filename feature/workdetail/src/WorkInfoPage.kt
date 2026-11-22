package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption
import moe.yumeyuka.yumehub.locale.GithubStrings_WorkDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.githubLabelColor
import moe.yumeyuka.yumehub.ui.component.placeholders.IconTilePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.component.placeholders.TextPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 工作项信息页：评审、标签、里程碑、链接项与项目共用同一套选择面板。 */
data class WorkInfoRoute(
    val owner: String,
    val name: String,
    val kind: CollaborationKind,
    val number: Int,
) : GithubRoute

@Composable
fun WorkInfoPage(
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
) {
    val allStrings = LocalGithubStrings.current
    val strings = allStrings.WorkDetail
    val repositoryStrings = allStrings.Repository
    val commonStrings = allStrings.Common
    val viewModel: WorkInfoViewModel =
        koinViewModel(
            key = "work-info-$login-$owner-$name-${kind.name}-$number",
            parameters = { parametersOf(owner, name, kind, number, login) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val host = requireNotNull(LocalGithubPageHost.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            host.onBack()
        }
    }
    if (state.failed) GithubLoadFailureToast(Unit, strings.PropertyLoadFailed)
    GithubPage(
        title = strings.Information,
        itemSpacing = 12.dp,
        actions = {
            GithubToolbarActions(
                toolbarIcon(Lucide.Share, repositoryStrings.ShareLink) {
                    if (host.navigationEnabled) {
                        shareWorkLink(
                            context,
                            owner,
                            name,
                            kind,
                            number,
                            repositoryStrings.ShareLink,
                            commonStrings.ExternalLinkOpenFailed,
                        )
                    }
                },
                if (state.value != null)
                    toolbarIcon(Lucide.Check, strings.SaveChanges, state.hasPendingChanges()) {
                        if (host.navigationEnabled) viewModel.saveAll()
                    }
                else null,
            )
        },
    ) {
        val value = state.value
        if (value == null) {
            if (!state.failed) {
                workInfoPlaceholders(kind, strings)
            }
        } else {
            if (kind == CollaborationKind.PullRequest) {
                item(key = "work-info-reviewers") {
                    PropertySection(
                        title = strings.RequestedReviewers,
                        values = state.valuesFor(WorkProperty.Reviewers),
                        empty = strings.NoReviewRequests,
                        editable = value.editable,
                        submitting = state.submitting,
                        onEdit = {
                            if (host.navigationEnabled) viewModel.edit(WorkProperty.Reviewers)
                        },
                        leading = { option ->
                            val avatar = option.avatarUrl?.takeIf(String::isNotBlank)
                            if (avatar != null) GithubAvatar(avatar, option.title, size = 20.dp)
                            else
                                GithubIcon(
                                    Lucide.UsersRound,
                                    contentDescription = null,
                                    size = 20.dp,
                                )
                        },
                    )
                }
            }
            item(key = "work-info-labels") {
                PropertySection(
                    title = strings.Labels,
                    values = state.valuesFor(WorkProperty.Labels),
                    empty = strings.NoLabels,
                    editable = value.editable,
                    submitting = state.submitting,
                    onEdit = { if (host.navigationEnabled) viewModel.edit(WorkProperty.Labels) },
                    leading = { label ->
                        GithubIcon(
                            Lucide.Tag,
                            contentDescription = null,
                            size = 20.dp,
                            tint = githubLabelColor(label.color),
                        )
                    },
                )
            }
            item(key = "work-info-milestone") {
                PropertySection(
                    title = strings.Milestone,
                    values = state.valuesFor(WorkProperty.Milestone),
                    empty = strings.NoMilestone,
                    editable = value.editable,
                    submitting = state.submitting,
                    onEdit = { if (host.navigationEnabled) viewModel.edit(WorkProperty.Milestone) },
                    leading = {
                        GithubIcon(Lucide.Milestone, contentDescription = null, size = 20.dp)
                    },
                )
            }
            item(key = "work-info-linked") {
                PropertySection(
                    title = strings.LinkedItems,
                    values = state.valuesFor(WorkProperty.LinkedItems),
                    empty = strings.NoLinkedItems,
                    editable = value.editable,
                    submitting = state.submitting,
                    onEdit = {
                        if (host.navigationEnabled) viewModel.edit(WorkProperty.LinkedItems)
                    },
                    leading = { option -> LinkedItemIcon(option) },
                )
            }
            if (kind == CollaborationKind.Issue) {
                item(key = "work-info-sub-issues") {
                    PropertySection(
                        title = strings.SubIssues,
                        values = state.valuesFor(WorkProperty.SubIssues),
                        empty = strings.NoSubIssues,
                        editable = value.editable,
                        submitting = state.submitting,
                        onEdit = {
                            if (host.navigationEnabled) viewModel.edit(WorkProperty.SubIssues)
                        },
                        leading = {
                            GithubIcon(Lucide.ListPlus, contentDescription = null, size = 20.dp)
                        },
                    )
                }
            }
            item(key = "work-info-projects") {
                PropertySection(
                    title = strings.Projects,
                    values = state.valuesFor(WorkProperty.Projects),
                    empty = strings.NoProjects,
                    editable = value.editable,
                    submitting = state.submitting,
                    onEdit = { if (host.navigationEnabled) viewModel.edit(WorkProperty.Projects) },
                    leading = {
                        GithubIcon(Lucide.FolderKanban, contentDescription = null, size = 20.dp)
                    },
                )
            }
        }
    }
    WorkPropertySelectionSheet(state, viewModel)
}

@Composable
private fun PropertySection(
    title: String,
    values: List<WorkPropertyOption>,
    empty: String,
    editable: Boolean,
    submitting: Boolean,
    onEdit: () -> Unit,
    leading: @Composable (WorkPropertyOption) -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val onClick = if (editable && !submitting) onEdit else null
    GithubSection(title = title) {
        if (values.isEmpty()) {
            GlasmioListRow(
                title = empty,
                systemName = null,
                onClick = onClick,
                showDisclosure = onClick != null,
            )
        } else {
            values.forEachIndexed { index, option ->
                GlasmioListRow(
                    title = option.title,
                    subtitle = option.subtitle,
                    leading = { leading(option) },
                    onClick = onClick,
                    showTopSeparator = index > 0,
                    showDisclosure = false,
                )
            }
            GlasmioListRow(
                title = strings.ContinueAdding,
                systemName = null,
                onClick = onClick,
                showTopSeparator = true,
                showDisclosure = onClick != null,
            )
        }
    }
}

private fun LazyListScope.workInfoPlaceholders(
    kind: CollaborationKind,
    strings: GithubStrings_WorkDetail,
) {
    if (kind == CollaborationKind.PullRequest) {
        item(key = "work-info-reviewers") { WorkInfoSectionPlaceholder(strings.RequestedReviewers) }
    }
    item(key = "work-info-labels") { WorkInfoSectionPlaceholder(strings.Labels) }
    item(key = "work-info-milestone") { WorkInfoSectionPlaceholder(strings.Milestone) }
    item(key = "work-info-linked") { WorkInfoSectionPlaceholder(strings.LinkedItems) }
    if (kind == CollaborationKind.Issue) {
        item(key = "work-info-sub-issues") { WorkInfoSectionPlaceholder(strings.SubIssues) }
    }
    item(key = "work-info-projects") { WorkInfoSectionPlaceholder(strings.Projects) }
}

@Composable
private fun WorkInfoSectionPlaceholder(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GithubSectionHeader(title)
        GlasmioGroupedList { WorkInfoRowPlaceholder(showArrow = true) }
    }
}

@Composable
private fun WorkInfoRowPlaceholder(
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
    viewModel: WorkInfoViewModel,
) {
    val editor = state.editor
    val strings = LocalGithubStrings.current.WorkDetail
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    if (editor?.failed == true || editor?.moreFailed == true) {
        GithubLoadFailureToast(
            Unit,
            if (editor.needsReconcile) strings.PropertySaveFailed else strings.PropertyLoadFailed,
        )
    }
    GithubSearchSelectionSheet(
        visible = editor != null,
        title = editor?.property?.selectionTitle(strings).orEmpty(),
        placeholder = strings.PropertySearch,
        queryKey = editor?.query.orEmpty(),
        onQueryChange = viewModel::search,
        onDismiss = {
            keyboard?.hide()
            focus.clearFocus(force = true)
            viewModel.closeEditor()
        },
        onConfirm = {
            keyboard?.hide()
            focus.clearFocus(force = true)
            viewModel.applyEditor()
        },
        enabled = !state.submitting,
        confirmEnabled =
            editor != null && !editor.loading && !editor.failed && !editor.needsReconcile,
    ) {
        if (editor == null) return@GithubSearchSelectionSheet
        when {
            editor.loading ->
                item(key = "work-property-loading") {
                    GlasmioGroupedList {
                        repeat(3) { index -> WorkInfoRowPlaceholder(showTopSeparator = index > 0) }
                    }
                }
            editor.failed -> Unit
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
                                        onSelect = { viewModel.select(option) },
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
                                if (!editor.moreFailed) {
                                    LaunchedEffect(editor.query, editor.nextCursor) {
                                        viewModel.more()
                                    }
                                    WorkInfoRowPlaceholder(showTopSeparator = index > 0)
                                }
                            } else {
                                WorkPropertyOptionRow(
                                    editor.property,
                                    option,
                                    checked = editor.selected.any { it.id == option.id },
                                    showTopSeparator = index > 0,
                                    onSelect = { viewModel.select(option) },
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
    onSelect: () -> Unit,
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
                if (checked) GithubIcon(Lucide.Check, contentDescription = null)
            }
        },
        onClick = onSelect.takeIf { option.editable },
    )
}

@Composable
private fun propertyOptionIcon(property: WorkProperty, option: WorkPropertyOption) {
    when (property) {
        WorkProperty.Reviewers -> {
            val avatar = option.avatarUrl?.takeIf(String::isNotBlank)
            if (avatar != null) GithubAvatar(avatar, option.title, size = 20.dp)
            else GithubIcon(Lucide.UsersRound, contentDescription = null, size = 20.dp)
        }
        WorkProperty.Labels ->
            GithubIcon(
                Lucide.Tag,
                contentDescription = null,
                size = 20.dp,
                tint = githubLabelColor(option.color),
            )
        WorkProperty.Milestone ->
            GithubIcon(Lucide.Milestone, contentDescription = null, size = 20.dp)
        WorkProperty.LinkedItems -> LinkedItemIcon(option)
        WorkProperty.SubIssues ->
            GithubIcon(Lucide.ListPlus, contentDescription = null, size = 20.dp)
        WorkProperty.Projects ->
            GithubIcon(Lucide.FolderKanban, contentDescription = null, size = 20.dp)
        WorkProperty.Assignees -> {
            val avatar = option.avatarUrl?.takeIf(String::isNotBlank)
            if (avatar != null) GithubAvatar(avatar, option.title, size = 20.dp)
            else GithubIcon(Lucide.UsersRound, contentDescription = null, size = 20.dp)
        }
    }
}

@Composable
private fun LinkedItemIcon(option: WorkPropertyOption) {
    val closed = option.state == "CLOSED"
    val merged = option.state == "MERGED"
    GithubIcon(
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
                merged -> GithubWorkColors.merged
                closed -> GithubWorkColors.closed
                else -> GithubWorkColors.open
            },
    )
}

private fun WorkProperty.title(strings: GithubStrings_WorkDetail) =
    when (this) {
        WorkProperty.Reviewers -> strings.RequestedReviewers
        WorkProperty.Assignees -> strings.Assignees
        WorkProperty.Labels -> strings.Labels
        WorkProperty.Milestone -> strings.Milestone
        WorkProperty.LinkedItems -> strings.LinkedItems
        WorkProperty.SubIssues -> strings.SubIssues
        WorkProperty.Projects -> strings.Projects
    }

private fun WorkProperty.selectionTitle(strings: GithubStrings_WorkDetail) =
    if (this == WorkProperty.Reviewers) strings.RequestReview else title(strings)
