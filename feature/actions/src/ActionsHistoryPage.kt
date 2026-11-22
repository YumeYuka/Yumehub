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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import moe.yumeyuka.yumehub.core.model.RepositoryActivityKind
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal class ActionsHistoryModel(
    val workflows: WorkflowRepository,
    activities: RepositoryActivityRepository,
    route: ActionsRoute,
    account: String,
) : ViewModel() {
    val selected = MutableStateFlow<WorkflowOption?>(null)
    var catalog by mutableStateOf<WorkflowCatalog?>(null)
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
    var submitting by remember { mutableStateOf(false) }
    var refreshAfterSubmit by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
        contentKey = "$route:${selected?.id}",
        locked = submitting,
        canOpen = { it.targetId != null },
        onOpen = open,
        row = { entry, click -> WorkflowRow(entry, click) },
        toolbar = { refresh ->
            LaunchedEffect(refreshAfterSubmit) { if (refreshAfterSubmit > 0) refresh() }
            GithubToolbarActions(
                toolbarIcon(Lucide.ListFilter, activity.WorkflowFilter) { filter = true },
                if (selected?.active == true && vm.catalog?.canWrite == true)
                    toolbarIcon(Lucide.Play, activity.RunWorkflow) { form = true }
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
                            finish { vm.selected.value = null }
                        }
                    GithubLoadingContent(
                        loading = vm.catalog == null && !failed,
                        placeholder = { TitleRowPlaceholder(showTopSeparator = showAll) },
                    ) {
                        Column {
                            options.forEach { option ->
                                key(option.id) {
                                    FilterRow(option.name, selected?.id == option.id) {
                                        finish { vm.selected.value = option }
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
            onBusy = { submitting = it },
            onClose = { form = false },
            onResult = { confirmed ->
                form = false
                refreshAfterSubmit++
                Toast.makeText(
                        context,
                        if (confirmed) activity.RunSubmitted else activity.RunUncertain,
                        Toast.LENGTH_LONG,
                    )
                    .show()
            },
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
