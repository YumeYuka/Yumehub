package moe.yumeyuka.yumehub.feature.actions

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.yumeyuka.yumehub.data.repository.WorkspacesRepository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubChoiceSheet
import org.koin.compose.koinInject

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
    var choices by remember(query) { mutableStateOf(emptyList<String>()) }
    var cursor by remember(query) { mutableStateOf<String?>(null) }
    var more by remember(query) { mutableStateOf(false) }
    var failed by remember(query) { mutableStateOf(false) }
    var loading by remember(query) { mutableStateOf(true) }
    var request by remember(query) { mutableIntStateOf(0) }
    LaunchedEffect(account, route, query, request) {
        failed = false
        loading = true
        if (query.isNotEmpty() && cursor == null) delay(250)
        try {
            val page = repo.loadBranches(account, route.owner, route.name, cursor, query)
            choices = (choices + page.branches).distinct()
            more = page.hasNextPage
            cursor = page.endCursor
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    GithubChoiceSheet(
        true,
        strings.Workspaces.Branches,
        strings.Workspaces.BranchSearchPlaceholder,
        choices,
        query,
        "refs/heads/$selected",
        loading && choices.isEmpty(),
        more,
        if (failed) strings.Workspaces.BranchesLoadFailed else null,
        if (query.isEmpty()) strings.Workspaces.NoBranches
        else strings.Workspaces.NoMatchingBranches,
        onDismiss,
        { request++ },
        { if (!loading && more) request++ },
        { query = it },
        onSelect,
        label = { it.removePrefix("refs/heads/") },
    )
}
