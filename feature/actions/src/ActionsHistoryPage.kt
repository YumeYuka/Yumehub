package moe.yumeyuka.yumehub.feature.actions

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal class ActionsHistoryModel(
    val workflows: WorkflowRepository,
    private val activities: RepositoryActivityRepository,
    private val route: ActionsRoute,
    private val account: String,
) : ViewModel() {
    val selected = MutableStateFlow<WorkflowOption?>(null)
    var catalog by mutableStateOf<WorkflowCatalog?>(null)
    val commands = WorkflowCommands(workflows.mutations(account), MutationKind.WORKFLOW_DISPATCH, viewModelScope)

    fun target(workflowId: String) = "${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}:$workflowId"

    fun dispatch(workflowId: String, branch: String, inputs: Map<String, String>): Boolean =
        commands.submit(target(workflowId)) { workflows.dispatch(account, workflowId, branch, inputs) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val runs =
        selected
            .flatMapLatest { workflow ->
                if (workflow == null) workflows.history(account, route.owner, route.name)
                else
                    activities.items(
                        account,
                        route.owner,
                        route.name,
                        RepositoryActivityKind.WorkflowRuns,
                        workflow.id,
                    )
            }
            .cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val revalidation: StateFlow<RevalidationState> =
        selected
            .flatMapLatest { workflow ->
                if (workflow == null) {
                    workflows.revalidation(account, workflows.historyKey(route.owner, route.name))
                } else {
                    activities.revalidation(
                        account,
                        activities.itemsKey(
                            route.owner,
                            route.name,
                            RepositoryActivityKind.WorkflowRuns,
                            workflow.id,
                        ),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RevalidationState.Idle)

    fun retryRevalidation() {
        val workflow = selected.value
        if (workflow == null) {
            workflows.retryRevalidation(account, workflows.historyKey(route.owner, route.name))
        } else {
            activities.retryRevalidation(
                account,
                activities.itemsKey(
                    route.owner,
                    route.name,
                    RepositoryActivityKind.WorkflowRuns,
                    workflow.id,
                ),
            )
        }
    }
}

@Composable
internal fun ActionsHistoryPage(
    route: ActionsRoute,
    account: String,
    open: (RepositoryActivityEntry) -> Unit,
) {
    val vm: ActionsHistoryModel =
        koinViewModel(
            key = "actions:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val selection by vm.selected.collectAsState()
    val selected = selection
    val target = selected?.id?.let(vm::target)
    val command = vm.commands.stateFor(target)
    val strings = LocalGithubStrings.current
    val activity = strings.Activity
    var filter by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var refreshAfterSubmit by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var acknowledgeId by remember(vm, target) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(vm, context, activity) {
        vm.commands.results.collect { phase ->
            if (phase == WritePhase.IDLE) refreshAfterSubmit++
            Toast.makeText(
                    context,
                    when (phase) {
                        WritePhase.IDLE -> activity.RunSubmitted
                        WritePhase.UNCERTAIN -> activity.RunUncertain
                        else -> strings.Common.MutationNotSubmitted
                    },
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }
    LaunchedEffect(filter, retry) {
        if (filter && vm.catalog == null) {
            failed = false
            try {
                vm.catalog = vm.workflows.catalog(account, route.owner, route.name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    GithubLoadFailureToast(failed.takeIf { it && filter }, activity.LoadFailed)
    GithubActivityListPage(
        title = strings.Repository.ActionsTitle,
        source = vm.runs,
        subtitle = when (command?.phase) {
            WritePhase.PENDING -> "${selected?.name} · ${strings.Common.MutationPending}"
            WritePhase.UNCERTAIN -> "${selected?.name} · ${strings.Common.MutationUncertain}"
            else -> selected?.name
        },
        revalidation = vm.revalidation,
        onRetryRevalidation = vm::retryRevalidation,
        contentKey = "$route:${selected?.id}",
        canOpen = { it.targetId != null },
        onOpen = open,
        row = { entry, click -> WorkflowRow(entry, click) },
        toolbar = { refresh ->
            LaunchedEffect(refreshAfterSubmit) { if (refreshAfterSubmit > 0) refresh() }
            GithubToolbarActions(
                toolbarIcon(Lucide.ListFilter, activity.WorkflowFilter) { filter = true },
                if ((selected?.active == true && vm.catalog?.canWrite == true) || command?.phase == WritePhase.UNCERTAIN)
                    toolbarIcon(Lucide.Play, activity.RunWorkflow, enabled = command?.phase != WritePhase.PENDING) {
                        if (command?.phase == WritePhase.UNCERTAIN) acknowledgeId = command.submissionId
                        else form = true
                    }
                else toolbarIcon(Lucide.RefreshCw, strings.Repository.Refresh, onClick = refresh),
            )
        },
    )
    if (filter) {
        var search by remember { mutableStateOf("") }
        val options =
            remember(vm.catalog, search) {
                vm.catalog?.options.orEmpty().filter {
                    it.name.contains(search.trim(), ignoreCase = true)
                }
            }
        val showAll = activity.AllRuns.contains(search.trim(), ignoreCase = true)
        GithubSearchSelectionSheet(
            visible = true,
            title = activity.WorkflowFilter,
            placeholder = strings.Common.Search,
            queryKey = search,
            onQueryChange = { search = it },
            onDismiss = { filter = false },
            onConfirm = { filter = false },
            showConfirm = false,
        ) { finish ->
            item {
                GlasmioGroupedList {
                    if (showAll)
                        FilterRow(activity.AllRuns, selected == null) {
                            finish {
                                vm.selected.value = null
                                filter = false
                            }
                        }
                    GithubLoadingContent(
                        loading = vm.catalog == null && !failed,
                        placeholder = { TitleRowPlaceholder(showTopSeparator = showAll) },
                    ) {
                        Column {
                            options.forEach { option ->
                                key(option.id) {
                                    FilterRow(option.name, selected?.id == option.id) {
                                        finish {
                                            vm.selected.value = option
                                            filter = false
                                        }
                                    }
                                }
                            }
                            when {
                                failed ->
                                    GlasmioListRow(
                                        title = strings.Common.Retry,
                                        onClick = { retry++ },
                                    )
                                !showAll && options.isEmpty() ->
                                    GlasmioListRow(title = activity.NoWorkflows)
                            }
                        }
                    }
                }
            }
        }
    }
    if (form && selected != null) {
        WorkflowDispatchSheet(
            vm.workflows,
            account,
            route,
            selected,
            vm.catalog?.branch.orEmpty(),
            onClose = { form = false },
            onDispatch = { branch, inputs ->
                if (form && vm.dispatch(selected.id, branch, inputs)) form = false
            },
        )
    }
    val acknowledging = acknowledgeId
    if (acknowledging != null && target != null && command?.phase == WritePhase.UNCERTAIN && command.submissionId == acknowledging) {
        GlasmioAlertDialog(
            visible = true,
            title = strings.Common.MutationUncertain,
            message = strings.Common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        strings.Common.Cancel,
                        onClick = { acknowledgeId = null },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        strings.Common.MutationAcknowledged,
                        onClick = {
                            acknowledgeId = null
                            vm.commands.acknowledge(target, acknowledging)
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeId = null },
        )
    }
}

@Composable
internal fun FilterRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    select: () -> Unit,
) {
    GlasmioListRow(
        title = label,
        modifier = Modifier.semantics { selected = checked },
        enabled = enabled,
        onClick = select,
        showDisclosure = false,
        trailing = { if (checked) GithubIcon(Lucide.Check, contentDescription = null) },
    )
}
