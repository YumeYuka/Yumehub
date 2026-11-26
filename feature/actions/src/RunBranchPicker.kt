package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubChoiceSheet
import org.koin.compose.koinInject

private class BranchChoices {
    var choices by mutableStateOf(emptyList<String>())
    var cursor: String? = null
    var more by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var request by mutableIntStateOf(0)

    fun load() {
        if (loading) return
        loading = true
        failed = false
        request++
    }
}

@Composable
internal fun RunBranchPicker(
    account: String,
    route: ActionsRoute,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val repo: WorkspacesRepository = koinInject()
    val strings = LocalGithubStrings.current
    var query by remember { mutableStateOf("") }
    val queries = remember(account, route) { LinkedHashMap<String, BranchChoices>(8, 0.75f, true) }
    val pane = remember(queries, query) {
        queries.getOrPut(query) { BranchChoices() }.also {
            if (queries.size > 8) queries.remove(queries.keys.first())
        }
    }
    LaunchedEffect(pane, pane.request) {
        if (!pane.loading) return@LaunchedEffect
        if (query.isNotEmpty() && pane.cursor == null && pane.request == 0) delay(250)
        try {
            val page = repo.loadBranches(account, route.owner, route.name, pane.cursor, query)
            pane.choices = (pane.choices + page.branches).distinct()
            pane.more = page.hasNextPage
            pane.cursor = page.endCursor
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            if (error.invalidatesCachedContent()) {
                queries.clear()
                pane.choices = emptyList()
                pane.cursor = null
                pane.more = false
            }
            pane.failed = true
        }
        pane.loading = false
    }
    GithubChoiceSheet(
        true,
        strings.Workspaces.Branches,
        strings.Workspaces.BranchSearchPlaceholder,
        pane.choices,
        query,
        "refs/heads/$selected",
        pane.loading && pane.choices.isEmpty(),
        pane.more,
        if (pane.failed) strings.Workspaces.BranchesLoadFailed else null,
        if (query.isEmpty()) strings.Workspaces.NoBranches
        else strings.Workspaces.NoMatchingBranches,
        onDismiss,
        pane::load,
        { if (pane.more) pane.load() },
        { query = it },
        onSelect,
        label = { it.removePrefix("refs/heads/") },
    )
}
