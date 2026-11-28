package moe.yumeyuka.yumehub.feature.workdetail

import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Info
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Pencil
import kotlinx.coroutines.flow.collect
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.rememberDiffExpansions
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.AppPage
import moe.yumeyuka.yumehub.ui.navigation.PageRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.AppRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalAppPageState
import moe.yumeyuka.yumehub.ui.platform.rememberPageLinkHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 议题／拉取请求详情的路由键；[title] 是列表已展示的标题，用于首帧顶栏。 */
data class WorkDetailRoute(
    val owner: String,
    val name: String,
    val kind: CollaborationKind,
    val number: Int,
    val title: String? = null,
) : AppRoute

@Composable
fun WorkDetailPage(
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
    onOpenFiles: () -> Unit,
    onOpenCommits: () -> Unit,
    onOpenCommitGroup: (List<RepositoryActivityEntry>) -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
    title: String? = null,
) {
    val viewModel: CollaborationDetailViewModel =
        koinViewModel(
            key = "work-detail-$login-$owner-$name-${kind.name}-$number",
            parameters = { parametersOf(owner, name, kind, number, login) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 轮询只在页面真在前台时做静默核对；不要走 refresh()，它会作废时间线与评论分页。
    // 首屏由 ViewModel init 发起，这里不能再抢一次强刷：invalidate() 会取消仍在飞的首个请求，
    // 让它重发同一份查询，PR 详情因此白白多等一整轮。
    PageRefreshEffect(
        viewModel,
        DataDomain.Work,
        refreshOnFirstActivation = false,
        enabled = !state.submitting,
        pollWhileVisible = state.item?.state == "open",
        onRefresh = viewModel::revalidate,
    )
    val strings = LocalAppStrings.current.WorkDetail
    val host = requireNotNull(LocalAppPageHost.current)
    val context = LocalContext.current
    var infoVisible by rememberSaveable { mutableStateOf(false) }
    var propertySheet by remember(viewModel) { mutableStateOf<WorkProperty?>(null) }
    var baseBranchVisible by remember(viewModel) { mutableStateOf(false) }
    var mergeVisible by rememberSaveable { mutableStateOf(false) }
    var subscriptionVisible by rememberSaveable { mutableStateOf(false) }
    val currentPage = host.isCurrent
    val issueEditor = viewModel.issueMetadata
    val issueMetadata by issueEditor.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, currentPage) {
        if (currentPage)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
    }
    LaunchedEffect(state.composerTarget, currentPage) {
        if (currentPage && state.composerTarget != null) {
            viewModel.takeEditorSession()?.let(host.onOpenEditor)
        }
    }
    LaunchedEffect(state.titleEditorOpen, currentPage) {
        if (currentPage && state.titleEditorOpen) {
            viewModel.takeTitleEditorSession()?.let(host.onOpenEditor)
        }
    }
    LaunchedEffect(state.bodyEditorOpen, currentPage) {
        if (currentPage && state.bodyEditorOpen) {
            viewModel.takeBodyEditorSession()?.let(host.onOpenEditor)
        }
    }
    val head = state.item?.head?.sha.orEmpty()
    val sectionKey =
        when (state.section) {
            CollaborationSection.Files,
            CollaborationSection.Commits -> "work-${state.section}:$head"
            else -> "work-${state.section}"
        }
    val expansions = rememberDiffExpansions(head)
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    val listState =
        LocalAppPageState.current?.listState(sectionKey)
            ?: listStates.getOrPut(sectionKey) { LazyListState() }
    val entries =
        remember(viewModel, sectionKey) { viewModel.entriesFor(state.section, head) }
            ?.collectAsLazyPagingItems()
    val validation =
        remember(viewModel, sectionKey) {
                viewModel.entriesRevalidation(state.section, state.item?.head?.sha)
            }
            ?.collectAsStateWithLifecycle()
            ?.value as? RevalidationState.Failed
    val timeline =
        if (state.section == CollaborationSection.Discussion) {
            viewModel.timeline.state.collectAsStateWithLifecycle().value
        } else null
    val activityGroups =
        remember(timeline?.items) {
            timeline?.items?.let(::groupWorkActivities).orEmpty()
        }
    LoadFailureToast(state.error, state.error.orEmpty())
    val pageFailure =
        timeline?.error
            ?: (entries?.loadState?.refresh as? LoadState.Error)
            ?: (entries?.loadState?.append as? LoadState.Error)
    LoadFailureToast(pageFailure, LocalAppStrings.current.Activity.LoadFailed)
    val entrySlots = entries?.let { pages ->
        rememberPagingSlots(
            pages,
            sectionKey,
            autoRefresh = false,
            accessDenied = validation?.error?.invalidatesCachedContent() == true,
        ) { entry ->
            entry.key
        }
    }
    val routeTitle =
        if (kind == CollaborationKind.PullRequest) strings.PullRequestTitle(number)
        else strings.IssueTitle(number)
    AppPage(
        title = state.item?.title ?: title?.takeIf(String::isNotBlank) ?: routeTitle,
        smallTitle = routeTitle,
        titleMaxLines = 2,
        titleLoading = state.item == null && state.error == null,
        titleSuffix = "#$number",
        titleStyle =
            GlasmioTheme.typography.headlineLarge.copy(
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
        titleOnClick =
            if (host.navigationEnabled && state.item != null) {
                { infoVisible = true }
            } else null,
        back = true,
        listState = listState,
        itemSpacing = if (state.section == CollaborationSection.Files) 8.dp else 12.dp,
        contentHeader = {
            if (state.item != null || state.error == null)
                WorkDetailHeader(
                    state,
                    kind,
                    onInformation = {
                        if (host.navigationEnabled) infoVisible = true
                    },
                    onLabels = {
                        if (host.navigationEnabled) {
                            propertySheet = WorkProperty.Labels
                        }
                    },
                    onProjects = {
                        if (host.navigationEnabled) {
                            propertySheet = WorkProperty.Projects
                        }
                    },
                    onReviewers = {
                        if (host.navigationEnabled) {
                            propertySheet = WorkProperty.Reviewers
                        }
                    },
                    onChecks = {
                        if (host.navigationEnabled) {
                            viewModel.showStatusPanel(WorkStatusPanel.Checks)
                        }
                    },
                    onIssueMetadata = { panel ->
                        if (host.navigationEnabled) issueEditor.open(panel)
                    },
                )
        },
        actions = {
            var editExpanded by remember { mutableStateOf(false) }
            val editAnchor = io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState()
            val editAvailable = state.item != null && host.navigationEnabled && !state.submitting
            moe.yumeyuka.yumehub.ui.component.ToolbarActions(
                moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                    Lucide.Info,
                    strings.Information,
                    enabled = host.navigationEnabled && state.item != null,
                    key = "info",
                ) {
                    if (host.navigationEnabled) infoVisible = true
                },
                moe.yumeyuka.yumehub.ui.component.toolbarIcon(
                    Lucide.Pencil,
                    strings.EditProperty,
                    enabled = editAvailable,
                    key = "edit",
                    menuAnchor = editAnchor,
                ) {
                    if (editAvailable) editExpanded = true
                },
            )
            WorkEditMenu(
                state = state,
                viewModel = viewModel,
                enabled = state.item != null && host.navigationEnabled,
                expanded = editExpanded,
                onExpandedChange = { editExpanded = it },
                menuAnchor = editAnchor,
            )
        },
        bottomContent = {
            WorkDetailToolbar(
                state = state,
                kind = kind,
                onMerge = { if (host.navigationEnabled) mergeVisible = true },
                onComment = { if (host.navigationEnabled) viewModel.openComposer() },
                onSubscription = { if (host.navigationEnabled) subscriptionVisible = true },
            )
        },
    ) {
        CollaborationDetailScreen(
            state,
            entries,
            entrySlots,
            validation,
            timeline,
            activityGroups,
            viewModel,
            expansions = expansions,
            onOpenFiles = onOpenFiles,
            onOpenCommits = onOpenCommits,
            onOpenCommitGroup = onOpenCommitGroup,
            onOpenCommit = onOpenCommit,
            onStatus = { if (host.navigationEnabled) viewModel.showStatusPanel(it) },
        )
    }
    val canChangeBase =
        kind == CollaborationKind.PullRequest &&
            state.item?.viewerCanChangeBase == true &&
            state.item?.state == "open" &&
            !state.submitting &&
            !state.unknownField("base") &&
            state.mergePhase != WritePhase.PENDING &&
            state.mergePhase != WritePhase.UNCERTAIN &&
            host.navigationEnabled
    if (state.item == null) return
    WorkInfoSheet(
        visible = currentPage && infoVisible,
        owner = owner,
        name = name,
        kind = kind,
        number = number,
        login = login,
        item = state.item,
        titleEditable =
            host.navigationEnabled &&
                state.item?.viewerCanEdit == true &&
                !state.submitting &&
                !state.unknownField("title"),
        onSubmitTitle = viewModel::submitTitle,
        onOpenWork = onOpenWork,
        onOpenBaseBranch =
            if (canChangeBase) {
                {
                    viewModel.searchBaseBranches("")
                    baseBranchVisible = true
                }
            } else null,
        onOpenIssueFields = {
            if (host.navigationEnabled) {
                infoVisible = false
                issueEditor.open(IssueMetadataPanel.Fields)
            }
        },
        onDismiss = {
            baseBranchVisible = false
            infoVisible = false
        },
    )
    if (kind == CollaborationKind.Issue) {
        IssueMetadataSheet(currentPage, issueMetadata, issueEditor)
    }
    if (currentPage && infoVisible && baseBranchVisible) {
        WorkBaseBranchSheet(
            state = state,
            viewModel = viewModel,
            canChangeBase = canChangeBase,
            onDismiss = { baseBranchVisible = false },
        )
    }
    WorkPropertySheet(
        visible = currentPage && propertySheet != null,
        owner = owner,
        name = name,
        kind = kind,
        number = number,
        login = login,
        property = propertySheet,
        onDismiss = { propertySheet = null },
    )
    WorkReviewSheet(
        visible = currentPage && state.statusPanel == WorkStatusPanel.Reviews,
        state = state,
        onDismiss = { if (host.isCurrent) viewModel.showStatusPanel(null) },
        onReviewEvent = viewModel::selectReviewEvent,
        onBodyChange = viewModel::changeReviewDraft,
        onSubmit = viewModel::submitReview,
    )
    WorkChecksSheet(
        visible = currentPage && state.statusPanel == WorkStatusPanel.Checks,
        item = state.item,
        onDismiss = { if (host.isCurrent) viewModel.showStatusPanel(null) },
        checks = {
            val open = rememberPageLinkHandler()
            WorkCheckGroups(state.item?.head?.sha, viewModel) { url ->
                if (host.navigationEnabled && state.statusPanel == WorkStatusPanel.Checks) {
                    viewModel.showStatusPanel(null)
                    open(url)
                }
            }
        },
    )
    WorkMergeSheet(
        visible = currentPage && mergeVisible,
        state = state,
        onDismiss = { mergeVisible = false },
        loadMessage = viewModel::mergeMessage,
        onMerge = { item, method, message ->
            if (host.navigationEnabled && viewModel.mergePullRequest(item, method, message))
                mergeVisible = false
        },
    )
    WorkSubscriptionSheet(
        visible = currentPage && subscriptionVisible,
        state = state,
        onDismiss = { subscriptionVisible = false },
        onSelect = { targetState ->
            if (host.navigationEnabled) viewModel.updateSubscription(targetState)
        },
        onRetry = {
            if (host.navigationEnabled) viewModel.loadSubscription(force = true)
        },
    )
    if (currentPage) CollaborationDeleteCommentDialog(state, viewModel)
}
