package moe.yumeyuka.yumehub.feature.repository

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetValue
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.IconTilePlaceholder

@Composable
internal fun RepositoryForkSheet(
    state: RepositoryForkState?,
    onDismiss: () -> Unit,
    onName: (String) -> Unit,
    onDefaultBranchOnly: (Boolean) -> Unit,
    onCheckName: () -> Unit,
    onCreate: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Repository
    val common = LocalGithubStrings.current.Common
    val context = LocalContext.current
    val colors = GlasmioTheme.colors
    val submitting = state?.submitting == true
    val checking = state?.nameStatus == ForkNameStatus.Checking
    LaunchedEffect(state?.visible, state?.error, state?.nameStatus) {
        if (state?.visible == true) {
            state.error?.let { error ->
                val message =
                    if (state.nameStatus == ForkNameStatus.Failed)
                        strings.ForkNameCheckFailed(error)
                    else error
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(state?.visible, state?.name, state?.nameStatus) {
        if (state?.visible == true && state.nameStatus == ForkNameStatus.Taken) {
            Toast.makeText(context, strings.ForkNameTaken(state.name), Toast.LENGTH_SHORT).show()
        }
    }
    val sheetState =
        rememberGlasmioSheetState(skipPartiallyExpanded = true) { value ->
            value != GlasmioSheetValue.Hidden || !submitting
        }
    GlasmioBottomSheet(
        visible = state?.visible == true,
        onDismissRequest = onDismiss,
        title = strings.ForkRepository,
        fitContent = true,
        sheetState = sheetState,
        dismissOnBackPress = !submitting,
        dismissOnClickOutside = !submitting,
        topBar = {
            GlasmioTopBar(
                title = strings.ForkRepository,
                subtitle = state?.parent?.fullName,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !submitting) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        onClick = {
                            if (state != null) {
                                when (state.nameStatus) {
                                    ForkNameStatus.Available -> onCreate()
                                    ForkNameStatus.Checking -> Unit
                                    ForkNameStatus.Taken ->
                                        Toast.makeText(
                                                context,
                                                strings.ForkNameTaken(state.name),
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    ForkNameStatus.Invalid ->
                                        Toast.makeText(
                                                context,
                                                strings.ForkNameInvalid,
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    ForkNameStatus.Failed -> onCheckName()
                                }
                            }
                        },
                        enabled = state?.visible == true && !submitting && !checking,
                    ) {
                        GithubLoadingContent(
                            loading = checking,
                            modifier = Modifier.size(23.dp),
                            placeholder = { IconTilePlaceholder(23.dp) },
                        ) {
                            GithubIcon(Lucide.Check, contentDescription = strings.ForkCreate)
                        }
                    }
                },
            )
        },
    ) {
        if (state != null) {
            Column(
                Modifier.fillMaxWidth()
                    .background(colors.groupedBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(LocalGlasmioSheetContentPadding.current)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GithubSection(strings.ForkName) {
                    GlasmioTextField(
                        value = state.name,
                        onValueChange = { name ->
                            if (name.length > ForkNameMaxLength) {
                                Toast.makeText(
                                        context,
                                        strings.ForkNameTooLong(ForkNameMaxLength),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            } else {
                                onName(name)
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier.semantics { contentDescription = strings.ForkName },
                    )
                }
                state.parent.description?.takeIf(String::isNotBlank)?.let { description ->
                    GithubSection(strings.Description) {
                        Text(
                            description,
                            style = GlasmioTheme.textStyles.body,
                            color = colors.secondaryContent,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                val branch = state.parent.defaultBranch
                if (!branch.isNullOrBlank()) {
                    GithubSection(strings.ForkSettings) {
                        GlasmioListRow(
                            title = strings.ForkDefaultBranchOnly(branch),
                            systemName = null,
                            showTopSeparator = false,
                            showDisclosure = false,
                            trailing = {
                                GlasmioSwitch(
                                    checked = state.defaultBranchOnly,
                                    onCheckedChange = onDefaultBranchOnly,
                                    enabled = !submitting,
                                    onTintColor = colors.accent,
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription =
                                                strings.ForkDefaultBranchOnly(branch)
                                        },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
