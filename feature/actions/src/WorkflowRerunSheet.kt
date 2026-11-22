package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RotateCcw
import io.github.yumeyucca.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.repository.WorkflowRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon

@Composable
internal fun WorkflowRerunSheet(
    repo: WorkflowRepository,
    account: String,
    suite: String,
    onBusy: (Boolean) -> Unit,
    onClose: () -> Unit,
    onResult: (Boolean) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val activity = strings.Activity
    val scope = rememberCoroutineScope()
    var debug by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val sheet =
        rememberGlasmioSheetState(skipPartiallyExpanded = true) {
            it != GlasmioSheetValue.Hidden || !busy
        }
    GlasmioBottomSheet(
        onDismissRequest = { if (!busy) onClose() },
        sheetState = sheet,
        fitContent = true,
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
        title = activity.RerunFailed,
        topBar = {
            GlasmioTopBar(
                title = activity.RerunFailed,
                navigation = {
                    GlasmioIconButton(enabled = !busy, onClick = onClose) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = !busy,
                        onClick = {
                            if (!busy) {
                                busy = true
                                onBusy(true)
                                scope.launch {
                                    val confirmed =
                                        try {
                                            repo.rerunFailed(account, suite, debug)
                                            true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            false
                                        } finally {
                                            busy = false
                                            onBusy(false)
                                        }
                                    onResult(confirmed)
                                }
                            }
                        },
                    ) {
                        GithubIcon(Lucide.RotateCcw, contentDescription = activity.Rerun)
                    }
                },
            )
        },
    ) {
        GlasmioGroupedList(
            modifier = Modifier.padding(LocalGlasmioSheetContentPadding.current).padding(12.dp),
        ) {
            GlasmioListRow(
                title = activity.DebugLogging,
                trailing = {
                    GlasmioSwitch(
                        checked = debug,
                        enabled = !busy,
                        onCheckedChange = { debug = it },
                    )
                },
            )
        }
    }
}
