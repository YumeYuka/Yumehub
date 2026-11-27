package moe.yumeyuka.yumehub.ui.component

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
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap

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
    requestClose: Boolean = false,
    loading: Boolean = false,
    loadingPlaceholder: (@Composable () -> Unit)? = null,
    content: LazyListScope.(finishSelection: (() -> Unit) -> Unit) -> Unit,
) {
    val common = LocalGithubStrings.current.Common
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf(TextFieldValue()) }
    var searching by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val canDismiss by rememberUpdatedState(!visible || enabled)
    val sheetState =
        rememberGlasmioSheetState(skipPartiallyExpanded = false) {
            it != GlasmioSheetValue.Hidden || canDismiss
        }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(visible) {
        if (visible) {
            query = TextFieldValue()
            searching = false
        }
    }
    LaunchedEffect(visible, searching) { if (visible && searching) sheetState.expand() }
    LaunchedEffect(visible, queryKey) { if (visible) listState.scrollToItem(0) }
    LaunchedEffect(visible, requestClose, canDismiss) {
        if (visible && requestClose && canDismiss) sheetState.hide()
    }
    GithubBottomSheet(
        visible = visible,
        onDismissRequest = { if (visible && enabled) onDismiss() },
        title = title,
        sheetState = sheetState,
        skipPartiallyExpanded = false,
        dismissOnBackPress = visible && enabled,
        dismissOnClickOutside = visible && enabled,
        topBar = {
            GlasmioTopBar(
                title = title,
                navigation = {
                    GlasmioIconButton(
                        onClick = {
                            focus.clearFocus(force = true)
                            keyboard?.hide()
                            scope.launch { sheetState.hide() }
                        },
                        enabled = visible && enabled && !requestClose,
                    ) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
                actions = {
                    GithubToolbarActions(
                        if (!searching)
                            toolbarIcon(
                                Lucide.Search,
                                common.Search,
                                visible && enabled && !requestClose,
                            ) {
                                searching = true
                            }
                        else null,
                        if (showConfirm)
                            toolbarIcon(
                                Lucide.Check,
                                common.Confirm,
                                visible && enabled && confirmEnabled && !requestClose,
                                onClick = {
                                    focus.clearFocus(force = true)
                                    keyboard?.hide()
                                    onConfirm()
                                },
                            )
                        else null,
                    )
                },
            )
        },
    ) {
        val contentDensity = LocalDensity.current
        // Selection owns its result and visibility; onDismiss is cancellation only.
        val finish: (() -> Unit) -> Unit = { action ->
            if (visible && enabled) {
                focus.clearFocus(force = true)
                keyboard?.hide()
                action()
            }
        }
        Box(
            Modifier.fillMaxSize().padding(12.dp),
        ) {
            val results: @Composable () -> Unit = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = visible && enabled,
                    contentPadding =
                        PaddingValues(
                            bottom =
                                if (searching) with(contentDensity) { toolbarHeight.toDp() + 12.dp }
                                else 0.dp,
                        ),
                ) {
                    content(finish)
                }
            }
            if (loadingPlaceholder != null) {
                SkeletonSwap(
                    loading = loading,
                    modifier = Modifier.fillMaxSize(),
                    entrance = false,
                    placeholder = loadingPlaceholder,
                    content = results,
                )
            } else {
                results()
            }
            if (searching)
                GlasmioSearchToolbar(
                    query = query,
                    onQueryChange = {
                        if (visible && enabled) {
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
                    enabled = visible && enabled,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            .onSizeChanged { toolbarHeight = it.height },
                )
        }
    }
}
