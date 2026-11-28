package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioSearchToolbar
import moe.yumeyuka.yumehub.locale.LocalAppStrings

@Composable
fun PageSearchLayout(
    visible: Boolean,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    autoFocus: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (visible) {
            GlasmioSearchToolbar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onCancel = onClose,
                cancelLabel = LocalAppStrings.current.Common.Close,
                placeholder = placeholder,
                autoFocus = autoFocus,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}

fun LazyListScope.pageSearchClearance(visible: Boolean) {
    if (visible) item(key = "search-spacer") { Spacer(Modifier.height(80.dp)) }
}
