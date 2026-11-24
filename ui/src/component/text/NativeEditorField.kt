package moe.yumeyuka.yumehub.ui.component.text

import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.filterNotNull

/** Keep IME composition in Foundation when the Markdown owner echoes an edit back. */
private class EditorFieldBinding(initial: TextFieldValue) {
    val state = TextFieldState(initial.text, initial.selection)
    private var external = initial
    private var published = initial

    fun accept(value: TextFieldValue) {
        if (value == external) return
        external = value
        if (value == published) return
        published = value
        val replaceText = !state.text.contentEquals(value.text)
        if (replaceText || state.selection != value.selection) {
            state.edit {
                if (replaceText) replace(0, length, value.text)
                selection = value.selection
            }
        }
    }

    suspend fun observe(onValueChange: (TextFieldValue) -> Unit) {
        snapshotFlow { TextFieldValue(state.text.toString(), state.selection, state.composition) }
            .collect { native ->
                val next = if (native.text == published.text) {
                    published.copy(selection = native.selection, composition = native.composition)
                } else native
                if (next != published) {
                    published = next
                    onValueChange(next)
                }
            }
    }
}

@Composable
internal fun NativeEditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle,
    keyboardOptions: KeyboardOptions,
    onKeyboardAction: KeyboardActionHandler?,
    lineLimits: TextFieldLineLimits,
    password: Boolean,
    formatter: EditorOutputFormatter?,
    onTextLayout: (TextLayoutResult, EditorTextPresentation?) -> Unit,
    cursorBrush: Brush,
    inputTransformation: InputTransformation? = null,
    decorator: TextFieldDecorator,
) {
    val binding = remember { EditorFieldBinding(value) }
    val notifyValue by rememberUpdatedState(onValueChange)
    SideEffect { binding.accept(value) }
    LaunchedEffect(binding) { binding.observe { notifyValue(it) } }

    // Foundation supplies a snapshot-aware getter, not a layout result per callback.
    var layout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val receiveLayout: Density.(() -> TextLayoutResult?) -> Unit = remember { { layout = it } }
    val notifyLayout by rememberUpdatedState(onTextLayout)
    LaunchedEffect(binding, formatter) {
        snapshotFlow {
            // A new source may format to the same display string with a different offset map.
            if (formatter != null) binding.state.text
            layout?.invoke()?.let { it to formatter?.presentation }
        }.filterNotNull().collect { (result, presentation) -> notifyLayout(result, presentation) }
    }
    if (password) {
        BasicSecureTextField(
            state = binding.state,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            onTextLayout = receiveLayout,
            cursorBrush = cursorBrush,
            decorator = decorator,
            textObfuscationMode = TextObfuscationMode.Hidden,
            textObfuscationCharacter = '\u2022',
        )
    } else {
        val annotated = value.annotatedString
        val styles = remember(annotated, formatter) {
            if (annotated.spanStyles.isEmpty() && annotated.paragraphStyles.isEmpty()) formatter
            else OutputTransformation {
                if (annotated.text.contentEquals(asCharSequence())) {
                    annotated.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
                    annotated.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
                }
                formatter?.let { with(it) { transformOutput() } }
            }
        }
        BasicTextField(
            state = binding.state,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            onTextLayout = receiveLayout,
            cursorBrush = cursorBrush,
            outputTransformation = styles,
            decorator = decorator,
        )
    }
}
