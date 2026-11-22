package moe.yumeyuka.yumehub.feature.repository

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.yumeyucca.lucide.Bell
import io.github.yumeyucca.lucide.CircleDot
import io.github.yumeyucca.lucide.Ellipsis
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.List
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.MessagesSquare
import io.github.yumeyucca.lucide.Pencil
import io.github.yumeyucca.lucide.Pin
import io.github.yumeyucca.lucide.Plus
import io.github.yumeyucca.lucide.Share
import java.util.LinkedHashMap
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.GithubLinkTarget
import moe.yumeyuka.yumehub.core.model.GithubRepositoryLocation
import moe.yumeyuka.yumehub.core.model.parseGithubLink
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.AvatarPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.platform.LocalGithubLinkHandler
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** 仓库详情的路由键。 */
data class RepositoryRoute(
    val owner: String,
    val name: String,
    val readme: GithubRepositoryLocation.Readme? = null,
) : GithubRoute

@Composable
fun RepositoryPage(
    owner: String,
    name: String,
    onOpenWorkList:
        (
            owner: String,
            name: String,
            kind: CollaborationKind,
            createInitially: Boolean,
        ) -> Unit,
    onCreateDiscussion: (owner: String, name: String) -> Unit,
    onOpenWorkspaces:
        (
            owner: String,
            name: String,
            ref: String?,
            path: String,
            file: Boolean,
            anchor: String?,
        ) -> Unit,
    onOpenRepository: (owner: String, name: String) -> Unit,
    onExit: () -> Unit,
    readme: GithubRepositoryLocation.Readme? = null,
) {
    val strings = LocalGithubStrings.current.Repository
    val profileStrings = LocalGithubStrings.current.Profile
    val viewModel: RepositoryDetailViewModel =
        koinViewModel(key = "repository-$owner-$name", parameters = { parametersOf(owner, name) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overviewViewModel: RepositoryOverviewViewModel =
        koinViewModel(
            key = "repository-home-$owner-$name",
            parameters = { parametersOf(owner, name) },
        )
    val overview by overviewViewModel.uiState.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Repositories,
        DataDomain.Content,
        refreshOnFirstActivation = false,
    ) {
        viewModel.refresh()
        overviewViewModel.refresh(force = true)
    }
    val forkViewModel: RepositoryForkViewModel = koinViewModel(key = "repository-fork-$owner-$name")
    val forkState by forkViewModel.uiState.collectAsStateWithLifecycle()
    val listsViewModel: RepositoryListsViewModel =
        koinViewModel(key = "repository-lists-$owner-$name")
    val repositoryLists by listsViewModel.uiState.collectAsStateWithLifecycle()
    val latestOpenRepository by rememberUpdatedState(onOpenRepository)
    // 首页收藏概览与仓库详情来自不同缓存键，必须等概览本身到达后再取固定状态。
    val homeOverview = overview.overview
    LaunchedEffect(overviewViewModel, homeOverview) {
        if (homeOverview != null) overviewViewModel.loadPinned()
    }
    // 回到被覆盖的页面时不重新应用入口，保留用户后续浏览的位置。
    var linkApplied by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val common = LocalGithubStrings.current.Common
    val openExternal = rememberExternalUriHandler()
    val openGithubLink = rememberGithubUriHandler()
    val details = (state.repository as? RepositoryLoad.Ready)?.value
    val fullNameParts = details?.fullName?.split('/', limit = 2)
    val currentOwner =
        details?.owner?.login?.takeIf(String::isNotBlank)
            ?: fullNameParts?.getOrNull(0)?.takeIf(String::isNotBlank)
            ?: owner
    val currentName =
        details?.name?.takeIf(String::isNotBlank)
            ?: fullNameParts?.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: name
    // canonical 名称是当前页的资源信息，不替换路由，避免加载完成后再跑一次转场。
    val openWorkspaces: (String?, String, Boolean, String?) -> Unit = { ref, path, file, anchor ->
        onOpenWorkspaces(currentOwner, currentName, ref, path, file, anchor)
    }
    val latestOpenWorkspaces by rememberUpdatedState(openWorkspaces)
    LaunchedEffect(viewModel, readme, details) {
        if (readme != null && details != null && !linkApplied) {
            viewModel.openReadme()
            linkApplied = true
        }
    }
    val repositoryUrl =
        remember(currentOwner, currentName) {
            Uri.Builder()
                .scheme("https")
                .authority("github.com")
                .appendPath(currentOwner)
                .appendPath(currentName)
                .build()
                .toString()
        }
    val openSheet: (RepositorySheet) -> Unit = { sheet ->
        if (host.navigationEnabled && details != null) overviewViewModel.open(sheet, details)
    }
    val openFork = { if (host.navigationEnabled && details != null) forkViewModel.open(details) }
    LaunchedEffect(forkState?.created) {
        forkState?.created?.let { created ->
            val forkOwner = created.owner?.login
            val forkName = created.name
            if (host.navigationEnabled && forkOwner != null && forkName != null) {
                forkViewModel.consumeCreated()
                overviewViewModel.refresh(force = true)
                latestOpenRepository(forkOwner, forkName)
            }
        }
    }
    val share = {
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, repositoryUrl)
                    },
                    strings.ShareLink,
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, common.ExternalLinkOpenFailed, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(overviewViewModel) {
        overviewViewModel.events.collect { event ->
            when (event) {
                is RepositoryOverviewEvent.Failed ->
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pageKey =
        when (state.page) {
            is RepositoryDetailPage.Readme -> "readme"
            is RepositoryDetailPage.License -> "license"
            RepositoryDetailPage.Overview -> "overview"
        }
    // 没有外壳注入时仍分别保留概览和 README 的滚动位置。
    val fallbackListStates = remember { LinkedHashMap<String, LazyListState>(16, 0.75f, true) }
    val listState =
        LocalGithubPageState.current?.listState(pageKey)
            ?: fallbackListStates
                .getOrPut(pageKey) { LazyListState() }
                .also {
                    while (fallbackListStates.size > 32) {
                        fallbackListStates.remove(fallbackListStates.keys.first())
                    }
                }
    val currentRef =
        state.selectedRef
            ?: (state.repository as? RepositoryLoad.Ready)?.value?.defaultBranch?.let {
                "refs/heads/$it"
            }
    val overviewActions = state.page == RepositoryDetailPage.Overview
    if (!overviewActions) {
        GithubBackHandler { if (host.backEnabled) viewModel.navigateUp() }
    }
    // 简介在头部只显示两行，点击后用 alert 展示完整内容，单个确认按钮关闭。
    var descriptionOpen by remember { mutableStateOf(false) }
    var currentReadmeAnchor by rememberSaveable { mutableStateOf(readme?.anchor) }
    val parentLinkHandler = LocalGithubLinkHandler.current
    val repositoryLinkHandler: (String) -> Unit =
        remember(currentOwner, currentName, viewModel, parentLinkHandler) {
            { url ->
                val target = parseGithubLink(url)
                if (
                    target is GithubLinkTarget.Repository &&
                        target.owner.equals(currentOwner, ignoreCase = true) &&
                        target.name.equals(currentName, ignoreCase = true)
                ) {
                    when (val loc = target.location) {
                        null -> {
                            viewModel.showOverview()
                            currentReadmeAnchor = null
                        }
                        is GithubRepositoryLocation.Readme -> {
                            viewModel.openReadme()
                            currentReadmeAnchor = loc.anchor
                        }
                        is GithubRepositoryLocation.GitObject -> {
                            parentLinkHandler?.invoke(url)
                        }
                    }
                } else {
                    parentLinkHandler?.invoke(url)
                }
            }
        }
    val loadFailure =
        (state.repository as? RepositoryLoad.Error)
            ?: when (val page = state.page) {
                is RepositoryDetailPage.Readme -> page.preview as? RepositoryLoad.Error
                is RepositoryDetailPage.License -> page.contents as? RepositoryLoad.Error
                RepositoryDetailPage.Overview -> state.overviewReadme as? RepositoryLoad.Error
            }
    GithubLoadFailureToast(loadFailure, loadFailure?.message.orEmpty())
    CompositionLocalProvider(LocalGithubLinkHandler provides repositoryLinkHandler) {
        GithubPage(
            title =
                when (state.page) {
                    RepositoryDetailPage.Overview -> currentName
                    is RepositoryDetailPage.Readme -> strings.Readme
                    is RepositoryDetailPage.License -> strings.License
                },
            titleStyle = if (overviewActions) GlasmioTheme.typography.headlineLarge else null,
            // 总览页大标题上方放 @属主徽标、行尾放属主头像，大标题自然下移让位。
            // 属主来自路由，头像在数据到达前先占位，顶栏高度不随加载变化。
            titleEyebrow =
                if (overviewActions) {
                    {
                        Text(
                            "@$currentOwner",
                            style = GlasmioTheme.textStyles.footnote,
                            fontWeight = FontWeight.Bold,
                            color = GlasmioTheme.colors.secondaryContent,
                            maxLines = 1,
                        )
                    }
                } else null,
            titleTrailing =
                if (overviewActions) {
                    {
                        GithubAvatar(
                            url = details?.owner?.avatarUrl,
                            login = details?.owner?.login ?: currentOwner,
                            type = details?.owner?.type,
                            size = 56.dp,
                            placeholder = { AvatarPlaceholder(56.dp) },
                        )
                    }
                } else null,
            onBack = {
                if (host.backEnabled && !viewModel.navigateUp()) {
                    onExit()
                }
            },
            listState = listState,
            locked = forkState?.submitting == true,
            itemSpacing = 12.dp,
            actions = {
                Box {
                var creating by remember(viewModel, pageKey) { mutableStateOf(false) }
                var expanded by remember(viewModel, pageKey) { mutableStateOf(false) }
                val createAnchor = rememberGlasmioMenuAnchorState()
                val moreAnchor = rememberGlasmioMenuAnchorState()
                GithubToolbarActions(
                    if (overviewActions)
                        toolbarIcon(Lucide.Plus, strings.Create, menuAnchor = createAnchor) {
                            if (host.navigationEnabled) creating = true
                        }
                    else null,
                    toolbarIcon(Lucide.Ellipsis, strings.Actions, menuAnchor = moreAnchor) {
                        if (host.navigationEnabled) expanded = true
                    },
                )
                if (overviewActions) {
                    GlasmioMenu(
                        expanded = creating,
                        onExpandedChange = { creating = it },
                        itemCount = if (overview.overview?.discussionsEnabled == true) 3 else 2,
                        menuAnchorState = createAnchor,
                        externalAnchorBounds = createAnchor.bounds,
                        anchor = {},
                    ) { backdrop, close ->
                        GlasmioMenuItem(
                            title = strings.NewIssue,
                            backdrop = backdrop,
                            icon = {
                                GithubIcon(
                                    Lucide.CircleDot,
                                    contentDescription = null,
                                    size = 20.dp,
                                )
                            },
                            enabled =
                                overview.overview?.issuesEnabled == true &&
                                    overview.overview?.archived != true,
                            onClick = {
                                close()
                                creating = false
                                onOpenWorkList(
                                    currentOwner,
                                    currentName,
                                    CollaborationKind.Issue,
                                    true,
                                )
                            },
                        )
                        GlasmioMenuItem(
                            title = strings.NewPullRequest,
                            backdrop = backdrop,
                            icon = {
                                GithubIcon(
                                    Lucide.GitPullRequest,
                                    contentDescription = null,
                                    size = 20.dp,
                                )
                            },
                            enabled =
                                overview.overview != null && overview.overview?.archived != true,
                            onClick = {
                                close()
                                creating = false
                                onOpenWorkList(
                                    currentOwner,
                                    currentName,
                                    CollaborationKind.PullRequest,
                                    true,
                                )
                            },
                        )
                        if (overview.overview?.discussionsEnabled == true) {
                            GlasmioMenuItem(
                                title = strings.NewDiscussion,
                                backdrop = backdrop,
                                icon = {
                                    GithubIcon(Lucide.MessagesSquare, contentDescription = null, size = 20.dp)
                                },
                                enabled = overview.overview?.archived != true,
                                onClick = {
                                    close()
                                    creating = false
                                    onCreateDiscussion(currentOwner, currentName)
                                },
                            )
                        }
                    }
                }
                GlasmioMenu(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    itemCount = if (!overviewActions) 1 else if (overview.pinned == null) 4 else 5,
                    menuAnchorState = moreAnchor,
                    externalAnchorBounds = moreAnchor.bounds,
                    anchor = {},
                ) { backdrop, close ->
                    if (overviewActions) {
                        GlasmioMenuItem(
                            title = strings.NotificationSettings,
                            backdrop = backdrop,
                            enabled = details != null,
                            icon = {
                                GithubIcon(Lucide.Bell, contentDescription = null, size = 20.dp)
                            },
                            onClick = {
                                close()
                                expanded = false
                                openSheet(RepositorySheet.Notifications)
                            },
                        )
                        GlasmioMenuItem(
                            title = profileStrings.AddToList,
                            backdrop = backdrop,
                            enabled = details?.nodeId != null && !overview.busy,
                            icon = {
                                GithubIcon(Lucide.List, contentDescription = null, size = 20.dp)
                            },
                            onClick = {
                                close()
                                expanded = false
                                details?.nodeId?.let(listsViewModel::open)
                            },
                        )
                    }
                    GlasmioMenuItem(
                        title = strings.ShareLink,
                        backdrop = backdrop,
                        icon = {
                            GithubIcon(Lucide.Share, contentDescription = null, size = 20.dp)
                        },
                        onClick = {
                            close()
                            expanded = false
                            share()
                        },
                    )
                    if (overviewActions) {
                        val pinned = overview.pinned
                        if (pinned != null)
                            GlasmioMenuItem(
                                title = if (pinned) strings.RemoveFavorite else strings.PinToHome,
                                backdrop = backdrop,
                                enabled = details != null && !overview.busy,
                                icon = {
                                    GithubIcon(
                                        Lucide.Pin,
                                        contentDescription = null,
                                        size = 20.dp,
                                        filled = pinned,
                                    )
                                },
                                onClick = {
                                    close()
                                    expanded = false
                                    details?.let { overviewViewModel.pin(it, !pinned) }
                                },
                            )
                        GlasmioMenuItem(
                            title = strings.EditDescription,
                            backdrop = backdrop,
                            enabled =
                                details != null &&
                                    !overview.busy &&
                                    overview.overview?.canEditDescription == true,
                            icon = {
                                GithubIcon(Lucide.Pencil, contentDescription = null, size = 20.dp)
                            },
                            onClick = {
                                close()
                                expanded = false
                                openSheet(RepositorySheet.Description)
                            },
                        )
                    }
                }
                }
            },
        ) {
            RepositoryDetailScreen(
                strings = strings,
                state = state,
                onBrowseFiles = {
                    if (host.navigationEnabled) latestOpenWorkspaces(currentRef, "", false, null)
                },
                onSwitchRef = { if (host.navigationEnabled) viewModel.switchRef(it) },
                onLoadRefs = { if (host.navigationEnabled) viewModel.loadRefs() },
                // 转场输入由 Decompose 拦截，栈顶归属只校验回调，避免整组控件切换到禁用色。
                enabled = true,
                onOpenIssues = {
                    if (host.navigationEnabled)
                        onOpenWorkList(currentOwner, currentName, CollaborationKind.Issue, false)
                },
                onOpenPullRequests = {
                    if (host.navigationEnabled)
                        onOpenWorkList(
                            currentOwner,
                            currentName,
                            CollaborationKind.PullRequest,
                            false,
                        )
                },
                onOpenSource = { path ->
                    if (host.navigationEnabled) {
                        if (path.isNullOrBlank()) latestOpenWorkspaces(currentRef, "", false, null)
                        else latestOpenWorkspaces(currentRef, path, true, null)
                    }
                },
                readmeAnchor = currentReadmeAnchor,
                overviewState = overview,
                onToggleStar = {
                    if (host.navigationEnabled && !overview.busy) overviewViewModel.star()
                },
                onOpenDescription = {
                    if (host.navigationEnabled && details?.description?.isNotBlank() == true)
                        descriptionOpen = true
                },
                onRetryOverviewReadme = {
                    if (host.navigationEnabled) viewModel.retryOverviewReadme()
                },
                onOpenLicense = { if (host.navigationEnabled) viewModel.openLicense() },
                onRepositoryExternal = { path ->
                    if (host.navigationEnabled) {
                        // 仓库设置里的网站是完整地址，其余入口传的是仓库内相对路径。
                        val target =
                            if (path.startsWith("http://") || path.startsWith("https://")) path
                            else "$repositoryUrl/$path"
                        when (path) {
                            "fork" -> openFork()
                            "releases" -> openGithubLink(target)
                            else -> openExternal(target)
                        }
                    }
                },
            )
        }
    }
    RepositoryManagementSheet(
        state = overview,
        onDismiss = overviewViewModel::dismiss,
        onDescription = overviewViewModel::description,
        onSubscriptionChange = overviewViewModel::changeSubscription,
        onSave = overviewViewModel::save,
        onRetry = { overview.management?.sheet?.let(openSheet) },
    )
    if (repositoryLists.failed) {
        GithubLoadFailureToast(Unit, profileStrings.ListLoadFailed)
    }
    RepositoryListsSheet(
        state = repositoryLists,
        onDismiss = listsViewModel::dismiss,
        onRetry = { details?.nodeId?.let(listsViewModel::open) },
        onToggle = listsViewModel::toggle,
        onSave = {
            listsViewModel.save { success ->
                if (success) {
                    Toast.makeText(
                            context,
                            profileStrings.ListsSaved,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                } else {
                    Toast.makeText(
                            context,
                            profileStrings.AddToListFailed,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }
        },
    )
    RepositoryForkSheet(
        state = forkState,
        onDismiss = forkViewModel::dismiss,
        onName = forkViewModel::name,
        onDefaultBranchOnly = forkViewModel::defaultBranchOnly,
        onCheckName = forkViewModel::checkName,
        onCreate = forkViewModel::create,
    )
    // 关闭时保留调用，仅设 visible=false，让库完成退场动画。
    GlasmioAlertDialog(
        visible = descriptionOpen,
        onDismissRequest = { descriptionOpen = false },
        title = strings.Description,
        message = details?.description.orEmpty(),
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    label = common.Confirm,
                    onClick = { descriptionOpen = false },
                ),
            ),
    )
}
