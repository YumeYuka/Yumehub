package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ListRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberFormTextFieldState

@Composable
internal fun RepositoryManagementSheet(
    state: RepositoryOverviewState,
    onDismiss: () -> Unit,
    onDescription: (String) -> Unit,
    onSubscriptionChange: (String, Set<String>) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Repository
    val common = LocalGithubStrings.current.Common
    val colors = GlasmioTheme.colors
    var displayed by remember { mutableStateOf(state.management) }
    LaunchedEffect(state.management) { state.management?.let { displayed = it } }
    val editor = state.management ?: displayed
    val title =
        when (editor?.sheet) {
            RepositorySheet.Description -> strings.EditDescription
            RepositorySheet.Notifications -> strings.NotificationSettings
            null -> strings.EditDescription
        }
    val canSave =
        !state.busy &&
            editor?.let {
                when (it.sheet) {
                    RepositorySheet.Description -> it.description.trim() != it.originalDescription
                    else -> false
                }
            } == true
    val blocking = state.busy && state.management?.sheet == RepositorySheet.Description
    val sheetState =
        rememberGlasmioSheetState(skipPartiallyExpanded = true) { value ->
            value != GlasmioSheetValue.Hidden || !blocking
        }
    val failure = state.management?.error
    GithubLoadFailureToast(failure, failure.orEmpty())
    GlasmioBottomSheet(
        visible = state.management != null,
        onDismissRequest = onDismiss,
        title = title,
        fitContent = true,
        sheetState = sheetState,
        dismissOnBackPress = !blocking,
        dismissOnClickOutside = !blocking,
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = !blocking) {
                        GithubIcon(Lucide.X, contentDescription = common.Cancel)
                    }
                },
                actions = {
                    GithubToolbarActions(
                        when (editor?.sheet) {
                            RepositorySheet.Description ->
                                toolbarIcon(Lucide.Check, common.Save, canSave, onClick = onSave)
                            RepositorySheet.Notifications ->
                                toolbarIcon(Lucide.Check, common.Confirm, !blocking && !editor.loading, onClick = onSave)
                            else -> null
                        },
                    )
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(colors.groupedBackground)
                .verticalScroll(rememberScrollState())
                .padding(LocalGlasmioSheetContentPadding.current)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (editor?.sheet) {
                RepositorySheet.Description ->
                    GithubSection(strings.Description) {
                        BasicTextField(
                            state = rememberFormTextFieldState(editor.description, onDescription),
                            inputTransformation = InputTransformation {
                                if (length > RepositoryDescriptionMaxLength) revertAllChanges()
                            },
                            modifier =
                                Modifier.fillMaxWidth().padding(16.dp).semantics {
                                    contentDescription = strings.Description
                                },
                            enabled = !state.busy,
                            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
                            cursorBrush = SolidColor(colors.accent),
                            lineLimits = TextFieldLineLimits.MultiLine(4, 8),
                        )
                    }
                RepositorySheet.Notifications ->
                    GithubLoadingContent(
                        loading = editor.loading,
                        placeholder = { ListRowPlaceholder() },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RepositoryNotificationOptions(
                                subscription = editor.subscription?.value,
                                subscriptionTypes = editor.subscription?.types.orEmpty(),
                                discussionsEnabled = state.overview?.discussionsEnabled == true,
                                visible = state.management?.sheet == RepositorySheet.Notifications,
                                onSubscriptionChange = onSubscriptionChange,
                            )
                        }
                    }
                null -> Unit
            }
        }
    }
}
