package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange

/** Binds a native editing buffer to a form whose draft is owned by its caller. */
@Composable
fun rememberFormTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val field = rememberTextFieldState(value, TextRange.Zero)
    val model by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(field) {
        var previousModel = model
        var pending: String? = null
        field.replaceFormText(previousModel)
        snapshotFlow { model to field.text.toString() }.collect { (current, draft) ->
            if (current != previousModel) {
                previousModel = current
                val externalEdit = current != pending
                pending = null
                if (externalEdit) {
                    field.replaceFormText(current)
                    return@collect
                }
            }
            // Wait for the owner's echo before sending another edit. A late echo must not
            // replace newer typing or interrupt the IME's composing region.
            if (pending == null && draft != current) {
                pending = draft
                onChange(draft)
            }
        }
    }
    return field
}

/** Replaces a draft from a picker or its owner, retaining both ends of the selection. */
fun TextFieldState.replaceFormText(value: String) {
    if (text.contentEquals(value)) return
    edit {
        val previous = selection
        replace(0, length, value)
        selection = TextRange(previous.start.coerceAtMost(length), previous.end.coerceAtMost(length))
    }
}
