package moe.yumeyuka.yumehub.feature.commitdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.AtSign
import io.github.yumeyucca.lucide.Copy
import io.github.yumeyucca.lucide.GitMerge
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.GitPullRequestClosed
import io.github.yumeyucca.lucide.GitPullRequestDraft
import io.github.yumeyucca.lucide.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.GithubStrings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.placeholders.CommitAuthorPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.OrganizationRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.ReadingTextPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.*
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.platform.rememberGithubUriHandler
import moe.yumeyuka.yumehub.ui.theme.GithubWorkColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

data class CommitDetailRoute(val owner: String, val name: String, val oid: String) : GithubRoute

@Composable
fun CommitDetailPage(route: CommitDetailRoute, account: String) {
    val viewModel: CommitDetailViewModel =
        koinViewModel(
            key = "commit:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    GithubRefreshEffect(
        viewModel,
        DataDomain.Content,
        DataDomain.Work,
        refreshOnFirstActivation = false,
        onRefresh = viewModel::load,
    )
    val strings = LocalGithubStrings.current
    val host = requireNotNull(LocalGithubPageHost.current)
    val open = rememberGithubUriHandler()
    val copy = rememberCopyText()
    val parents = viewModel.entries(CommitSection.Parents).collectAsLazyPagingItems()
    val pullRequests = viewModel.entries(CommitSection.PullRequests).collectAsLazyPagingItems()
    GithubPagingContent(
        viewModel.entries(state.section),
        contentKey = state.section,
        order = state.section.ordinal,
    ) { paging ->
        val pages = paging.items
        val information = state.section == CommitSection.Details
        // 已经展示过的页面返回组合后不再看加载位，刷新不能让占位重新盖住正文。
        // key 只取路由内稳定的分节标识，ViewModel 实例与重试次数都不参与。
        val presentationKey = "commit-section-${state.section.name}"
        val settled = LocalGithubPageState.current?.detailPresented(presentationKey) == true
        val firstPagesReady =
            if (information) {
                githubDetailPageSettled(parents, state.detail != null, settled) &&
                    githubDetailPageSettled(pullRequests, state.detail != null, settled)
            } else githubDetailPageSettled(pages, state.detail != null, settled)
        val firstPageFailed =
            !settled &&
                when {
                    information ->
                        parents.loadState.refresh is LoadState.Error ||
                            pullRequests.loadState.refresh is LoadState.Error
                    else -> pages.loadState.refresh is LoadState.Error
                }
        var chromePresentation by
            remember(viewModel) { mutableStateOf<GithubDetailPresentation?>(null) }
        val presentation =
            rememberGithubDetailPresentation(
                key = presentationKey,
                kind =
                    if (information) GithubDetailKind.CommitInformation
                    else GithubDetailKind.CommitFiles,
                dataReady = state.detail != null && (settled || !state.loading) && firstPagesReady,
                failed = state.failed || firstPageFailed,
                chrome = chromePresentation,
            )
        SideEffect {
            if (chromePresentation == null && presentation.interactive)
                chromePresentation = presentation
        }
        var displayedDetail by remember(viewModel) { mutableStateOf(state.detail) }
        val contentReady =
            state.detail != null &&
                (settled || !state.loading) &&
                firstPagesReady &&
                !(state.failed || firstPageFailed)
        SideEffect { if (contentReady) displayedDetail = state.detail }
        val shownDetail = if (contentReady) state.detail else displayedDetail ?: state.detail
        val slots =
            rememberGithubPagingSlots(
                pages,
                "commit:${state.section}",
                paging.initialLoading,
                contentReady = contentReady,
            ) {
                it.id
            }
        val parentSlots =
            rememberGithubPagingSlots(parents, "commit-parents", contentReady = contentReady) {
                it.id
            }
        val pullRequestSlots =
            rememberGithubPagingSlots(
                pullRequests,
                "commit-pull-requests",
                contentReady = contentReady,
            ) {
                it.id
            }
        if (state.failed) GithubLoadFailureToast(Unit, strings.Activity.LoadFailed)
        if (information) {
            val failure =
                listOf(parents.loadState, pullRequests.loadState).firstNotNullOfOrNull {
                    (it.refresh as? LoadState.Error) ?: (it.append as? LoadState.Error)
                }
            GithubLoadFailureToast(failure, strings.Activity.LoadFailed)
        }
        GithubPage(
            title = shownDetail?.title.orEmpty(),
            titleMaxLines = 2,
            titleStyle =
                GlasmioTheme.typography.headlineLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                ),
            smallTitle = state.detail?.oid?.take(7) ?: route.oid.take(7),
            listState = paging.listState,
            detailPresentation = presentation,
            onRetryDetail = {
                viewModel.load()
                pages.retry()
                parents.retry()
                pullRequests.retry()
            },
            detailHeaderPlaceholder = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlasmioSegmentedControl(
                        items =
                            listOf(
                                CommitSection.Files to strings.WorkDetail.Changes,
                                CommitSection.Details to strings.Activity.Details,
                            ),
                        selected = state.section,
                        onSelected = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!information) CommitAuthorPlaceholder()
                }
            },
            itemSpacing = if (information) 12.dp else 8.dp,
            contentHeader = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlasmioSegmentedControl(
                        items =
                            listOf(
                                CommitSection.Files to strings.WorkDetail.Changes,
                                CommitSection.Details to strings.Activity.Details,
                            ),
                        selected = state.section,
                        onSelected = { if (host.navigationEnabled) viewModel.select(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.section == CommitSection.Files && state.detail == null) {
                        CommitAuthorPlaceholder()
                    }
                    shownDetail
                        ?.takeIf { state.section == CommitSection.Files }
                        ?.let { detail ->
                            GlasmioGroupedList {
                                GlasmioListRow(
                                    title = detail.author ?: strings.Workspaces.UnknownAuthor,
                                    subtitle = githubActivityTime(detail.committedDate),
                                    leading = {
                                        GithubAvatar(detail.avatarUrl, detail.author, size = 32.dp)
                                    },
                                    systemName = null,
                                    showTopSeparator = false,
                                )
                                SelectionContainer {
                                    Text(
                                        text = detail.message,
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                        style = GlasmioTheme.textStyles.body,
                                        color = GlasmioTheme.colors.content,
                                    )
                                }
                            }
                        }
                }
            },
        ) {
            if (state.detail == null && !state.failed) {
                readingItem(key = "commit-detail-loading") {
                    GithubReadingGroup { ReadingTextPlaceholder() }
                }
            } else if (state.section == CommitSection.Details) {
                shownDetail?.let { detail ->
                    item(key = "commit-information") {
                        GithubSection(strings.Activity.Details) {
                            GlasmioListRow(
                                title = strings.WorkDetail.Author,
                                leading = {
                                    GithubIcon(
                                        Lucide.AtSign,
                                        contentDescription = null,
                                        tint = GlasmioTheme.colors.secondaryContent,
                                    )
                                },
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        GithubAvatar(detail.avatarUrl, detail.author, size = 24.dp)
                                        Text(
                                            text =
                                                detail.author ?: strings.Workspaces.UnknownAuthor,
                                            style = GlasmioTheme.textStyles.body,
                                            color = GlasmioTheme.colors.secondaryContent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                systemName = null,
                                showTopSeparator = false,
                            )
                            GlasmioListRow(
                                title = strings.Activity.CommitHash,
                                detail = detail.oid.take(7),
                                leading = {
                                    GithubIcon(
                                        Lucide.Copy,
                                        contentDescription = strings.Activity.CopyHash,
                                        tint = GlasmioTheme.colors.secondaryContent,
                                    )
                                },
                                showDisclosure = false,
                                systemName = null,
                                enabled = host.navigationEnabled,
                                onClick = { copy(strings.Activity.CommitHash, detail.oid) },
                            )
                        }
                    }
                    commitLinks(
                        "parents",
                        strings.Activity.Parents,
                        parentSlots,
                        strings,
                        host.navigationEnabled,
                        open = { open(it) },
                    )
                    commitLinks(
                        "pull-requests",
                        strings.Activity.Related,
                        pullRequestSlots,
                        strings,
                        host.navigationEnabled,
                        open = { open(it) },
                    )
                }
            } else {
                githubPagingItems(slots, reading = true, contentType = "commit-file") { entry ->
                    GithubDiffFileCard((entry as? CommitEntry.File)?.value, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun LazyListScope.commitLinks(
    key: String,
    title: String,
    slots: GithubPagingSlots<CommitEntry>,
    strings: GithubStrings,
    enabled: Boolean,
    open: (String) -> Unit,
) {
    item(key = "$key-heading") { GithubSectionHeader(title) }
    githubPagingItems(slots, contentType = "commit-$key") { item ->
        val entry = item as? CommitEntry.Link
        GithubLoadingCard(
            loading = entry == null,
            placeholder = {
                if (key == "parents") WorkspaceCommitPlaceholder() else OrganizationRowPlaceholder()
            },
        ) {
            if (entry != null) {
                val value = entry.value
                if (key == "parents") {
                    GithubCommitSummary(
                        message = value.title,
                        author = value.login ?: strings.Workspaces.UnknownAuthor,
                        avatarUrl = value.avatarUrl,
                        time = value.timestamp?.let(::githubActivityTime),
                        onClick = value.url?.takeIf { enabled }?.let { url -> { open(url) } },
                    )
                } else {
                    GlasmioListRow(
                        title = value.subtitle ?: value.title,
                        leading = {
                            GithubIcon(
                                GithubBrandIcon,
                                contentDescription = null,
                                tint = GlasmioTheme.colors.secondaryContent,
                            )
                        },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "#${value.number}",
                                    style = GlasmioTheme.textStyles.body,
                                    color = GlasmioTheme.colors.secondaryContent,
                                    maxLines = 1,
                                )
                                val status =
                                    when (value.state) {
                                        "MERGED" ->
                                            Triple(
                                                Lucide.GitMerge,
                                                GithubWorkColors.merged,
                                                strings.Explore.Merged,
                                            )
                                        "CLOSED" ->
                                            Triple(
                                                Lucide.GitPullRequestClosed,
                                                GithubWorkColors.closed,
                                                strings.Explore.Closed,
                                            )
                                        "DRAFT" ->
                                            Triple(
                                                Lucide.GitPullRequestDraft,
                                                GithubWorkColors.draft,
                                                strings.Explore.Draft,
                                            )
                                        else ->
                                            Triple(
                                                Lucide.GitPullRequest,
                                                GithubWorkColors.open,
                                                strings.Explore.Open,
                                            )
                                    }
                                GithubIcon(
                                    status.first,
                                    contentDescription = status.third,
                                    tint = status.second,
                                    size = 18.dp,
                                )
                            }
                        },
                        systemName = null,
                        enabled = enabled,
                        showTopSeparator = false,
                        onClick = value.url?.let { url -> { open(url) } },
                    )
                }
            }
        }
    }
}

internal enum class CommitSection {
    Files,
    Details,
    Parents,
    PullRequests,
}

internal sealed interface CommitEntry {
    val id: String

    data class File(val value: PullRequestFileDto) : CommitEntry {
        override val id = value.filename
    }

    data class Link(val value: RepositoryActivityEntry) : CommitEntry {
        override val id = value.id
    }
}

internal data class CommitDetailState(
    val detail: GithubCommitDetail? = null,
    val failed: Boolean = false,
    val section: CommitSection = CommitSection.Files,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class CommitDetailViewModel(
    private val source: CommitRepository,
    private val route: CommitDetailRoute,
    private val account: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CommitDetailState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val empty = flowOf(PagingData.empty<CommitEntry>())
    private val entries =
        CommitSection.entries.associateWith { section ->
            if (section == CommitSection.Details) empty
            else
                state
                    .filter {
                        section == CommitSection.Files || it.section == CommitSection.Details
                    }
                    .map { it.detail?.oid }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .flatMapLatest { oid ->
                        when {
                            section == CommitSection.Files ->
                                source.files(account, route.owner, route.name, oid).map { page ->
                                    page.map<PullRequestFileDto, CommitEntry> {
                                        CommitEntry.File(it)
                                    }
                                }
                            else ->
                                source
                                    .links(
                                        account,
                                        route.owner,
                                        route.name,
                                        oid,
                                        section == CommitSection.Parents,
                                    )
                                    .map { page ->
                                        page.map<RepositoryActivityEntry, CommitEntry> {
                                            CommitEntry.Link(it)
                                        }
                                    }
                        }
                    }
                    .cachedIn(viewModelScope)
        }

    init {
        load()
    }

    fun entries(section: CommitSection) = entries.getValue(section)

    fun select(section: CommitSection) {
        mutableState.update { it.copy(section = section) }
    }

    fun load() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(failed = false, loading = true) }
            try {
                val detail = source.load(account, route.owner, route.name, route.oid)
                mutableState.update { it.copy(detail = detail) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }
}

val commitDetailFeatureModule = module {
    viewModel { (route: CommitDetailRoute, account: String) ->
        CommitDetailViewModel(get(), route, account)
    }
}
