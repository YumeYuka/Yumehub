package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.yumeyucca.lucide.*
import kotlinx.coroutines.CancellationException
import moe.yumeyuka.yumehub.data.repository.WorkflowInput
import moe.yumeyuka.yumehub.data.repository.WorkflowOption
import moe.yumeyuka.yumehub.data.repository.WorkflowRepository
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder

private class DispatchForm {
    var fields by mutableStateOf<List<WorkflowInput>?>(null)
    var unavailable by mutableStateOf(false)
    var failed by mutableStateOf(false)
    val values = mutableStateMapOf<String, String>()
}

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
    val strings = LocalAppStrings.current
    var branch by remember { mutableStateOf(defaultBranch) }
    var pickBranch by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val forms = remember(account, workflow.id) { LinkedHashMap<String, DispatchForm>(8, 0.75f, true) }
    val form = remember(forms, branch) {
        forms.getOrPut(branch) { DispatchForm() }.also {
            if (forms.size > 8) forms.remove(forms.keys.first())
        }
    }
    val fields = form.fields
    val values = form.values
    val unavailable = form.unavailable
    val failed = form.failed
    LaunchedEffect(account, workflow.id, branch, retry) {
        if (branch.isBlank() || form.fields != null || form.unavailable) return@LaunchedEffect
        form.failed = false
        try {
            val inputs = repo.inputs(account, workflow.id, branch)
            form.unavailable = inputs == null
            inputs?.forEach { field ->
                val initial = field.default ?: if (field.type == "BOOLEAN") "false" else null
                if (initial != null) values[field.key] = initial
            }
            form.fields = inputs
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            form.failed = true
        }
    }
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
    AppBottomSheet(
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
                        AppIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = ready,
                        onClick = {
                            if (ready) onDispatch(branch, values.filterValues { it.isNotEmpty() })
                        },
                    ) {
                        AppIcon(Lucide.Play, contentDescription = strings.Activity.RunWorkflow)
                    }
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ListSection(strings.Activity.RunBranch) {
                GlasmioListRow(
                    title = branch.ifBlank { strings.Workspaces.Branches },
                    onClick = { pickBranch = true },
                )
            }
            LoadingContent(
                loading = !failed && !unavailable && fields == null && branch.isNotBlank(),
                placeholder = { ListRowPlaceholder() },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        failed ->
                            GlasmioListRow(title = strings.Activity.LoadFailed, detail = strings.Common.Retry, onClick = { retry++ })
                        unavailable -> GlasmioListRow(title = strings.Activity.ManualUnavailable)
                    }
                    fields.orEmpty().forEach { field ->
                        ListSection(field.key) {
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
