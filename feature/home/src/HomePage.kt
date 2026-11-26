package moe.yumeyuka.yumehub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.*
import java.text.DateFormat
import java.util.Date
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.PendingMutationSummary
import moe.yumeyuka.yumehub.locale.GithubStrings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.RepositoryRow
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRefreshEffect
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun HomePage(
    login: String,
    onOpenProfile: (() -> Unit)? = null,
    onOpenSearch: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenWork: (CollaborationKind) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenRepository: (RepositoryDto) -> Unit,
    onOpenOrganizations: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenDiscussions: () -> Unit,
) {
    // 收藏模型跟随登录会话；AppRoot 的 key(login) 负责切号时重建，本地快照按账号隔离。
    val viewModel: HomeFavoritesViewModel =
        koinViewModel(
            key = "home-favorites-$login",
            parameters = { parametersOf(login) },
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingMutations by viewModel.pendingMutations.collectAsStateWithLifecycle()
    val recoveryFailed by viewModel.recoveryFailed.collectAsStateWithLifecycle()
    var pendingSheetVisible by remember { mutableStateOf(false) }
    var acknowledgeCandidate by remember { mutableStateOf<PendingMutationSummary?>(null) }
    GithubRefreshEffect(viewModel, DataDomain.Repositories, onRefresh = viewModel::refresh)
    val work: HomeWorkViewModel = koinViewModel()
    val currentLayout by work.layout.collectAsStateWithLifecycle()
    var draftOrder by rememberSaveable(login) { mutableStateOf(emptyList<String>()) }
    val workDraft = remember(draftOrder) { HomeWorkLayout.decode(draftOrder, emptyList()) }
    val allStrings = LocalGithubStrings.current
    val strings = allStrings.Home
    val common = allStrings.Common
    val profileTitle = allStrings.Profile.Title
    val retry = common.Retry
    var editingWork by rememberSaveable(login) { mutableStateOf(false) }
    val onEditWork = {
        draftOrder = currentLayout.encode()
        editingWork = true
    }
    if (
        state == HomeFavoritesState.Error ||
            (state as? HomeFavoritesState.Content)?.refreshFailed == true
    ) {
        GithubLoadFailureToast(Unit, strings.FavoritesLoadFailed)
    }
    GithubPage(
        title = strings.Title,
        back = false,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlasmioIconButton(onClick = onOpenSearch) {
                    GithubIcon(
                        Lucide.Search,
                        contentDescription = LocalGithubStrings.current.Common.Search,
                    )
                }
                GlasmioIconButton(onClick = onEditWork) {
                    GithubIcon(Lucide.Pencil, contentDescription = strings.EditHome)
                }
            }
        },
    ) {
        if (recoveryFailed) {
            item(key = "home-mutation-recovery-failed") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = common.MutationRecoveryFailed,
                        detail = common.Retry,
                        showDisclosure = false,
                        onClick = viewModel::retryMutationRecovery,
                    )
                }
            }
        }
        if (pendingMutations.isNotEmpty()) {
            item(key = "home-pending-mutations") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = common.MutationUncertain,
                        detail = "${pendingMutations.size}",
                        onClick = { pendingSheetVisible = true },
                    )
                }
            }
        }
        HomeScreen(
            items = currentLayout.shown,
            strings = strings,
            onEdit = onEditWork,
            onOpenRepositories = onOpenRepositories,
            onOpenWork = onOpenWork,
            onOpenStarred = onOpenStarred,
            onOpenOrganizations = onOpenOrganizations,
            onOpenProjects = onOpenProjects,
            onOpenDiscussions = onOpenDiscussions,
        )
        val favorites = state as? HomeFavoritesState.Content
        if (onOpenProfile != null) {
            item(key = "home-profile") {
                GlasmioGroupedList { GlasmioListRow(title = profileTitle, onClick = onOpenProfile) }
            }
        }
        item(key = "home-favorites") {
            GithubSection(strings.Favorites) {
                GithubLoadingContent(
                    loading = state == HomeFavoritesState.Loading,
                    placeholder = { ListRowPlaceholder() },
                ) {
                    if (favorites == null) {
                        GlasmioListRow(title = retry, onClick = viewModel::retry)
                    } else if (favorites.repositories.isEmpty()) {
                        GlasmioListRow(
                            title = strings.NoFavorites,
                            subtitle = strings.FavoritesHint,
                            systemName = null,
                        )
                    } else
                        Column {
                            favorites.repositories.forEachIndexed { index, repository ->
                                androidx.compose.runtime.key(repository.fullName) {
                                    RepositoryRow(
                                        repository = repository,
                                        onClick = { onOpenRepository(repository) },
                                        compact = true,
                                        showTopSeparator = index > 0,
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
    HomeWorkSheet(
        visible = editingWork,
        draft = workDraft,
        favorites = viewModel,
        onDismiss = { editingWork = false },
        onChange = { draftOrder = it.encode() },
        onConfirm = {
            work.save(workDraft)
            editingWork = false
        },
    )
    PendingMutationsSheet(
        visible = pendingSheetVisible && pendingMutations.isNotEmpty(),
        pending = pendingMutations,
        allStrings = allStrings,
        onDismiss = { pendingSheetVisible = false },
        onAcknowledge = { acknowledgeCandidate = it },
    )
    acknowledgeCandidate?.let { candidate ->
        GlasmioAlertDialog(
            visible = true,
            title = common.MutationUncertain,
            message = common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        common.Cancel,
                        onClick = { acknowledgeCandidate = null },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        common.MutationAcknowledged,
                        onClick = {
                            acknowledgeCandidate = null
                            viewModel.acknowledgeMutation(candidate.operationId)
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeCandidate = null },
        )
    }
}

@Composable
private fun PendingMutationsSheet(
    visible: Boolean,
    pending: List<PendingMutationSummary>,
    allStrings: GithubStrings,
    onDismiss: () -> Unit,
    onAcknowledge: (PendingMutationSummary) -> Unit,
) {
    val common = allStrings.Common
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        fitContent = true,
        title = common.MutationUncertain,
        topBar = {
            GlasmioTopBar(
                title = common.MutationUncertain,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
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
                pending.forEachIndexed { index, summary ->
                    val label = mutationKindLabel(summary.kind, allStrings)
                    val time =
                        if (summary.startedAtMillis > 0L) {
                            timeFormat.format(Date(summary.startedAtMillis))
                        } else null
                    val subtitle =
                        listOfNotNull(summary.target, time).joinToString(" · ")
                    GlasmioListRow(
                        title = label,
                        subtitle = subtitle,
                        detail = common.MutationAcknowledged,
                        showTopSeparator = index > 0,
                        showDisclosure = false,
                        onClick = { onAcknowledge(summary) },
                    )
                }
            }
        }
    }
}

private fun mutationKindLabel(kind: MutationKind, strings: GithubStrings): String =
    when (kind) {
        MutationKind.STAR -> strings.Repository.Star
        MutationKind.FOLLOW -> strings.Profile.FollowUser
        MutationKind.REACTION -> strings.Activity.AddReaction
        MutationKind.VIEWER_STATUS -> strings.Profile.EditStatus
        MutationKind.DASHBOARD_PINS -> strings.Home.Favorites
        MutationKind.REPO_SUBSCRIPTION,
        MutationKind.WORK_SUBSCRIPTION -> strings.Repository.Watchers
        MutationKind.REPO_DESCRIPTION -> strings.Repository.Description
        MutationKind.REPO_LISTS -> strings.Profile.AddToList
        MutationKind.NOTIFICATION -> strings.Notifications.Done
        MutationKind.DISCUSSION_UPVOTE,
        MutationKind.DISCUSSION_ANSWER,
        MutationKind.DISCUSSION_VOTE,
        MutationKind.DISCUSSION_CREATE -> strings.Repository.NewDiscussion
        MutationKind.WORK_FIELD,
        MutationKind.ISSUE_FIELD,
        MutationKind.WORK_PROPERTY -> strings.WorkDetail.SaveChanges
        MutationKind.COMMENT -> strings.WorkDetail.Comment
        MutationKind.REVIEW -> strings.WorkDetail.Review
        MutationKind.MERGE -> strings.WorkDetail.MergePullRequest
        MutationKind.WORK_CREATE -> strings.Repository.NewIssue
        MutationKind.LIST_CREATE -> strings.Profile.NewList
        MutationKind.FILE_COMMIT -> strings.Workspaces.CommitChanges
        MutationKind.FORK -> strings.Repository.ForkRepository
        MutationKind.WORKFLOW_DISPATCH -> strings.Activity.RunWorkflow
        MutationKind.CHECK_RERUN -> strings.Activity.RerunFailed
        MutationKind.FEED_FILTERS -> strings.Common.MutationUncertain
        MutationKind.TWO_FACTOR_DECISION -> strings.TwoFactor.NotificationTitle
    }
