package moe.yumeyuka.yumehub.feature.workspaces

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.ExternalLink
import io.github.yumeyucca.lucide.File
import io.github.yumeyucca.lucide.Folder
import io.github.yumeyucca.lucide.FolderGit
import io.github.yumeyucca.lucide.FolderGit2
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RefreshCw
import java.time.Duration
import java.time.Instant
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.WorkspaceCommit
import moe.yumeyuka.yumehub.data.repository.WorkspaceEntry
import moe.yumeyuka.yumehub.locale.GithubStrings_Workspaces
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.EmptyScene
import moe.yumeyuka.yumehub.ui.component.GithubCommitSummary
import moe.yumeyuka.yumehub.ui.component.GithubEmptyState
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubCardSizeSpec
import moe.yumeyuka.yumehub.ui.component.placeholders.PlaceholderTokens
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspacePlaceholderRow
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubSkeletonEntrance
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspacesPage(
    route: WorkspacesRoute,
    onOpenCommitHistory: (String) -> Unit,
) {
    val locale = LocalGithubStrings.current
    val strings = locale.Workspaces
    val viewModel: WorkspacesViewModel =
        koinViewModel(
            key = "workspaces-$route",
            parameters = { parametersOf(route) },
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
    var branchPickerVisible by rememberSaveable { mutableStateOf(false) }
    var activeRef by remember { mutableStateOf(state.ref) }
    var branchEpoch by remember { mutableIntStateOf(0) }
    val currentRef = state.ref
    if (currentRef != null && currentRef != activeRef) {
        if (activeRef != null) branchEpoch++
        activeRef = currentRef
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
    if (state.path.isNotEmpty()) {
        GithubBackHandler { if (host.navigationEnabled) viewModel.navigateUp() }
    }
    GithubPage(
        title = strings.Title,
        onBack = { if (!viewModel.navigateUp()) host.onBack() },
        itemSpacing = 0.dp,
        actions = {
            val failed =
                (route.gitObject != null || route.license) &&
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
                    route.linkUrl?.let {
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
            enabled = host.navigationEnabled,
            branchEpoch = branchEpoch,
            onRetryEntries = viewModel::retry,
            onRetryCommit = { if (host.navigationEnabled) viewModel.retryCommit() },
            onOpenCommitHistory = {
                val commit = state.commit
                if (host.navigationEnabled && commit is WorkspacesLoad.Ready)
                    commit.value?.oid?.let {
                        open("https://github.com/${route.owner}/${route.name}/commit/$it")
                    }
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
        onSelect = { branch ->
            if (host.navigationEnabled) viewModel.switchBranch(branch)
            branchPickerVisible = false
        },
    )
}

private fun LazyListScope.workspacesScreen(
    state: WorkspacesUiState,
    strings: GithubStrings_Workspaces,
    enabled: Boolean,
    branchEpoch: Int,
    onRetryEntries: () -> Unit,
    onRetryCommit: () -> Unit,
    onOpenCommitHistory: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String, String, String?) -> Unit,
) {
    val directoryError =
        (state.repository as? WorkspacesLoad.Error)?.message
            ?: (state.entries as? WorkspacesLoad.Error)?.message
            ?: (state.entries as? WorkspacesLoad.Ready)?.refreshError
    if (directoryError != null) {
        item(key = "workspace-directory-error") {
            GlasmioGroupedList { WorkspaceRetryRow(directoryError, onRetryEntries) }
        }
    }
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
    item(key = "workspaces-directory") {
        val density = LocalDensity.current
        val viewportHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
        // Sample the displayed frame on navigation, including an interrupted resize.
        val frameHeight = remember { intArrayOf(0) }
        GlasmioGroupedList {
            Column(
                Modifier.onSizeChanged { frameHeight[0] = it.height }
                    .animateContentSize(GithubCardSizeSpec)
            ) {
                key(branchEpoch, state.path) {
                    val heldHeight = remember { frameHeight[0] }
                    val rowHeight = PlaceholderTokens.ListRowHeight
                    val placeholderHeight = with(density) {
                        if (heldHeight > 0) heldHeight.toDp() else rowHeight * 4
                    }.coerceAtMost(viewportHeight)
                    val ready = state.entries as? WorkspacesLoad.Ready
                    SkeletonSwap(
                        loading = state.entries is WorkspacesLoad.Loading,
                        entrance = true,
                        placeholder = {
                            Column(Modifier.height(placeholderHeight)) {
                                repeat(kotlin.math.ceil(placeholderHeight / rowHeight).toInt()) {
                                    WorkspacePlaceholderRow(showTopSeparator = it > 0)
                                }
                            }
                        },
                    ) {
                        if (ready != null && ready.value.isNotEmpty()) {
                            val scroll = LocalGithubPageState.current
                                ?.listState("workspaces-tree:${state.ref}:${state.path}")
                                ?: rememberLazyListState()
                            LazyColumn(Modifier.heightIn(max = viewportHeight), state = scroll) {
                                itemsIndexed(ready.value, key = { _, entry -> entry.path }) { index, entry ->
                                    WorkspaceRow(
                                        entry = entry,
                                        updatedAt = if (entry.directory) null else workspaceRelativeTime(state.updatedAt[entry.path], strings),
                                        enabled = enabled,
                                        showTopSeparator = index > 0,
                                        onClick = {
                                            if (entry.directory) onOpenDirectory(entry.path)
                                            else onOpenFile(entry.path, entry.name, entry.submoduleGitUrl)
                                        },
                                    )
                                }
                            }
                        } else if (ready != null && directoryError == null) {
                            GithubEmptyState(scene = EmptyScene.Inbox, title = strings.Empty)
                        }
                    }
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
    val target = if (state.repository is WorkspacesLoad.Loading) WorkspacesLoad.Loading else state.commit
    val firstEntrance = LocalGithubSkeletonEntrance.current?.presented == false
    val transitionState = remember {
        MutableTransitionState(if (firstEntrance) WorkspacesLoad.Loading else target)
    }
    transitionState.targetState = target
    val transition = rememberTransition(transitionState, label = "workspace-commit")
    transition.AnimatedContent(
        contentKey = { load -> if (load is WorkspacesLoad.Ready) load.value?.oid else load },
    ) { commit ->
        when (commit) {
            WorkspacesLoad.Loading -> WorkspaceCommitPlaceholder()
            is WorkspacesLoad.Error -> WorkspaceRetryRow(commit.message, onRetry)
            is WorkspacesLoad.Ready -> {
                WorkspaceCommitCard(
                    commit = commit.value,
                    strings = strings,
                    onClick = onOpenHistory.takeIf { enabled && commit.value?.oid != null && commit == target },
                )
            }
        }
    }
    (state.commit as? WorkspacesLoad.Ready)?.refreshError?.let { WorkspaceRetryRow(it, onRetry) }
}

@Composable
internal fun WorkspaceRetryRow(message: String, onRetry: () -> Unit) {
    GlasmioListRow(
        title = message,
        detail = LocalGithubStrings.current.Common.Retry,
        onClick = onRetry,
    )
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
