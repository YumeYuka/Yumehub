package moe.yumeyuka.yumehub.feature.auth

import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertFieldGroup
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import moe.yumeyuka.yumehub.data.twofactor.MobileAuthRequestType
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.theme.readableForeground

@Composable
fun TwoFactorOverlay(
    state: TwoFactorUiState,
    onDigitsChange: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notice = if (!state.isVisible) state.receivingError ?: state.notificationWarning else null
    LaunchedEffect(notice) {
        if (notice != null) {
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
        }
    }
    TwoFactorOverlayContent(
        state = state,
        dialog = { prompt, visible ->
            TwoFactorPromptDialog(
                prompt,
                visible,
                onDigitsChange,
                onApprove,
                onReject,
                onDismiss,
                modifier,
            )
        },
    )
}

internal data class TwoFactorPromptContent(
    val requestId: Int?,
    val type: MobileAuthRequestType?,
    val requiresDigits: Boolean,
    val digits: String,
    val error: String?,
    val canSubmit: Boolean,
)

private fun TwoFactorUiState.promptContent() =
    TwoFactorPromptContent(
        requestId = request?.id,
        type = request?.type,
        requiresDigits = requiresDigits,
        digits = digits,
        error = error,
        canSubmit = canSubmit,
    )

@Composable
internal fun TwoFactorOverlayContent(
    state: TwoFactorUiState,
    dialog: @Composable (TwoFactorPromptContent, Boolean) -> Unit,
) {
    val currentPrompt = state.promptContent()
    var lastVisiblePrompt by remember { mutableStateOf(currentPrompt) }
    if (state.isVisible) {
        SideEffect { lastVisiblePrompt = currentPrompt }
    }
    // Glasmio owns the exit animation. Keep its call and its outgoing content intact.
    dialog(if (state.isVisible) currentPrompt else lastVisiblePrompt, state.isVisible)
}

@Composable
private fun TwoFactorPromptDialog(
    prompt: TwoFactorPromptContent,
    visible: Boolean,
    onDigitsChange: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val colors = GlasmioTheme.colors
    val strings = LocalGithubStrings.current
    var fieldValue by remember(prompt.requestId) { mutableStateOf(TextFieldValue(prompt.digits)) }

    GlasmioAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = titleFor(prompt.type),
        message =
            if (prompt.requiresDigits) strings.TwoFactor.DigitsMessage
            else strings.TwoFactor.ConfirmationMessage,
        modifier = modifier,
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    label = strings.TwoFactor.Reject,
                    onClick = onReject,
                    role = GlasmioAlertButtonRole.Cancel,
                ),
                GlasmioAlertButtonSpec(
                    label = strings.TwoFactor.Approve,
                    onClick = onApprove,
                    role = GlasmioAlertButtonRole.Default,
                    enabled = prompt.canSubmit,
                ),
            ),
    ) {
        if (prompt.requiresDigits) {
            CompositionLocalProvider(
                LocalGlassColors provides
                    colors.copy(tertiaryContent = colors.content.copy(alpha = 0.72f)),
            ) {
                GlasmioAlertFieldGroup {
                    GlasmioAlertTextField(
                        value = fieldValue.copy(text = prompt.digits),
                        onValueChange = {
                            if (visible) {
                                fieldValue = it
                                onDigitsChange(it.text)
                            }
                        },
                        placeholder = strings.TwoFactor.DigitsPlaceholder,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = { if (visible && prompt.canSubmit) onApprove() },
                            ),
                    )
                }
            }
        }
        prompt.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = GlasmioTheme.textStyles.footnote,
                color =
                    readableForeground(
                        colors.destructive,
                        colors.elevatedBackground,
                        colors.content,
                    ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun titleFor(type: MobileAuthRequestType?): String {
    val strings = LocalGithubStrings.current.TwoFactor
    return when (type) {
        MobileAuthRequestType.DeviceVerification -> strings.DeviceTitle
        MobileAuthRequestType.TwoFactorPasswordReset -> strings.ResetTitle
        MobileAuthRequestType.TwoFactorSudoChallenge -> strings.SudoTitle
        else -> strings.LoginTitle
    }
}
