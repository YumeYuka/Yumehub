package moe.yumeyuka.yumehub.feature.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.GitCommitHorizontal
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommitDraft
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitMessageFields
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession

@Composable
internal fun WorkspaceCommitSheet(
    visible: Boolean,
    editor: GithubEditorSession,
    draft: WorkspaceCommitDraft?,
    baseChanged: Boolean,
    onDraftChange: (WorkspaceCommitDraft) -> Unit,
    branches: WorkspaceBranches,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceCommitDraft) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val work = strings.Workspaces
    val busy = editor.submitting || draft == null
    var choosing by rememberSaveable(editor.id, visible) { mutableStateOf(false) }
    var reviewed by rememberSaveable(editor.id, editor.filePath, visible, baseChanged) { mutableStateOf(false) }
    val browser = rememberExternalUriHandler()
    GlasmioBottomSheet(
        visible = visible && !choosing && !editor.closeRequested,
        onDismissRequest = { if (!choosing) onDismiss() },
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        fitContent = true,
        title = work.CommitChanges,
        topBar = {
            GlasmioTopBar(
                title = work.CommitChanges,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = editor.submitEnabled && !busy && (!baseChanged || reviewed) && !draft?.headline.isNullOrBlank(),
                        onClick = { draft?.let(onConfirm) },
                    ) {
                        GithubIcon(
                            Lucide.GitCommitHorizontal,
                            contentDescription = work.CommitChanges,
                        )
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
            if (baseChanged) GlasmioGroupedList {
                GlasmioListRow(title = work.DraftBaseChanged, detail = strings.Repository.OpenInBrowser,
                    onClick = editor.pageUrl?.let { url -> { browser(url) } })
                GlasmioListRow(title = work.ReviewedFile, trailing = {
                    GlasmioSwitch(checked = reviewed, enabled = !busy, onCheckedChange = { reviewed = it })
                })
            }
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.CommitBranch,
                    detail = draft?.branch?.removePrefix("refs/heads/"),
                    onClick =
                        if (busy) null
                        else
                            ({
                                branches.open()
                                choosing = true
                            }),
                )
            }
            GithubCommitMessageFields(
                headline = draft?.headline.orEmpty(),
                onHeadlineChange = { text -> draft?.let { onDraftChange(it.copy(headline = text)) } },
                body = draft?.body.orEmpty(),
                onBodyChange = { text -> draft?.let { onDraftChange(it.copy(body = text)) } },
                enabled = !busy,
            )
        }
    }
    val choices by branches.uiState.collectAsStateWithLifecycle()
    WorkspaceBranchSheet(
        visible = visible && choosing,
        branches = choices,
        selectedBranch = draft?.branch.orEmpty(),
        onDismiss = { choosing = false },
        onRetry = branches::retry,
        onLoadMore = branches::loadMore,
        onQueryChange = branches::search,
        onSelect = {
            draft?.let { current -> onDraftChange(current.copy(branch = it)) }
            choosing = false
        },
    )
}
