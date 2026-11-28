package moe.yumeyuka.yumehub.feature.repositoryactivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.api.dto.DiscussionCategoryDto
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.FilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.MutationAcknowledgementDialog

@Composable
internal fun DiscussionCreationSheet(
    visible: Boolean,
    creationState: DiscussionCreationState,
    categories: List<DiscussionCategoryDto>,
    optionsFailed: Boolean,
    onRetry: () -> Unit,
    onTitleChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    body: String,
    onEditBody: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (categoryId: String, title: String, body: String) -> Unit,
    onAcknowledgeUncertain: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val work = strings.WorkList
    val selectedId = creationState.categoryId
    val category = if (selectedId == null) categories.firstOrNull() else categories.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    val title = remember(visible, creationState.draftRestored, creationState.formRevision) { TextFieldState(creationState.title) }
    var acknowledgeVisible by remember { mutableStateOf(false) }
    val submitting = creationState.submitting
    val uncertain = creationState.uncertain
    val failed = creationState.failed
    LaunchedEffect(title, visible, submitting, uncertain) {
        if (visible && creationState.draftRestored && !submitting && !uncertain) {
            androidx.compose.runtime.snapshotFlow { title.text.toString() }.collect(onTitleChange)
        }
    }
    AppBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        fitContent = true,
        title = strings.Repository.NewDiscussion,
        topBar = {
            GlasmioTopBar(
                title = strings.Repository.NewDiscussion,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss) {
                        AppIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = creationState.draftRestored && !submitting && !uncertain && title.text.isNotBlank() && category != null,
                        onClick = {
                            val selected = category ?: return@GlasmioIconButton
                            onSubmit(selected.id, title.text.trim().toString(), body)
                        },
                    ) {
                        AppIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
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
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.Category,
                    detail = if (optionsFailed) strings.Common.Retry else category?.name,
                    systemName = null,
                    enabled = creationState.draftRestored && !submitting && !uncertain && (categories.isNotEmpty() || optionsFailed),
                    onClick = { if (optionsFailed) onRetry() else expanded = !expanded },
                )
                if (expanded) {
                    categories.forEach { option ->
                        FilterChoiceRow(
                            title = option.name,
                            checked = option.id == category?.id,
                            enabled = !submitting && !uncertain,
                        ) {
                            onCategoryChange(option.id)
                            expanded = false
                        }
                    }
                }
            }
            DiscussionCreationTitle(work.CreationTitle, title, !creationState.draftRestored || submitting || uncertain)
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.CreationBody,
                    subtitle = body.lineSequence().firstOrNull { it.isNotBlank() },
                    enabled = creationState.draftRestored && !submitting && !uncertain,
                    onClick = onEditBody,
                )
            }
            if (submitting) {
                GlasmioGroupedList {
                    GlasmioListRow(title = strings.Common.MutationPending, systemName = null)
                }
            } else if (uncertain) {
                GlasmioGroupedList {
                    GlasmioListRow(title = strings.Common.MutationUncertain, systemName = null)
                    GlasmioListRow(
                        title = strings.Common.MutationAcknowledged,
                        showTopSeparator = true,
                        showDisclosure = false,
                        onClick = { acknowledgeVisible = true },
                    )
                }
            } else if (failed) {
                GlasmioGroupedList {
                    GlasmioListRow(title = work.CreationFailed, systemName = null)
                }
            }
        }
    }
    if (acknowledgeVisible && uncertain) {
        MutationAcknowledgementDialog(
            onDismiss = { acknowledgeVisible = false },
            onAcknowledge = {
                acknowledgeVisible = false
                onAcknowledgeUncertain()
            },
        )
    }
}

@Composable
private fun DiscussionCreationTitle(
    label: String,
    state: TextFieldState,
    busy: Boolean,
) {
    val colors = GlasmioTheme.colors
    GlasmioGroupedList {
        BasicTextField(
            state = state,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 24.dp)
                    .semantics { contentDescription = label },
            enabled = !busy,
            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            lineLimits = TextFieldLineLimits.SingleLine,
            decorator = { inner ->
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(
                            label,
                            style = GlasmioTheme.textStyles.body,
                            color = colors.tertiaryContent,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
