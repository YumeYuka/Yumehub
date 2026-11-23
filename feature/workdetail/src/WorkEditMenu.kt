package moe.yumeyuka.yumehub.feature.workdetail

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.GithubSearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

@Composable
internal fun WorkEditMenu(
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
    enabled: Boolean,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val common = LocalGithubStrings.current.Common
    val item = state.item
    val isPullRequest = viewModel.kind == CollaborationKind.PullRequest
    var expanded by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val available = enabled && !state.submitting && !state.submissionUncertain
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = 3,
        anchor = { open ->
            GlasmioIconButton(onClick = open, enabled = available) {
                GithubIcon(Lucide.Pencil, contentDescription = strings.EditProperty)
            }
        },
    ) { backdrop, close ->
        GlasmioMenuItem(
            title = strings.EditTitle,
            backdrop = backdrop,
            enabled = available && item?.viewerCanEdit == true,
            icon = { GithubIcon(Lucide.Pencil, contentDescription = null, size = 20.dp) },
            onClick = {
                close()
                viewModel.openTitleEditor()
            },
        )
        GlasmioMenuItem(
            title =
                if (item?.locked == true) strings.UnlockConversation else strings.LockConversation,
            backdrop = backdrop,
            enabled = available && item?.viewerCanModerate == true,
            icon = { GithubIcon(Lucide.Lock, contentDescription = null, size = 20.dp) },
            onClick = {
                close()
                confirmation = "lock"
            },
        )
        GlasmioMenuItem(
            title = if (isPullRequest) strings.ClosePullRequest else strings.CloseIssue,
            backdrop = backdrop,
            destructive = true,
            enabled =
                available &&
                    item?.viewerCanClose == true &&
                    item.state == "open" &&
                    (!isPullRequest || !item.merged),
            icon = {
                GithubIcon(
                    if (isPullRequest) Lucide.GitPullRequestClosed else Lucide.CircleX,
                    contentDescription = null,
                    size = 20.dp,
                    tint = LocalGlassColors.current.destructive,
                )
            },
            onClick = {
                close()
                confirmation = if (isPullRequest) "close-pull" else "close-issue"
            },
        )
    }
    GlasmioAlertDialog(
        visible = confirmation != null,
        onDismissRequest = { confirmation = null },
        title =
            when (confirmation) {
                "close-pull" -> strings.ConfirmClosePullRequest
                "close-issue" -> strings.ConfirmCloseIssue
                else ->
                    if (item?.locked == true) strings.UnlockConversation
                    else strings.ConfirmLockConversation
            },
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    common.Cancel,
                    { confirmation = null },
                    GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    common.Confirm,
                    {
                        when (confirmation) {
                            "close-pull" -> viewModel.closePullRequest()
                            "close-issue" -> viewModel.closeIssue()
                            else -> viewModel.toggleConversationLock()
                        }
                        confirmation = null
                    },
                    if (confirmation == "lock") GlasmioAlertButtonRole.Default
                    else GlasmioAlertButtonRole.Destructive,
                ),
            ),
    )
}

@Composable
internal fun WorkBaseBranchSheet(
    state: CollaborationDetailUiState,
    viewModel: CollaborationDetailViewModel,
    canChangeBase: Boolean,
    onDismiss: () -> Unit,
) {
    val strings = LocalGithubStrings.current.WorkDetail
    val current = state.item?.baseRefName
    var selected by remember(viewModel, current) { mutableStateOf(current) }
    val branchChoices = if (state.branchLoading) emptyList() else state.branchChoices
    val branchSlots =
        remember(state.branchQuery) { GithubPagingSlotKeys("base-branch") }
    val branchKeys =
        remember(branchSlots, branchChoices, state.branchLoading, state.branchCursor) {
            branchSlots.update(
                branchChoices,
                loading = state.branchCursor != null,
                initialLoading = state.branchLoading,
            )
        }
    if (state.branchError)
        GithubLoadFailureToast(Unit, strings.PropertyLoadFailed)
    GithubSearchSelectionSheet(
        visible = true,
        title = strings.ChangeBaseBranch,
        placeholder = strings.PropertySearch,
        queryKey = state.branchQuery,
        onQueryChange = viewModel::searchBaseBranches,
        onDismiss = onDismiss,
        onConfirm = {
            selected?.takeIf { canChangeBase && it != current }?.let {
                viewModel.chooseBaseBranch(it)
                onDismiss()
            }
        },
        confirmEnabled = canChangeBase && selected != null && selected != current,
    ) { _ ->
        when {
            state.branchError -> Unit
            !state.branchLoading && state.branchChoices.isEmpty() ->
                item { GlasmioGroupedList { GlasmioListRow(title = strings.NoPropertyOptions) } }
            else -> {
                itemsIndexed(
                    branchKeys,
                    key = { _, key -> key },
                ) { index, _ ->
                    val branch = branchChoices.getOrNull(index)
                    if (branch == null && state.branchCursor != null) {
                        LaunchedEffect(state.branchQuery, state.branchCursor) {
                            viewModel.loadMoreBaseBranches()
                        }
                    }
                    GlasmioGroupedList(
                        framed = false,
                        modifier =
                            Modifier.animateItem()
                                .glasmioGroupedListBackground(
                                    ContinuousRoundedRectangle(
                                        topStart = if (index == 0) 26.dp else 0.dp,
                                        topEnd = if (index == 0) 26.dp else 0.dp,
                                        bottomStart =
                                            if (index == branchKeys.lastIndex) 26.dp else 0.dp,
                                        bottomEnd =
                                            if (index == branchKeys.lastIndex) 26.dp else 0.dp,
                                    ),
                                ),
                    ) {
                        GithubLoadingContent(
                            loading = branch == null,
                            placeholder = { TitleRowPlaceholder(showTopSeparator = index > 0) },
                        ) {
                            if (branch != null) {
                                val name = branch.removePrefix("refs/heads/")
                                val checked = name == selected
                                GlasmioListRow(
                                    title = name,
                                    modifier = Modifier.selectable(
                                        selected = checked,
                                        enabled = canChangeBase,
                                        role = Role.RadioButton,
                                        onClick = { selected = name },
                                    ),
                                    trailing = {
                                        if (checked) {
                                            GithubIcon(Lucide.Check, contentDescription = null)
                                        }
                                    },
                                    showTopSeparator = index > 0,
                                    showDisclosure = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
