package moe.yumeyuka.yumehub.feature.repositoryactivity

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.DiscussionCategoryDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubFilterChoiceRow
import moe.yumeyuka.yumehub.ui.component.GithubIcon

@Composable
internal fun DiscussionCreationSheet(
    visible: Boolean,
    categories: List<DiscussionCategoryDto>,
    optionsFailed: Boolean,
    onRetry: () -> Unit,
    body: String,
    onEditBody: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: suspend (categoryId: String, title: String, body: String) -> Int,
    onCreated: (Int) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val work = strings.WorkList
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable(visible) { mutableStateOf<String?>(null) }
    val category = categories.firstOrNull { it.id == selectedId } ?: categories.firstOrNull()
    var expanded by rememberSaveable(visible) { mutableStateOf(false) }
    var title by rememberSaveable(visible) { mutableStateOf("") }
    var submitting by rememberSaveable(visible) { mutableStateOf(false) }
    var failed by rememberSaveable(visible) { mutableStateOf(false) }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        dismissOnBackPress = !submitting,
        dismissOnClickOutside = !submitting,
        fitContent = true,
        title = strings.Repository.NewDiscussion,
        topBar = {
            GlasmioTopBar(
                title = strings.Repository.NewDiscussion,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !submitting) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioIconButton(
                        enabled = !submitting && title.isNotBlank() && category != null,
                        onClick = {
                            val selected = category ?: return@GlasmioIconButton
                            submitting = true
                            failed = false
                            scope.launch {
                                try {
                                    val number = onCreate(selected.id, title.trim(), body)
                                    onDismiss()
                                    onCreated(number)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    failed = true
                                    submitting = false
                                }
                            }
                        },
                    ) {
                        GithubIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
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
            GlasmioGroupedList(Modifier.animateContentSize(tween(420))) {
                GlasmioListRow(
                    title = work.Category,
                    detail = if (optionsFailed) strings.Common.Retry else category?.name,
                    systemName = null,
                    enabled = !submitting && (category != null || optionsFailed),
                    onClick = { if (optionsFailed) onRetry() else expanded = !expanded },
                )
                if (expanded) {
                    categories.forEach { option ->
                        GithubFilterChoiceRow(
                            title = option.name,
                            checked = option.id == category?.id,
                            enabled = !submitting,
                        ) {
                            selectedId = option.id
                            expanded = false
                        }
                    }
                }
            }
            DiscussionCreationTitle(work.CreationTitle, title, { title = it }, submitting)
            GlasmioGroupedList {
                GlasmioListRow(
                    title = work.CreationBody,
                    subtitle = body.lineSequence().firstOrNull { it.isNotBlank() },
                    enabled = !submitting,
                    onClick = onEditBody,
                )
            }
            if (failed) {
                GlasmioGroupedList {
                    GlasmioListRow(title = work.CreationFailed, systemName = null)
                }
            }
        }
    }
}

@Composable
private fun DiscussionCreationTitle(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
) {
    val colors = GlasmioTheme.colors
    GlasmioGroupedList {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 24.dp)
                    .semantics { contentDescription = label },
            enabled = !busy,
            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            singleLine = true,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
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
