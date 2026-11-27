package moe.yumeyuka.yumehub.feature.releases

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.RotateCcwClock
import io.github.yumeyucca.lucide.Tag
import kotlinx.coroutines.CancellationException
import moe.yumeyuka.yumehub.core.model.githubWebUri
import moe.yumeyuka.yumehub.data.download.DownloadStore
import moe.yumeyuka.yumehub.data.repository.GithubReleaseDetail
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubLoadFailureToast
import moe.yumeyuka.yumehub.ui.component.GithubMarkdown
import moe.yumeyuka.yumehub.ui.component.GithubReactionBar
import moe.yumeyuka.yumehub.ui.component.GithubReadingFooter
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.GithubToolbarActions
import moe.yumeyuka.yumehub.ui.component.githubActivityTime
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.ReleaseMetadataPlaceholder
import moe.yumeyuka.yumehub.ui.component.rememberGithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap
import moe.yumeyuka.yumehub.ui.component.toolbarIcon
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubRoute
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageState
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

data class ReleasesRoute(val owner: String, val name: String, val tag: String? = null) : GithubRoute

@Composable
fun ReleasesPage(route: ReleasesRoute, account: String) {
    val viewModel: ReleasesViewModel =
        koinViewModel(
            key = "releases:$account:$route",
            parameters = { parametersOf(route, account) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = requireNotNull(LocalGithubPageHost.current)
    val strings = LocalGithubStrings.current
    val downloads: DownloadStore = koinInject()
    val context = LocalContext.current
    val openExternal = rememberExternalUriHandler()
    val detail = state.result?.detail
    ReleaseReactionFailureToast(viewModel, strings.Activity.ActionFailed)
    var showTimeline by rememberSaveable { mutableStateOf(false) }
    ReleaseVersionSheet(
        visible = showTimeline && host.navigationEnabled,
        versions = viewModel.timeline,
        selectedId = detail?.version?.id ?: state.selectedVersion?.id,
        revalidation = viewModel.versionsRevalidation,
        onRetryRevalidation = viewModel::retryVersionsRevalidation,
        onDismiss = { showTimeline = false },
        onSelect = { selected ->
            showTimeline = false
            if (host.navigationEnabled) viewModel.select(selected)
        },
    )

    val version = detail?.version
    val assets =
        if (version == null) null
        else
            remember(version.id, viewModel) { viewModel.assets(version.id) }
                .collectAsLazyPagingItems()
    val assetsRevalidation =
        if (version != null) {
            remember(version.id, viewModel) { viewModel.assetsRevalidation(version.id) }
                .collectAsStateWithLifecycle()
                .value
        } else null
    val assetSlots =
        if (version != null && assets != null) {
            rememberGithubPagingSlots(
                assets,
                "release-assets:${version.id}",
                autoRefresh = false,
                accessDenied =
                    (assetsRevalidation as? RevalidationState.Failed)
                        ?.error
                        ?.invalidatesCachedContent() == true,
            ) {
                it.id
            }
        } else null
    val title =
        version?.title ?: state.selectedVersion?.title ?: route.tag ?: strings.Repository.Releases
    val listState =
        LocalGithubPageState.current?.listState(
            "release:${version?.id ?: state.selectedVersion?.id ?: route.tag.orEmpty()}",
        )
    GithubLoadFailureToast(state.failure, strings.Release.LoadFailed)
    GithubLoadFailureToast(
        (assets?.loadState?.refresh as? LoadState.Error)
            ?: (assets?.loadState?.append as? LoadState.Error),
        strings.Release.AssetsLoadFailed,
    )
    GithubPage(
        title = title,
        smallTitle = strings.Repository.Releases,
        titleMaxLines = 4,
        listState = listState,
        itemSpacing = 0.dp,
        contentHeader = {
            SkeletonSwap(
                loading = detail == null,
                placeholder = { ReleaseMetadataPlaceholder(Modifier.padding(bottom = 12.dp)) },
            ) {
                detail?.let {
                    ReleaseMetadata(it, host.navigationEnabled, Modifier.padding(bottom = 12.dp))
                }
            }
        },
        actions = {
            GithubToolbarActions(
                toolbarIcon(
                    Lucide.RotateCcwClock,
                    strings.Release.Timeline,
                    host.navigationEnabled,
                ) {
                    if (host.navigationEnabled) showTimeline = true
                },
            )
        },
    ) {
        if (state.failure != null)
            item(key = "release-detail-error") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Release.LoadFailed,
                        detail = strings.Common.Retry,
                        onClick = viewModel::retry,
                    )
                }
            }
        if (detail == null && state.result != null && state.failure == null)
            item(key = "release-empty") {
                GlasmioGroupedList { GlasmioListRow(title = strings.Release.Empty) }
            }
        if (detail != null || state.loading) {
            readingItem(key = "release-body") {
                GithubReadingGroup(documentKey = detail?.html?.takeIf(String::isNotBlank)) {
                    SkeletonSwap(
                        loading = detail == null,
                        placeholder = {
                            GithubMarkdownPlaceholder(Modifier.padding(16.dp), compact = true)
                        },
                    ) {
                        val value = detail ?: return@SkeletonSwap
                        when {
                            value.html.isNullOrBlank() ->
                                Text(
                                    text = strings.Release.NoNotes,
                                    modifier = Modifier.padding(16.dp),
                                    style = GlasmioTheme.textStyles.body,
                                    color = GlasmioTheme.colors.secondaryContent,
                                )
                            else ->
                                GithubMarkdown(
                                    text = "",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    html = value.html,
                                    nodeId = value.version.id,
                                    pageUrl = value.url,
                                    repositoryRootUrl = value.repositoryUrl,
                                )
                        }
                        if (value.canReact || value.reactions.isNotEmpty())
                            GithubReadingFooter {
                                GithubReactionBar(
                                    subjectKey = value.version.id,
                                    sourceReactions = value.reactions,
                                    canReact = value.canReact,
                                    enabled = host.navigationEnabled && !state.loading,
                                    updates = viewModel.reactionUpdates,
                                ) { content, reacted ->
                                    viewModel.toggleReaction(value.version.id, content, reacted)
                                }
                            }
                    }
                }
            }
        }
        if (detail != null && assets != null && assetSlots != null) {
            if (assetsRevalidation is RevalidationState.Failed && version != null) {
                item(key = "release-assets-sync-failed:${version.id}") {
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = strings.Common.CacheSyncFailed,
                            detail = strings.Common.Retry,
                            showDisclosure = false,
                            onClick = { viewModel.retryAssetsRevalidation(version.id) },
                        )
                    }
                }
            }
            releaseDownloads(
                detail,
                assets,
                assetSlots,
                onDownload = download@{ name, url, size, digest, sourceArchive ->
                        if (!host.navigationEnabled) return@download
                        val safeUrl = githubWebUri(url)?.toASCIIString()
                        try {
                            downloads.enqueueRelease(
                                account,
                                name,
                                requireNotNull(safeUrl),
                                size,
                                digest,
                                sourceArchive,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(
                                    context,
                                    strings.Activity.DownloadFailed,
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                            return@download
                        }
                    },
                onViewAttestation = { url ->
                    if (host.navigationEnabled) {
                        githubWebUri(url)?.toASCIIString()?.let(openExternal)
                    }
                },
                onRetry = { if (host.navigationEnabled) assets.retry() },
            )
        }
    }
}

