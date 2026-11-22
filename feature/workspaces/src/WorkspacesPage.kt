package moe.yumeyuka.yumehub.feature.workspaces

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.yumeyucca.lucide.ExternalLink
import io.github.yumeyucca.lucide.File
import io.github.yumeyucca.lucide.Folder
import io.github.yumeyucca.lucide.FolderGit
import io.github.yumeyucca.lucide.FolderGit2
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RefreshCw
import java.time.Duration
import java.time.Instant
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommit
import moe.yumeyuka.yumehub.data.repository.WorkspaceEntry
import moe.yumeyuka.yumehub.locale.GithubStrings_Workspaces
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubCommitSummary
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspacePlaceholderRow
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspacesPage(
    owner: String,
    name: String,
    ref: String? = null,
    path: String = "",
    file: Boolean = false,
    anchor: String? = null,
    gitObject: GithubRepositoryLocation.GitObject? = null,
    linkUrl: String? = null,
    onOpenCommitHistory: (String) -> Unit,
) {
    val locale = LocalGithubStrings.current
    val strings = locale.Workspaces
    val viewModel: WorkspacesViewModel =
        koinViewModel(
            key =
                "workspaces-$owner-$name-$ref-$path-$file-$anchor-${gitObject?.refAndPath}-" +
                    "${gitObject?.directory}-${gitObject?.anchor}-$linkUrl",
            parameters = {
                parametersOf(
                    WorkspacesRoute(owner, name, ref, path, file, anchor, gitObject, linkUrl),
                )
            },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Content,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::refresh,
    )
    val branches by viewModel.branches.uiState.collectAsStateWithLifecycle()
    val host = requireNotNull(LocalGithubPageHost.current)
    val open = moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler()
    val openExternal = rememberExternalUriHandler()
    val context = LocalContext.current
    val latestStrings by rememberUpdatedState(strings)
    val latestContext by rememberUpdatedState(context)
    val listState = LocalGithubPageState.current?.listState("workspaces")
    var scrolledPath by rememberSaveable { mutableStateOf(state.path) }
    var branchPickerVisible by rememberSaveable { mutableStateOf(false) }
    val entries = (state.entries as? WorkspacesLoad.Ready)?.value.orEmpty()
    val entriesLoading =
        state.repository is WorkspacesLoad.Loading || state.entries is WorkspacesLoad.Loading
    val entrySlots =
        remember(state.ref, state.path) {
            GithubPagingSlotKeys("workspaces:${state.ref}:${state.path}")
        }
    val entryKeys =
        remember(entrySlots, entries, entriesLoading) {
            entrySlots.update(
                entries.map(WorkspaceEntry::path),
                loading = false,
                initialLoading = entriesLoading,
            )
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                WorkspacesEvent.Saved ->
                    Toast.makeText(latestContext, latestStrings.Saved, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pendingEditor = state.pendingEditor
    LaunchedEffect(pendingEditor, host.navigationEnabled) {
        if (
            pendingEditor != null &&
                host.navigationEnabled &&
                viewModel.consumeEditor(pendingEditor)
        ) {
            host.onOpenEditor(pendingEditor)
        }
    }
    LaunchedEffect(state.path, listState) {
        if (state.path == scrolledPath) return@LaunchedEffect
        scrolledPath = state.path
        listState?.scrollToItem(0)
    }
    if (state.path.isNotEmpty()) {
        GithubBackHandler { if (host.navigationEnabled) viewModel.navigateUp() }
    }
    val failure =
        (state.repository as? WorkspacesLoad.Error)?.message
            ?: (state.entries as? WorkspacesLoad.Error)?.message
            ?: (state.commit as? WorkspacesLoad.Error)?.message
            ?: (state.entries as? WorkspacesLoad.Ready)?.refreshError
            ?: (state.commit as? WorkspacesLoad.Ready)?.refreshError
    GithubLoadFailureToast(failure, failure.orEmpty())
    GithubPage(
        title = strings.Title,
        onBack = { if (!viewModel.navigateUp()) host.onBack() },
        listState = listState,
        itemSpacing = 0.dp,
        actions = {
            val failed =
                gitObject != null &&
                    (state.repository is WorkspacesLoad.Error ||
                        state.entries is WorkspacesLoad.Error)
            val canBrowse = host.navigationEnabled && state.ref != null
            GithubToolbarActions(
                if (failed)
                    toolbarIcon(
                        Lucide.RefreshCw,
                        locale.Common.Retry,
                        host.navigationEnabled,
                        onClick = viewModel::retry,
                    )
                else
                    toolbarIcon(Lucide.FolderGit, strings.CommitHistory, canBrowse) {
                        if (host.navigationEnabled) state.ref?.let(onOpenCommitHistory)
                    },
                if (failed)
                    linkUrl?.let {
                        toolbarIcon(
                            Lucide.ExternalLink,
                            locale.Repository.OpenInBrowser,
                            host.navigationEnabled,
                        ) {
                            openExternal(it)
                        }
                    }
                else
                    toolbarIcon(Lucide.FolderGit2, strings.SwitchBranch, canBrowse) {
                        if (host.navigationEnabled) {
                            branchPickerVisible = true
                            viewModel.loadBranches()
                        }
                    },
            )
        },
    ) {
        workspacesScreen(
            state = state,
            strings = strings,
            enabled = true,
            entries = entries,
            entryKeys = entryKeys,
            onRetryCommit = { if (host.navigationEnabled) viewModel.retryCommit() },
            onOpenCommitHistory = {
                val commit = state.commit
                if (host.navigationEnabled && commit is WorkspacesLoad.Ready)
                    commit.value?.oid?.let { open("https://github.com/$owner/$name/commit/$it") }
            },
            onOpenDirectory = { if (host.navigationEnabled) viewModel.openDirectory(it) },
            onOpenFile = { path, name, submoduleGitUrl ->
                if (host.navigationEnabled) viewModel.openFile(path, name, submoduleGitUrl)
            },
        )
    }
    WorkspaceBranchSheet(
        visible = branchPickerVisible,
        branches = branches,
        selectedBranch = state.ref,
        onDismiss = { branchPickerVisible = false },
        onRetry = viewModel.branches::retry,
        onLoadMore = viewModel.branches::loadMore,
        onQueryChange = viewModel.branches::search,
        onSelect = { branch -> if (host.navigationEnabled) viewModel.switchBranch(branch) },
    )
}

private fun LazyListScope.workspacesScreen(
    state: WorkspacesUiState,
    strings: GithubStrings_Workspaces,
    enabled: Boolean,
    entries: List<WorkspaceEntry>,
    entryKeys: List<String>,
    onRetryCommit: () -> Unit,
    onOpenCommitHistory: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String, String, String?) -> Unit,
) {
    if (state.repository is WorkspacesLoad.Error) return
    item(key = "workspaces-commit") {
        GlasmioGroupedList(modifier = Modifier.padding(bottom = 12.dp)) {
            CommitBlock(
                state = state,
                strings = strings,
                enabled = enabled,
                onRetry = onRetryCommit,
                onOpenHistory = onOpenCommitHistory,
            )
        }
    }
    if (entryKeys.isEmpty()) {
        if (state.entries is WorkspacesLoad.Ready) {
            item(key = "workspaces-empty") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Empty,
                        systemName = null,
                        showTopSeparator = false,
                    )
                }
            }
        }
        return
    }
    // 每行独立布局和命中测试，不让整张长目录共用一个裁剪层。
    items(
        count = entryKeys.size,
        key = { entryKeys[it] },
        contentType = { "workspaces-entry" },
    ) { index ->
        val entry = entries.getOrNull(index)
        val top = if (index == 0) 26.dp else 0.dp
        val bottom = if (index == entryKeys.lastIndex) 26.dp else 0.dp
        GlasmioGroupedList(
            framed = false,
            modifier =
                Modifier.glasmioGroupedListBackground(
                    ContinuousRoundedRectangle(
                        topStart = top,
                        topEnd = top,
                        bottomStart = bottom,
                        bottomEnd = bottom,
                    ),
                ),
        ) {
            GithubLoadingContent(
                loading = entry == null,
                placeholder = { WorkspacePlaceholderRow(showTopSeparator = index > 0) },
            ) {
                if (entry != null) {
                    WorkspaceRow(
                        entry = entry,
                        updatedAt =
                            if (entry.directory) null
                            else workspaceRelativeTime(state.updatedAt[entry.path], strings),
                        enabled = enabled,
                        showTopSeparator = index > 0,
                        onClick = {
                            if (entry.directory) onOpenDirectory(entry.path)
                            else onOpenFile(entry.path, entry.name, entry.submoduleGitUrl)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitBlock(
    state: WorkspacesUiState,
    strings: GithubStrings_Workspaces,
    enabled: Boolean,
    onRetry: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    GithubLoadingContent(
        loading =
            state.repository is WorkspacesLoad.Loading || state.commit is WorkspacesLoad.Loading,
        placeholder = { WorkspaceCommitPlaceholder() },
    ) {
        when (val commit = state.commit) {
            WorkspacesLoad.Loading,
            is WorkspacesLoad.Error -> Unit
            is WorkspacesLoad.Ready ->
                WorkspaceCommitCard(
                    commit = commit.value,
                    strings = strings,
                    onClick = onOpenHistory.takeIf { enabled },
                )
        }
    }
}

@Composable
internal fun WorkspaceCommitCard(
    commit: WorkspaceCommit?,
    strings: GithubStrings_Workspaces,
    onClick: (() -> Unit)? = null,
) {
    val author =
        commit?.authorLogin?.takeIf(String::isNotBlank)
            ?: commit?.authorName?.takeIf(String::isNotBlank)
            ?: strings.UnknownAuthor
    val time = workspaceRelativeTime(commit?.committedDate, strings)
    GithubCommitSummary(
        message = commit?.message?.takeIf(String::isNotBlank) ?: strings.NoCommit,
        author = author,
        avatarUrl = commit?.avatarUrl,
        time = time,
        onClick = onClick,
    )
}

@Composable
private fun WorkspaceRow(
    entry: WorkspaceEntry,
    updatedAt: String?,
    enabled: Boolean,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = entry.name,
        detail = updatedAt,
        systemName = null,
        leading = {
            GithubIcon(
                if (entry.directory) Lucide.Folder else Lucide.File,
                contentDescription = null,
                size = 20.dp,
                tint = GlasmioTheme.colors.content,
            )
        },
        showTopSeparator = showTopSeparator,
        showDisclosure = false,
        enabled = enabled,
        onClick = if (enabled) onClick else null,
    )
}

private fun workspaceRelativeTime(
    value: String?,
    strings: GithubStrings_Workspaces,
    now: Instant = Instant.now(),
): String? {
    val instant = value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> strings.JustNow
        seconds < 60 * 60 -> strings.MinutesAgo((seconds / 60).toInt())
        seconds < 24 * 60 * 60 -> strings.HoursAgo((seconds / 3600).toInt())
        seconds < 7 * 86_400 -> strings.DaysAgo((seconds / 86_400).toInt())
        seconds < 30 * 86_400 -> strings.WeeksAgo((seconds / (7 * 86_400)).toInt())
        seconds < 365 * 86_400 -> strings.MonthsAgo((seconds / (30 * 86_400)).toInt())
        else -> strings.YearsAgo((seconds / (365 * 86_400)).toInt())
    }
}
