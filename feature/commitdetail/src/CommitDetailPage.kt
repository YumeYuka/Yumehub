package moe.yumeyuka.yumehub.feature.commitdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.*
import io.github.yumeyucca.lucide.AtSign
import io.github.yumeyucca.lucide.Copy
import io.github.yumeyucca.lucide.GitMerge
import io.github.yumeyucca.lucide.GitPullRequest
import io.github.yumeyucca.lucide.GitPullRequestClosed
import io.github.yumeyucca.lucide.GitPullRequestDraft
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.cache.DataDomain
import moe.yumeyuka.yumehub.data.repository.*
import moe.yumeyuka.yumehub.locale.GithubStrings
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.*
import moe.yumeyuka.yumehub.ui.component.placeholders.CommitAuthorPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.OrganizationRowPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.WorkspaceCommitPlaceholder
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap
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
    var section by rememberSaveable(account, route) { mutableStateOf(CommitSection.Files) }
    val revision = state.revision
    val expansions = rememberGithubDiffExpansions(revision?.oid)
    val sectionKey = "commit:$account:$route:${section.name}"
    val listState =
        LocalGithubPageState.current?.listState(sectionKey)
            ?: key(sectionKey) { rememberLazyListState() }
    val information = section == CommitSection.Details
    val files = if (!information) revision?.files?.collectRows { it.filename } else null
    val parents = if (information) revision?.parents?.collectRows { it.id } else null
    val pullRequests = if (information) revision?.pullRequests?.collectRows { it.id } else null
    val readyDetail = state.detail
    GithubPage(
        title = readyDetail?.title ?: route.oid.take(7),
        titleMaxLines = 2,
        titleStyle =
            GlasmioTheme.typography.headlineLarge.copy(
                fontSize = 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
            ),
        smallTitle = (readyDetail?.oid ?: route.oid).take(7),
        listState = listState,
        itemSpacing = if (information) 12.dp else 8.dp,
        contentHeader = {
            GlasmioSegmentedControl(
                items =
                    listOf(
                        CommitSection.Files to strings.WorkDetail.Changes,
                        CommitSection.Details to strings.Activity.Details,
                    ),
                selected = section,
                onSelected = { if (host.navigationEnabled) section = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (state.failed) {
            item(key = "commit-detail-error") {
                CommitStatusRow(strings.Activity.LoadFailed, viewModel::load)
            }
        }
        if (!information) {
            if (readyDetail != null || !state.failed) {
                readingItem(key = "commit-author") {
                    SkeletonSwap(
                        loading = readyDetail == null,
                        placeholder = { CommitAuthorPlaceholder() },
                    ) {
                        val detail = readyDetail ?: return@SkeletonSwap
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
            }
            if (files != null) {
                githubPagingItems(files.slots, reading = true, contentType = "commit-file") { file
                    ->
                    GithubDiffFileCard(file, expansions, Modifier.fillMaxWidth())
                }
                commitPageStatus(files, strings)
            } else if (!state.failed) {
                readingItem(key = "commit-files-pending") { GithubDiffFileCard(null, expansions) }
            }
        } else {
            item(key = "commit-information") {
                SkeletonSwap(
                    loading = readyDetail == null && !state.failed,
                    placeholder = {
                        GithubSection(strings.Activity.Details) { OrganizationRowPlaceholder() }
                    },
                ) {
                    val detail = readyDetail ?: return@SkeletonSwap
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
                                        text = detail.author ?: strings.Workspaces.UnknownAuthor,
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
            }
            commitLinks(
                "parents",
                strings.Activity.Parents,
                parents,
                !state.failed,
                strings,
                host.navigationEnabled,
                open = { open(it) },
            )
            commitLinks(
                "pull-requests",
                strings.Activity.Related,
                pullRequests,
                !state.failed,
                strings,
                host.navigationEnabled,
                open = { open(it) },
            )
        }
    }
}

private fun LazyListScope.commitLinks(
    key: String,
    title: String,
    rows: CommitRows<RepositoryActivityEntry>?,
    waitingForRevision: Boolean,
    strings: GithubStrings,
    enabled: Boolean,
    open: (String) -> Unit,
) {
    item(key = "$key-heading") { GithubSectionHeader(title) }
    if (rows == null) {
        if (waitingForRevision)
            item(key = "$key-pending") {
                GithubLoadingCard(
                    loading = true,
                    placeholder = {
                        if (key == "parents") WorkspaceCommitPlaceholder()
                        else OrganizationRowPlaceholder()
                    },
                ) {}
            }
        return
    }
    githubPagingItems(rows.slots, contentType = "commit-$key") { entry ->
        GithubLoadingCard(
            loading = entry == null,
            placeholder = {
                if (key == "parents") WorkspaceCommitPlaceholder() else OrganizationRowPlaceholder()
            },
        ) {
            if (entry != null) {
                val value = entry
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
    commitPageStatus(rows, strings)
}

private enum class CommitSection {
    Files,
    Details,
}

private class CommitRows<T : Any>(
    val source: CommitSource<T>,
    val pages: LazyPagingItems<T>,
    val slots: GithubPagingSlots<T>,
    val sync: RevalidationState,
)

@Composable
private fun <T : Any> CommitSource<T>.collectRows(id: (T) -> String): CommitRows<T> {
    val items = pages.collectAsLazyPagingItems()
    val sync by revalidation.collectAsStateWithLifecycle()
    val slots =
        rememberGithubPagingSlots(
            items,
            key,
            autoRefresh = false,
            accessDenied =
                (sync as? RevalidationState.Failed)?.error?.invalidatesCachedContent() == true,
            itemKey = id,
        )
    return CommitRows(this, items, slots, sync)
}

private fun LazyListScope.commitPageStatus(rows: CommitRows<*>, strings: GithubStrings) {
    val loads = rows.pages.loadState
    if (
        loads.refresh is LoadState.Error ||
            loads.append is LoadState.Error ||
            loads.prepend is LoadState.Error
    ) {
        item(key = "${rows.source.key}:error") {
            CommitStatusRow(strings.Activity.LoadFailed, rows.pages::retry)
        }
    }
    if (rows.sync is RevalidationState.Failed) {
        item(key = "${rows.source.key}:sync-error") {
            CommitStatusRow(strings.Common.CacheSyncFailed, rows.source.retryRevalidation)
        }
    }
}

@Composable
private fun CommitStatusRow(message: String, retry: (() -> Unit)? = null) {
    val host = requireNotNull(LocalGithubPageHost.current)
    GlasmioGroupedList {
        GlasmioListRow(
            title = message,
            detail = if (retry != null) LocalGithubStrings.current.Common.Retry else null,
            showDisclosure = false,
            showTopSeparator = false,
            onClick = retry?.let { { if (host.navigationEnabled) it() } },
        )
    }
}

val commitDetailFeatureModule = module {
    viewModel { (route: CommitDetailRoute, account: String) ->
        CommitDetailViewModel(get(), route, account)
    }
}
