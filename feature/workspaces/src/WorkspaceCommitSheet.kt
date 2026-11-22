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
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitMessageFields
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubEditorSession

@Composable
internal fun WorkspaceCommitSheet(
    visible: Boolean,
    editor: GithubEditorSession,
    initialRef: String,
    branches: WorkspaceBranches,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val work = strings.Workspaces
    val busy = editor.submitting
    var branch by rememberSaveable(editor.id, visible) { mutableStateOf(initialRef) }
    var headline by
        rememberSaveable(editor.id, visible) { mutableStateOf(work.UpdateFile(editor.title)) }
    var body by rememberSaveable(editor.id, visible) { mutableStateOf("") }
    var choosing by rememberSaveable(editor.id, visible) { mutableStateOf(false) }
    GlasmioBottomSheet(
        visible = visible && !choosing && !editor.closeRequested,
        onDismissRequest = { if (!busy && !choosing) onDismiss() },
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
        fitContent = true,
        title = work.CommitChanges,
        topBar = {
            GlasmioTopBar(
                title = work.CommitChanges,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !busy) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = editor.submitEnabled && !busy && headline.isNotBlank(),
                        onClick = { onConfirm(branch, headline, body) },
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
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.CommitBranch,
                    detail = branch.removePrefix("refs/heads/"),
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
                headline = headline,
                onHeadlineChange = { headline = it },
                body = body,
                onBodyChange = { body = it },
                enabled = !busy,
            )
        }
    }
    val choices by branches.uiState.collectAsStateWithLifecycle()
    WorkspaceBranchSheet(
        visible = visible && choosing,
        branches = choices,
        selectedBranch = branch,
        onDismiss = { choosing = false },
        onRetry = branches::retry,
        onLoadMore = branches::loadMore,
        onQueryChange = branches::search,
        onSelect = {
            branch = it
            choosing = false
        },
    )
}
