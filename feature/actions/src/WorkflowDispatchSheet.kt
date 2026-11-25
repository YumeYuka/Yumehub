package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.repository.WorkflowInput
import moe.yumeyuka.yumehub.data.repository.WorkflowOption
import moe.yumeyuka.yumehub.data.repository.WorkflowRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder

@Composable
internal fun WorkflowDispatchSheet(
    repo: WorkflowRepository,
    account: String,
    route: ActionsRoute,
    workflow: WorkflowOption,
    defaultBranch: String,
    onClose: () -> Unit,
    onDispatch: (branch: String, inputs: Map<String, String>) -> Unit,
) {
    val strings = LocalGithubStrings.current
    var branch by remember { mutableStateOf(defaultBranch) }
    var pickBranch by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var fields by remember(branch, retry) { mutableStateOf<List<WorkflowInput>?>(null) }
    var unavailable by remember(branch, retry) { mutableStateOf(false) }
    var failed by remember(branch, retry) { mutableStateOf(false) }
    val values = remember(branch, retry) { mutableStateMapOf<String, String>() }
    LaunchedEffect(account, workflow.id, branch, retry) {
        if (branch.isBlank()) return@LaunchedEffect
        delay(300)
        try {
            val form = repo.inputs(account, workflow.id, branch)
            unavailable = form == null
            form?.forEach { field ->
                val initial = field.default ?: if (field.type == "BOOLEAN") "false" else null
                if (initial != null) values[field.key] = initial
            }
            fields = form
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    GithubLoadFailureToast(failed.takeIf { it }, strings.Activity.LoadFailed)
    val ready =
        fields?.all { field ->
            val v = values[field.key]
            when {
                v.isNullOrBlank() -> !field.required
                field.choices.isNotEmpty() -> v in field.choices
                field.type == "NUMBER" -> v.toBigDecimalOrNull() != null
                else -> true
            }
        } == true
    if (pickBranch) {
        RunBranchPicker(account, route, branch, onDismiss = { pickBranch = false }) {
            branch = it.removePrefix("refs/heads/")
            pickBranch = false
        }
        return
    }
    GlasmioBottomSheet(
        visible = true,
        onDismissRequest = onClose,
        title = strings.Activity.RunWorkflow,
        fitContent = true,
        topBar = {
            GlasmioTopBar(
                title = strings.Activity.RunWorkflow,
                subtitle = workflow.name,
                navigation = {
                    GlasmioIconButton(onClick = onClose) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = ready,
                        onClick = {
                            if (ready) onDispatch(branch, values.filterValues { it.isNotEmpty() })
                        },
                    ) {
                        GithubIcon(Lucide.Play, contentDescription = strings.Activity.RunWorkflow)
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
            GithubSection(strings.Activity.RunBranch) {
                GlasmioListRow(
                    title = branch.ifBlank { strings.Workspaces.Branches },
                    onClick = { pickBranch = true },
                )
            }
            GithubLoadingContent(
                loading = !failed && !unavailable && fields == null && branch.isNotBlank(),
                placeholder = { ListRowPlaceholder() },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        failed ->
                            GlasmioListRow(title = strings.Common.Retry, onClick = { retry++ })
                        unavailable -> GlasmioListRow(title = strings.Activity.ManualUnavailable)
                    }
                    fields.orEmpty().forEach { field ->
                        GithubSection(field.key) {
                            field.description
                                ?.takeIf { it.isNotBlank() }
                                ?.let { GlasmioListRow(title = it) }
                            when {
                                field.choices.isNotEmpty() ->
                                    field.choices.forEach { option ->
                                        FilterRow(option, values[field.key] == option) {
                                            values[field.key] = option
                                        }
                                    }
                                field.type == "BOOLEAN" ->
                                    GlasmioListRow(
                                        title = field.key,
                                        trailing = {
                                            GlasmioSwitch(
                                                checked = values[field.key] == "true",
                                                onCheckedChange = {
                                                    values[field.key] = it.toString()
                                                },
                                            )
                                        },
                                    )
                                else ->
                                    GlasmioTextField(
                                        value = values[field.key].orEmpty(),
                                        onValueChange = { values[field.key] = it },
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}
