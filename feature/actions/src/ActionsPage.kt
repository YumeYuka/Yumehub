package moe.yumeyuka.yumehub.feature.actions

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Download
import io.github.yumeyucca.lucide.ExternalLink
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Package
import io.github.yumeyucca.lucide.RefreshCw
import io.github.yumeyucca.lucide.RotateCcw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.cache.CacheTag
import moe.yumeyuka.yumehub.data.cache.CommandAccepted
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.cache.MutationKey
import moe.yumeyuka.yumehub.data.cache.MutationKind
import moe.yumeyuka.yumehub.data.cache.WritePhase
import moe.yumeyuka.yumehub.data.download.DownloadStore
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ReleaseAssetPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.*
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class ActionsRoute(
    val owner: String,
    val name: String,
    val run: Long? = null,
    val suite: String? = null,
    val workflowName: String? = null,
    val runNumber: Int? = null,
) : GithubRoute

internal class ActionsDetailModel(
    private val repo: WorkflowRepository,
    private val route: ActionsRoute,
    private val account: String,
) : ViewModel() {
    var detail by mutableStateOf<WorkflowJobs?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var failed by mutableStateOf(false)
        private set

    private var request: kotlinx.coroutines.Job? = null
    private var lastAppend = false
    val appending
        get() = loading && lastAppend

    var artifacts by mutableStateOf<WorkflowArtifacts?>(null)
        private set

    var artifactPageLoading by mutableStateOf<Int?>(null)
        private set

    var artifactsFailed by mutableStateOf(false)
        private set

    var rerunning by mutableStateOf(false)
        private set

    var rerunUncertain by mutableStateOf(false)
        private set

    private var rerunUncertainOpId: String? = null
    private val rerunResultsChannel = Channel<WritePhase>(Channel.CONFLATED)
    val rerunResults = rerunResultsChannel.receiveAsFlow()

    private var artifactRequest: kotlinx.coroutines.Job? = null
    private var artifactObserver: kotlinx.coroutines.Job? = null

    private val runId
        get() = route.run ?: detail?.url?.substringAfterLast('/')?.toLongOrNull()

    init {
        load()
    }

    fun retry() = load(lastAppend)

    fun loadMoreArtifacts() {
        val run = runId ?: return
        val page = artifacts?.next ?: return
        if (artifactPageLoading == null) loadArtifacts(run, page)
    }

    private fun observeArtifacts(run: Long) {
        artifactObserver?.cancel()
        artifactObserver =
            viewModelScope.launch {
                repo
                    .observeFirstArtifactPage(account, route.owner, route.name, run)
                    .collect { state ->
                        state.data?.let { artifacts = it }
                        artifactPageLoading = if (state.loading && state.data == null) 1 else null
                        artifactsFailed = state.error != null
                    }
            }
    }

    private fun loadArtifacts(run: Long, page: Int) {
        artifactRequest?.cancel()
        artifactPageLoading = page
        artifactsFailed = false
        artifactRequest = viewModelScope.launch {
            try {
                val result = repo.artifacts(account, route.owner, route.name, run, page)
                artifacts =
                    result.copy(
                        files = (artifacts?.files.orEmpty() + result.files).distinctBy { it.id },
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                artifactsFailed = true
            }
            artifactPageLoading = null
        }
    }

    fun load(append: Boolean = false) {
        if (append && (loading || detail?.next == null)) return
        request?.cancel()
        val previous = detail
        val knownRun = runId
        if (!append && knownRun != null) observeArtifacts(knownRun)
        lastAppend = append
        loading = true
        failed = false
        request = viewModelScope.launch {
            try {
                val result =
                    repo.runJobs(
                        account,
                        route.owner,
                        route.name,
                        route.run,
                        previous?.suite ?: route.suite,
                        if (append) previous?.next else null,
                    )
                detail =
                    if (append)
                        result.copy(jobs = (previous!!.jobs + result.jobs).distinctBy { it.id })
                    else result
                if (knownRun == null) runId?.let { observeArtifacts(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
            loading = false
        }
    }

    fun rerunFailed(suiteId: String, debug: Boolean) {
        if (rerunning || rerunUncertain) return
        rerunning = true
        val key = MutationKey(MutationKind.CHECK_RERUN, suiteId, CommandAccepted.serializer())
        viewModelScope.launch {
            val coord = repo.mutations(account)
            val receipt =
                coord.submitCommand(
                    key = key,
                    tags = setOf(CacheTag(DataDomain.Work, null)),
                    send = {
                        repo.rerunFailed(account, suiteId, debug)
                        CommandAccepted
                    },
                    readBack = { null },
                )
            val cmd = receipt?.await() ?: coord.currentCommand(key)
            rerunning = false
            rerunUncertain = cmd.phase == WritePhase.UNCERTAIN
            rerunUncertainOpId = cmd.submissionId.takeIf { rerunUncertain }
            rerunResultsChannel.trySend(if (receipt == null && !rerunUncertain) WritePhase.REJECTED else cmd.phase)
        }
    }

    fun acknowledgeRerun(suiteId: String) {
        val opId = rerunUncertainOpId
        viewModelScope.launch {
            val coord = repo.mutations(account)
            val id =
                opId
                    ?: coord.pending.value
                        .firstOrNull { it.kind == MutationKind.CHECK_RERUN && it.target == suiteId }
                        ?.operationId
            if (id == null) return@launch
            coord.acknowledge(id).join()
            if (coord.pending.value.any { it.operationId == id }) return@launch
            rerunUncertain = false
            rerunUncertainOpId = null
        }
    }
}

val actionsFeatureModule = module {
    viewModel { (route: ActionsRoute, account: String) ->
        ActionsHistoryModel(get(), get(), route, account)
    }
    viewModel { (route: ActionsRoute, account: String) ->
        ActionsDetailModel(get(), route, account)
    }
}

@Composable
fun ActionsPage(
    route: ActionsRoute,
    account: String,
    open: (ActionsRoute) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val repo: WorkflowRepository = koinInject()
    if (route.run == null && route.suite == null) {
        ActionsHistoryPage(route, account) { row ->
            open(
                route.copy(
                    run = row.url?.substringAfterLast('/')?.toLongOrNull(),
                    suite = row.targetId,
                    workflowName = row.workflowName,
                    runNumber = row.number,
                ),
            )
        }
        return
    }
    val vm: ActionsDetailModel =
        koinViewModel(key = "$account:$route", parameters = { parametersOf(route, account) })
    val detail = vm.detail
    val downloads: DownloadStore = koinInject()
    val host = requireNotNull(LocalGithubPageHost.current)
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(vm, context, strings) {
        vm.rerunResults.collect { phase ->
            if (phase == WritePhase.IDLE) confirm = false
            if (phase != WritePhase.REJECTED) vm.load()
            val message = when (phase) {
                WritePhase.IDLE -> strings.Activity.RerunSubmitted
                WritePhase.UNCERTAIN -> strings.Activity.RunUncertain
                else -> strings.Common.MutationNotSubmitted
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val browser = rememberExternalUriHandler()
    val url =
        detail?.url
            ?: route.run?.let { "https://github.com/${route.owner}/${route.name}/actions/runs/$it" }
    val files = vm.artifacts?.files.orEmpty()
    val waitingForRun = route.run == null && detail == null && vm.loading
    val artifactPage = vm.artifactPageLoading
    val showArtifactPlaceholder =
        waitingForRun || (artifactPage != null && (vm.artifacts == null || artifactPage > 1))
    val workflowName = detail?.workflowName ?: route.workflowName
    val runNumber = detail?.runNumber ?: route.runNumber
    val smallTitle =
        if (workflowName != null && runNumber != null) {
            strings.Activity.WorkflowRunTitle(workflowName, runNumber)
        } else null
    GithubLoadFailureToast(vm.failed.takeIf { it }, strings.Activity.LoadFailed)
    GithubPage(
        title = workflowName ?: strings.Repository.ActionsTitle,
        smallTitle = smallTitle,
        titleMaxLines = 3,
        titleSuffix = runNumber?.takeIf { workflowName != null }?.let { "#$it" },
        titleStyle =
            GlasmioTheme.typography.headlineLarge.copy(
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
            ),
        itemSpacing = 12.dp,
        actions = {
            GithubToolbarActions(
                toolbarIcon(Lucide.RefreshCw, strings.Repository.Refresh, onClick = vm::load),
                if (detail?.canRerunFailed == true) toolbarIcon(Lucide.RotateCcw,
                    strings.Activity.RerunFailed, enabled = !vm.rerunning) { confirm = true } else null,
                url?.let {
                    toolbarIcon(Lucide.ExternalLink, strings.Repository.OpenInBrowser) { browser(it) }
                },
            )
        },
    ) {
        if (showArtifactPlaceholder || files.isNotEmpty())
            item(key = "artifacts") {
                GithubSection(strings.Release.Artifacts) {
                    files.forEachIndexed { index, file ->
                        key(file.id) {
                            ArtifactRow(file, index > 0, true) {
                                if (host.navigationEnabled && !file.expired) {
                                    try {
                                        downloads.enqueueArtifact(
                                            account,
                                            route.owner,
                                            route.name,
                                            file,
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        Toast.makeText(
                                                context,
                                                strings.Activity.DownloadFailed,
                                                Toast.LENGTH_LONG,
                                            )
                                            .show()
                                        return@ArtifactRow
                                    }
                                }
                            }
                        }
                    }
                    GithubLoadingContent(
                        loading = showArtifactPlaceholder,
                        placeholder = {
                            ReleaseAssetPlaceholder(showTopSeparator = files.isNotEmpty())
                        },
                    ) {
                        if (vm.artifacts?.next != null) {
                            GlasmioListRow(
                                title = strings.Activity.LoadMoreItems,
                                enabled = artifactPage == null,
                                onClick = vm::loadMoreArtifacts,
                            )
                        }
                    }
                }
            }
        item(key = "jobs") {
            GlasmioGroupedList {
                GithubLoadingContent(
                    loading = vm.loading && detail == null,
                    placeholder = { TitleRowPlaceholder() },
                ) {
                    Column {
                        detail?.jobs?.forEachIndexed { index, job ->
                            key(job.id) { WorkflowRow(job, onClick = null, separator = index > 0) }
                        }
                        GithubLoadingContent(
                            loading = vm.appending,
                            placeholder = { TitleRowPlaceholder(showTopSeparator = true) },
                        ) {
                            when {
                                vm.failed ->
                                    GlasmioListRow(
                                        title = strings.Common.Retry,
                                        onClick = vm::retry,
                                    )
                                detail?.next != null ->
                                    GlasmioListRow(
                                        title = strings.Activity.LoadMoreItems,
                                        enabled = !vm.loading,
                                        onClick = { vm.load(append = true) },
                                    )
                                detail?.jobs.isNullOrEmpty() ->
                                    GlasmioListRow(title = strings.Activity.NoJobs)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm && detail?.canRerunFailed == true) {
        WorkflowRerunSheet(
            busy = vm.rerunning,
            uncertain = vm.rerunUncertain,
            onClose = { confirm = false },
            onRerun = { debug -> vm.rerunFailed(detail.suite, debug) },
            onAcknowledgeUncertain = { vm.acknowledgeRerun(detail.suite) },
        )
    }
}

@Composable
private fun ArtifactRow(
    file: WorkflowArtifact,
    separator: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    GithubDownloadFileRow(
        name = file.name,
        bytes = file.bytes,
        digest = file.digest,
        separator = separator,
        enabled = enabled,
        actionLabel = strings.Release.Download,
        actionIcon = Lucide.Download,
        onAction = onDownload,
        onClick = if (file.expired) null else onDownload,
        leadingIcon = Lucide.Package,
        detail = if (file.expired) strings.Activity.ArtifactExpired else null,
        actionEnabled = !file.expired,
    )
}
