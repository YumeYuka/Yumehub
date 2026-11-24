package moe.yumeyuka.yumehub.feature.workdetail

import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.flow.collect
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.WorkProperty
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.RepositoryActivityEntry
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.DetailToolbarPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkDetailHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.navigation.GithubDetailKind
import moe.yumeyuka.yumehub.ui.navigation.GithubDetailPresentation
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.githubDetailPageSettled
import moe.yumeyuka.yumehub.ui.navigation.rememberGithubDetailPresentation
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 议题／拉取请求详情的路由键。 */
data class WorkDetailRoute(
    val owner: String,
    val name: String,
    val kind: CollaborationKind,
    val number: Int,
) : GithubRoute

@Composable
fun WorkDetailPage(
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    login: String,
    onOpenInBrowser: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenCommits: () -> Unit,
    onOpenCommitGroup: (List<RepositoryActivityEntry>) -> Unit,
    onOpenCommit: (String) -> Unit,
    onOpenWork: (String, String, CollaborationKind, Int) -> Unit,
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
    GithubRefreshEffect(
        viewModel,
        DataDomain.Work,
        refreshOnFirstActivation = false,
        enabled = !state.submitting,
        pollWhileVisible = state.item?.state == "open",
        onRefresh = viewModel::revalidate,
    )
    val strings = LocalGithubStrings.current.WorkDetail
    val host = requireNotNull(LocalGithubPageHost.current)
    val context = LocalContext.current
    var infoVisible by rememberSaveable { mutableStateOf(false) }
    var propertySheet by remember(viewModel) { mutableStateOf<WorkProperty?>(null) }
    var baseBranchVisible by remember(viewModel) { mutableStateOf(false) }
    var mergeVisible by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(state.titleEditorOpen) {
        if (state.titleEditorOpen) {
            viewModel.takeTitleEditorSession()?.let(host.onOpenEditor)
        }
    }
    LaunchedEffect(state.bodyEditorOpen) {
        if (state.bodyEditorOpen) {
            viewModel.takeBodyEditorSession()?.let(host.onOpenEditor)
        }
    }
    val listStates = remember { mutableMapOf<CollaborationSection, LazyListState>() }
    val listState =
        LocalGithubPageState.current?.listState("work-section:${state.section.name}")
            ?: listStates.getOrPut(state.section) { LazyListState() }
    val entries = viewModel.entriesFor(state.section)?.collectAsLazyPagingItems()
    val timeline =
        if (state.section == CollaborationSection.Discussion) {
            viewModel.timeline.state.collectAsStateWithLifecycle().value
        } else null
    val activityGroups =
        remember(timeline?.items) {
            timeline?.items?.let(::groupWorkActivities).orEmpty()
        }
    // 已经展示过的页面返回组合后不再看加载位：刷新、重排分页都不允许占位重新盖住正文。
    // key 只取路由内稳定的分节标识，ViewModel 实例与重试次数都不参与，返回时必须命中同一条记录。
    val presentationKey = "work-section-${state.section.name}"
    val settled = LocalGithubPageState.current?.detailPresented(presentationKey) == true
    val initialFailure =
        (!settled && state.error != null) ||
            (!settled &&
                (entries?.loadState?.refresh is LoadState.Error ||
                    (timeline != null && !timeline.ready && timeline.error != null)))
    // 订阅状态只在详情返回后串行拉取，属于底栏图标的次要信息；让它参与首屏会把详情页首帧
    // 硬生生推后一个往返，这里只等详情与当前分节第一页。
    val pageReady =
        entries?.let { githubDetailPageSettled(it, state.item != null, settled) }
            ?: (timeline?.ready == true || settled)
    val initialReady =
        state.item != null && (settled || !state.loading) && !initialFailure && pageReady
    var chromePresentation by
        remember(viewModel) { mutableStateOf<GithubDetailPresentation?>(null) }
    val presentation =
        rememberGithubDetailPresentation(
            key = presentationKey,
            kind =
                when {
                    state.section == CollaborationSection.Files -> GithubDetailKind.CommitFiles
                    kind == CollaborationKind.PullRequest -> GithubDetailKind.PullRequest
                    else -> GithubDetailKind.Issue
                },
            dataReady = initialReady,
            failed = initialFailure,
            chrome = chromePresentation,
        )
    SideEffect {
        if (chromePresentation == null && presentation.interactive)
            chromePresentation = presentation
    }
    GithubLoadFailureToast(state.error, state.error.orEmpty())
    val pageFailure =
        timeline?.error
            ?: (entries?.loadState?.refresh as? LoadState.Error)
            ?: (entries?.loadState?.append as? LoadState.Error)
    GithubLoadFailureToast(pageFailure, LocalGithubStrings.current.Activity.LoadFailed)
    var displayedItem by remember(viewModel) { mutableStateOf(state.item) }
    SideEffect { if (initialReady && !initialFailure) displayedItem = state.item }
    val shownState =
        state.copy(
            item = if (initialReady) state.item else displayedItem ?: state.item,
            error = if (settled) null else state.error,
        )
    val entrySlots = entries?.let { pages ->
        rememberGithubPagingSlots(
            pages,
            "work-${state.section}",
            contentReady = initialReady,
            autoRefresh = false,
        ) { entry ->
            entry.key
        }
    }
    val retryDetail: () -> Unit = {
        viewModel.refresh()
        entries?.retry()
    }
    GithubPage(
        title = shownState.item?.title.orEmpty(),
        smallTitle =
            if (kind == CollaborationKind.PullRequest) strings.PullRequestTitle(number)
            else strings.IssueTitle(number),
        titleMaxLines = 3,
        titleSuffix = shownState.item?.let { "#$number" },
        titleStyle =
            GlasmioTheme.typography.headlineLarge.copy(
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
            ),
        back = true,
        listState = listState,
        locked = state.submitting && !state.sendingInBackground || issueMetadata.saving,
        itemSpacing = if (state.section == CollaborationSection.Files) 8.dp else 12.dp,
        detailPresentation = presentation,
        onRetryDetail = retryDetail,
        detailHeaderPlaceholder = { WorkDetailHeaderPlaceholder() },
        detailBottomPlaceholder = {
            DetailToolbarPlaceholder(
                showTrailingAction =
                    kind == CollaborationKind.PullRequest &&
                        (state.item == null || state.item?.state == "open"),
            )
        },
        contentHeader = {
            WorkDetailHeader(
                shownState,
                kind,
                onInformation = {
                    if (host.navigationEnabled && presentation.interactive) infoVisible = true
                },
                onLabels = {
                    if (host.navigationEnabled && presentation.interactive) {
                        propertySheet = WorkProperty.Labels
                    }
                },
                onProjects = {
                    if (host.navigationEnabled && presentation.interactive) {
                        propertySheet = WorkProperty.Projects
                    }
                },
                onReviewers = {
                    if (host.navigationEnabled && presentation.interactive) {
                        propertySheet = WorkProperty.Reviewers
                    }
                },
                onChecks = {
                    if (host.navigationEnabled && presentation.interactive) {
                        viewModel.showStatusPanel(WorkStatusPanel.Checks)
                    }
                },
                onIssueMetadata = { panel ->
                    if (host.navigationEnabled && presentation.interactive) issueEditor.open(panel)
                },
            )
        },
        actions = {
            GlasmioIconButton(
                onClick = {
                    if (host.navigationEnabled && presentation.interactive) infoVisible = true
                },
                enabled =
                    presentation.interactive &&
                        state.item != null &&
                        (!state.submitting || state.sendingInBackground),
            ) {
                GithubIcon(Lucide.Info, contentDescription = strings.Information)
            }
            WorkEditMenu(
                state,
                viewModel,
                enabled = presentation.interactive && host.navigationEnabled,
            )
        },
        bottomContent = {
            WorkDetailToolbar(
                state = state,
                kind = kind,
                onMerge = { if (host.navigationEnabled) mergeVisible = true },
                onComment = { if (host.navigationEnabled) viewModel.openComposer() },
                onSubscription = { if (host.navigationEnabled) viewModel.toggleSubscription() },
            )
        },
    ) {
        CollaborationDetailScreen(
            shownState,
            entries,
            entrySlots,
            timeline,
            activityGroups,
            viewModel,
            onOpenInBrowser = onOpenInBrowser,
            onOpenFiles = onOpenFiles,
            onOpenCommits = onOpenCommits,
            onOpenCommitGroup = onOpenCommitGroup,
            onOpenCommit = onOpenCommit,
            onStatus = { if (host.navigationEnabled) viewModel.showStatusPanel(it) },
        )
    }
    val canChangeBase =
        kind == CollaborationKind.PullRequest && state.item?.viewerCanChangeBase == true &&
            state.item?.state == "open" && !state.submitting && !state.submissionUncertain &&
            presentation.interactive && host.navigationEnabled
    WorkInfoSheet(
        visible = currentPage && infoVisible,
        owner = owner,
        name = name,
        kind = kind,
        number = number,
        login = login,
        item = shownState.item,
        onOpenWork = onOpenWork,
        onOpenBaseBranch =
            if (canChangeBase) {
                {
                    viewModel.searchBaseBranches("")
                    baseBranchVisible = true
                }
            } else null,
        onOpenIssueFields = {
            if (host.navigationEnabled && presentation.interactive) {
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
            val open = rememberGithubUriHandler()
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
            if (host.navigationEnabled) {
                viewModel.mergePullRequest(item, method, message) { mergeVisible = false }
            }
        },
    )
    CollaborationDeleteCommentDialog(state, viewModel)
}