@Composable
private fun ReleaseReactionFailureToast(viewModel: ReleasesViewModel, message: String) {
    val failure by viewModel.reactionFailure.collectAsStateWithLifecycle()
    GithubLoadFailureToast(failure, message)
}

@Composable
private fun ReleaseMetadata(detail: GithubReleaseDetail, enabled: Boolean, modifier: Modifier) {
    val strings = LocalGithubStrings.current
    val copy = rememberCopyText()
    val tag = detail.version.tag
    GlasmioGroupedList(modifier = modifier) {
        GlasmioListRow(
            title = detail.author ?: strings.Workspaces.UnknownAuthor,
            subtitle = githubActivityTime(detail.version.date),
            leading = { GithubAvatar(detail.authorAvatarUrl, detail.author, size = 32.dp) },
            showTopSeparator = false,
        )
        if (tag.isNotBlank()) {
            GlasmioListRow(
                title = strings.Release.Tag,
                detail = tag,
                leading = {
                    GithubIcon(
                        Lucide.Tag,
                        contentDescription = strings.Release.CopyTag,
                        tint = GlasmioTheme.colors.secondaryContent,
                    )
                },
                showDisclosure = false,
                enabled = enabled,
                onClick = { copy(strings.Release.CopyTag, tag) },
            )
        }
    }
}
