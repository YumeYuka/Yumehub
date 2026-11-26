package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.core.model.CollaborationKind
import moe.yumeyuka.yumehub.core.model.CollaborationListFilter
import moe.yumeyuka.yumehub.core.model.CollaborationScopeFilter
import moe.yumeyuka.yumehub.data.api.dto.CollaborationLabelDto
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.navigation.GithubBackHandler
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost

/** 讨论不属于 Issue/PR 的 CollaborationKind；kind 为空时使用讨论筛选项。 */
@Composable
fun WorkListControls(
    kind: CollaborationKind?,
    filter: CollaborationListFilter,
    scopes: List<CollaborationScopeFilter>,
    onQueryChange: (String) -> Unit,
    onApply: (CollaborationListFilter) -> Unit,
    labels: List<CollaborationLabelDto> = emptyList(),
    milestones: List<String> = emptyList(),
    issueTypes: List<CollaborationLabelDto> = emptyList(),
    discussionCategories: List<String> = emptyList(),
    onShowFilters: (() -> Unit)? = null,
    content: @Composable (actions: @Composable () -> Unit, searching: Boolean) -> Unit,
) {
    val common = LocalGithubStrings.current.Common
    val enabled = requireNotNull(LocalGithubPageHost.current).navigationEnabled
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searching by remember { mutableStateOf(false) }
    var input by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(filter.searchQuery))
        }
    var sheetVisible by remember { mutableStateOf(false) }
    val dismissSearch = {
        searching = false
        focus.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(input.text) {
        delay(200)
        onQueryChange(input.text)
    }
    GithubBackHandler(enabled = searching && !sheetVisible, onBack = dismissSearch)
    GithubPageSearchLayout(
        searching,
        input,
        { input = it },
        onQueryChange,
        dismissSearch,
        common.Search,
    ) {
        content(
            {
                WorkListActions(
                    enabled,
                    onSearch = { searching = true },
                    onShowFilterSheet = {
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                        onShowFilters?.invoke()
                        sheetVisible = true
                    },
                )
            },
            searching,
        )
    }
    WorkListFilterSheet(
        visible = sheetVisible,
        kind = kind,
        filter = filter,
        scopes = scopes,
        labels = labels,
        milestones = milestones,
        issueTypes = issueTypes,
        discussionCategories = discussionCategories,
        enabled = enabled,
        onDismiss = { sheetVisible = false },
        onApply = {
            onApply(it)
            sheetVisible = false
        },
    )
}
