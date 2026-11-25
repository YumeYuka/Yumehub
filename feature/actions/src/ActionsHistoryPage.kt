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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
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
    var submitting by mutableStateOf(false)
        private set
    var uncertain by mutableStateOf(false)
        private set
    private var uncertainOperationId: String? = null
    private var uncertainTarget: String? = null
    private val observedDispatchKeys = mutableSetOf<String>()

    private val dispatchResults = Channel<Boolean>(Channel.CONFLATED)
    val results = dispatchResults.receiveAsFlow()

    fun dispatch(workflowId: String, branch: String, inputs: Map<String, String>): Boolean {
        if (submitting || uncertain) return false
        submitting = true
        val target = "${route.owner.lowercase(Locale.ROOT)}/${route.name.lowercase(Locale.ROOT)}:$workflowId"
        val key = MutationKey(MutationKind.WORKFLOW_DISPATCH, target, CommandAccepted.serializer())
        viewModelScope.launch {
            val coord = workflows.mutations(account)
            if (observedDispatchKeys.add(target)) {
                launch {
                    coord.observeCommand(key).collect { cmd ->
                        when (cmd.phase) {
                            WritePhase.PENDING -> submitting = true
                            WritePhase.IDLE -> {
                                submitting = false
                                if (cmd.result != null) {
                                    uncertain = false
                                    uncertainOperationId = null
                                    uncertainTarget = null
                                    dispatchResults.trySend(true)
                                }
                            }
                            WritePhase.REJECTED -> {
                                submitting = false
                                uncertain = false
                                dispatchResults.trySend(false)
                            }
                            WritePhase.UNCERTAIN -> {
                                submitting = false
                                uncertain = true
                                uncertainTarget = target
                                uncertainOperationId =
                                    coord.pending.value
                                        .firstOrNull {
                                            it.kind == key.kind && it.target == key.target
                                        }
                                        ?.operationId
                                dispatchResults.trySend(false)
                            }
                        }
                    }
                }
            }
            val accepted =
                coord.submitCommand(
                    key = key,
                    tags = setOf(CacheTag(DataDomain.Work, null)),
                    send = {
                        workflows.dispatch(account, workflowId, branch, inputs)
                        CommandAccepted
                    },
                    readBack = { null },
                )
            if (!accepted) {
                submitting = false
                uncertain = true
                uncertainTarget = target
                uncertainOperationId =
                    coord.pending.value
                        .firstOrNull { it.kind == key.kind && it.target == key.target }
                        ?.operationId
                dispatchResults.trySend(false)
            }
        }
        return true
    }

    fun acknowledgeDispatch() {
        val opId = uncertainOperationId
        val target = uncertainTarget
        viewModelScope.launch {
            val coord = workflows.mutations(account)
            val id =
                opId
                    ?: coord.pending.value
                        .firstOrNull {
                            it.kind == MutationKind.WORKFLOW_DISPATCH &&
                                (target == null || it.target == target)
                        }
                        ?.operationId
            if (id != null) coord.acknowledge(id)
            uncertain = false
            uncertainOperationId = null
            uncertainTarget = null
        }
    }

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
    val strings = LocalGithubStrings.current
    val activity = strings.Activity
    var filter by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var refreshAfterSubmit by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var acknowledgeDispatchVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(vm, context, activity) {
        vm.results.collect { confirmed ->
            refreshAfterSubmit++
            Toast.makeText(
                    context,
                    if (confirmed) activity.RunSubmitted else activity.RunUncertain,
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
        subtitle = selected?.name,
        source = vm.runs,
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
                if (selected?.active == true && vm.catalog?.canWrite == true)
                    toolbarIcon(Lucide.Play, activity.RunWorkflow, enabled = !vm.submitting) {
                        if (vm.uncertain) acknowledgeDispatchVisible = true
                        else if (!vm.submitting) form = true
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
    if (acknowledgeDispatchVisible && vm.uncertain) {
        GlasmioAlertDialog(
            visible = true,
            title = strings.Common.MutationUncertain,
            message = strings.Common.MutationAcknowledgeWarning,
            buttons =
                listOf(
                    GlasmioAlertButtonSpec(
                        strings.Common.Cancel,
                        onClick = { acknowledgeDispatchVisible = false },
                        role = GlasmioAlertButtonRole.Cancel,
                    ),
                    GlasmioAlertButtonSpec(
                        strings.Common.MutationAcknowledged,
                        onClick = {
                            acknowledgeDispatchVisible = false
                            vm.acknowledgeDispatch()
                        },
                        role = GlasmioAlertButtonRole.Default,
                    ),
                ),
            onDismissRequest = { acknowledgeDispatchVisible = false },
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
