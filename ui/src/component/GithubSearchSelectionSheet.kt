package moe.yumeyuka.yumehub.ui.component

import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Search
import io.github.yumeyucca.lucide.X
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.locale.LocalGithubStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GithubSearchSelectionSheet(
    visible: Boolean,
    title: String,
    placeholder: String,
    queryKey: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    confirmEnabled: Boolean = true,
    showConfirm: Boolean = true,
    content: LazyListScope.(finishSelection: (() -> Unit) -> Unit) -> Unit,
) {
    val common = LocalGithubStrings.current.Common
    val sheetState = rememberGlasmioSheetState(skipPartiallyExpanded = false)
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf(TextFieldValue()) }
    var searching by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            query = TextFieldValue()
            searching = false
            finishing = false
        }
    }
    LaunchedEffect(visible, searching) { if (visible && searching) sheetState.expand() }
    LaunchedEffect(visible, queryKey) { if (visible) listState.scrollToItem(0) }
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = { if (enabled || finishing) onDismiss() },
        title = title,
        sheetState = sheetState,
        skipPartiallyExpanded = false,
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(onClick = onDismiss, enabled = enabled && !finishing) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    GithubToolbarActions(
                        if (!searching)
                            toolbarIcon(Lucide.Search, common.Search, enabled) { searching = true }
                        else null,
                        if (showConfirm)
                            toolbarIcon(
                                Lucide.Check,
                                common.Confirm,
                                enabled && confirmEnabled && !finishing,
                                onClick = onConfirm,
                            )
                        else null,
                    )
                },
            )
        },
    ) {
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val view = LocalView.current
        val density = LocalDensity.current
        val ime = WindowInsets.ime
        val source = WindowInsets.imeAnimationSource
        val target = WindowInsets.imeAnimationTarget
        val finish: (() -> Unit) -> Unit = { action ->
            if (enabled && !finishing) {
                finishing = true
                action()
                keyboard?.hide()
                focus.clearFocus(force = true)
                scope.launch {
                    do {
                        withFrameNanos {}
                        val showing =
                            view.rootWindowInsets?.isVisible(AndroidWindowInsets.Type.ime()) == true
                        val animating =
                            ime.getBottom(density) > 0 ||
                                source.getBottom(density) > 0 ||
                                target.getBottom(density) > 0
                    } while (showing || animating)
                    sheetState.hide()
                }
            }
        }
        Box(
            Modifier.fillMaxSize().padding(LocalGlasmioSheetContentPadding.current).padding(12.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = enabled && !finishing,
                contentPadding =
                    PaddingValues(
                        bottom =
                            if (searching) with(density) { toolbarHeight.toDp() + 12.dp } else 0.dp,
                    ),
            ) {
                content(finish)
            }
            if (searching)
                GlasmioSearchToolbar(
                    query = query,
                    onQueryChange = {
                        if (enabled && !finishing) {
                            query = it
                            onQueryChange(it.text)
                        }
                    },
                    onSearch = { focus.clearFocus() },
                    onCancel = {
                        searching = false
                        query = TextFieldValue()
                        onQueryChange("")
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                    },
                    cancelLabel = common.Close,
                    placeholder = placeholder,
                    enabled = enabled && !finishing,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .onSizeChanged { toolbarHeight = it.height },
                )
        }
    }
}
