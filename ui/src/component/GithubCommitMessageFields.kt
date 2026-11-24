package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

private val FirstLineOnly = InputTransformation {
    val lineBreak = asCharSequence().indexOfFirst { it == '\n' || it == '\r' }
    if (lineBreak >= 0) replace(lineBreak, length, "")
}

@Composable
fun GithubCommitMessageFields(
    headline: String,
    onHeadlineChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    enabled: Boolean,
) {
    val strings = LocalGithubStrings.current.Workspaces
    CommitTextField(
        value = headline,
        onValueChange = onHeadlineChange,
        placeholder = strings.CommitSummary,
        enabled = enabled,
        singleLine = true,
    )
    GithubMessageBodyField(body, onBodyChange, strings.CommitBody, enabled)
}

@Composable
fun GithubMessageBodyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    CommitTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        minLines = 4,
        maxLines = 8,
    )
}

@Composable
private fun CommitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    val colors = GlasmioTheme.colors
    val field = rememberFormTextFieldState(value, onValueChange)
    GlasmioGroupedList {
        BasicTextField(
            state = field,
            modifier =
                Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 20.dp).semantics {
                    contentDescription = placeholder
                },
            enabled = enabled,
            textStyle = GlasmioTheme.textStyles.body.copy(color = colors.content),
            cursorBrush = SolidColor(colors.accent),
            inputTransformation = if (singleLine) FirstLineOnly else null,
            lineLimits =
                if (singleLine) TextFieldLineLimits.SingleLine
                else TextFieldLineLimits.MultiLine(minLines, maxLines),
            decorator = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment =
                        if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (field.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = GlasmioTheme.textStyles.body,
                            color = colors.tertiaryContent,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
