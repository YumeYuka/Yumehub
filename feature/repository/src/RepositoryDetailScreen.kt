package moe.yumeyuka.yumehub.feature.repository

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.BookOpen
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.RepositoryStrings
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.EntryIcon
import moe.yumeyuka.yumehub.ui.component.MarkdownBody
import moe.yumeyuka.yumehub.ui.component.ReadingFooter
import moe.yumeyuka.yumehub.ui.component.ReadingGroup
import moe.yumeyuka.yumehub.ui.component.ListSection
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingCard
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.MarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.RepositoryEntryRowsPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.RepositoryHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.component.skeleton.SkeletonSwap
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler

internal fun LazyListScope.RepositoryDetailScreen(
    strings: RepositoryStrings,
    state: RepositoryDetailUiState,
    onBrowseFiles: () -> Unit,
    onSwitchRef: (String) -> Unit,
    onLoadRefs: () -> Unit,
    // 只表示业务状态是否允许操作，栈顶归属在页面回调校验，不参与禁用配色。
    enabled: Boolean,
    onOpenIssues: (() -> Unit)? = null,
    onOpenPullRequests: (() -> Unit)? = null,
    onOpenSource: (path: String?) -> Unit = {},
    readmeAnchor: String? = null,
    overviewState: RepositoryOverviewState,
    onToggleStar: () -> Unit,
    onRetryRepository: () -> Unit,
    onRetryOverview: () -> Unit,
    onRepositoryExternal: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val loaded = state.repository
    if (loaded is RepositoryLoad.Error) {
        item(key = "repository-error") {
            GlasmioGroupedList { RepositoryRetryRow(loaded.message, onRetryRepository) }
        }
        return
    }
    // 占位与正文共用同一批 item key：数据到达时由卡片内部交接，列表不重建。
    val repository = (loaded as? RepositoryLoad.Ready)?.value
    (loaded as? RepositoryLoad.Ready)?.refreshError?.let { error ->
        item(key = "repository-error") {
            GlasmioGroupedList { RepositoryRetryRow(error, onRetryRepository) }
        }
    }
    val currentRef = state.selectedRef ?: repository?.defaultBranch?.let { "refs/heads/$it" }
    if (state.page == RepositoryDetailPage.Readme) {
        item(key = "repository-ref") {
            ListSection(strings.Refs) {
                RepositoryRefSelector(
                    state.refs,
                    currentRef.orEmpty(),
                    enabled,
                    onSwitchRef,
                    onLoadRefs,
                )
            }
        }
    }
    when (state.page) {
        RepositoryDetailPage.Overview ->
            repositoryOverview(
                strings,
                repository,
                currentRef,
                state.readme,
                overviewState,
                enabled,
                onToggleStar,
                onBrowseFiles,
                onOpenIssues,
                onOpenPullRequests,
                onRetryOverview,
                onRepositoryExternal,
                onOpenLicense,
                onOpenSource,
            )
        RepositoryDetailPage.Readme -> {
            repository?.let { repositoryLocation(strings, it, ref = currentRef) }
            repositoryReadme(
                strings,
                state.readme,
                ref = currentRef,
                repositoryRootUrl = repository?.contentRoot(currentRef),
                onOpenSource = onOpenSource,
                scrollToAnchor = readmeAnchor,
            )
        }
    }
}

private fun LazyListScope.repositoryOverview(
    strings: RepositoryStrings,
    repository: RepositoryDto?,
    ref: String?,
    readme: RepositoryLoad<RepositoryTextPreview>,
    overview: RepositoryOverviewState,
    enabled: Boolean,
    onToggleStar: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenIssues: (() -> Unit)?,
    onOpenPullRequests: (() -> Unit)?,
    onRetryOverview: () -> Unit,
    onExternal: (String) -> Unit,
    onOpenLicense: () -> Unit,
    onOpenSource: (String?) -> Unit,
) {
    item(key = "repository-header") {
        LoadingContent(
            loading = repository == null,
            placeholder = { RepositoryHeaderPlaceholder() },
        ) {
            if (repository != null) {
                Column {
                    RepositoryHeaderCard(
                        repository,
                        overview,
                        enabled,
                        onToggleStar,
                        onFork = { onExternal("fork") },
                        onOpenLink = onExternal,
                    )
                }
            }
        }
    }
    item(key = "repository-entries") {
        LoadingCard(
            loading = repository == null,
            placeholder = { RepositoryEntryRowsPlaceholder() },
        ) {
            if (repository != null) {
                RepositoryEntryCard(
                    repository,
                    overview,
                    enabled,
                    onBrowseFiles,
                    onOpenIssues,
                    onOpenPullRequests,
                    onExternal,
                    onOpenLicense,
                )
            }
            overview.error?.let { RepositoryRetryRow(it, onRetryOverview) }
        }
    }
    readingItem(key = "repository-overview-readme") {
        RepositoryReadmeCard(
            strings,
            readme,
            ref,
            repository?.contentRoot(ref),
            onOpenSource,
            overview = true,
        )
    }
}

@Composable
private fun RepositoryOverviewReadmeHeader() {
    DisableSelection {
        val labelColor = GlasmioTheme.colors.secondaryContent
        GlasmioListRow(
            title = LocalAppStrings.current.Repository.ReadmeFileTitle,
            titleColor = labelColor,
            systemName = null,
            leading = { EntryIcon(Lucide.BookOpen, tint = labelColor) },
            showTopSeparator = false,
            showDisclosure = true,
        )
    }
}

private fun LazyListScope.repositoryLocation(
    strings: RepositoryStrings,
    repository: RepositoryDto,
    path: String? = null,
    ref: String? = null,
) {
    val name = repository.fullName ?: repository.name.orEmpty()
    val branch = ref?.let { repositoryRefLabel(it, strings) }
    val title = path?.ifEmpty { strings.RootDirectory } ?: name
    val subtitle =
        if (path == null) branch
        else
            listOfNotNull(name.takeIf(String::isNotBlank), branch)
                .joinToString(" · ")
                .takeIf(String::isNotEmpty)
    item(key = "repository-location") {
        GlasmioGroupedList { GlasmioListRow(title = title, subtitle = subtitle, systemName = null) }
    }
}

private fun LazyListScope.repositoryReadme(
    strings: RepositoryStrings,
    preview: RepositoryLoad<RepositoryTextPreview>,
    ref: String?,
    repositoryRootUrl: String?,
    onOpenSource: (path: String?) -> Unit,
    scrollToAnchor: String?,
) {
    readingItem(key = "repository-text") {
        RepositoryReadmeCard(strings, preview, ref, repositoryRootUrl, onOpenSource, scrollToAnchor)
    }
}

@Composable
private fun RepositoryReadmeCard(
    strings: RepositoryStrings,
    preview: RepositoryLoad<RepositoryTextPreview>,
    ref: String?,
    repositoryRootUrl: String?,
    onOpenSource: (String?) -> Unit,
    scrollToAnchor: String? = null,
    overview: Boolean = false,
) {
    // 没有 README 或加载失败时不占用任何卡片位置。
    if (preview is RepositoryLoad.Error) return
    val document = (preview as? RepositoryLoad.Ready)?.value
    val html = document?.html?.takeIf(String::isNotBlank)
    if (preview is RepositoryLoad.Ready && html == null) return
    val path = document?.path?.takeIf(String::isNotBlank)
    ReadingGroup(
        documentKey = html,
        onHeaderClick = if (overview && path != null) ({ onOpenSource(path) }) else null,
        header = { if (overview) RepositoryOverviewReadmeHeader() },
    ) {
        SkeletonSwap(
            loading = preview is RepositoryLoad.Loading,
            placeholder = { RepositoryReadmePlaceholder() },
        ) {
            if (preview is RepositoryLoad.Ready) {
                MarkdownBody(
                    "",
                    Modifier.fillMaxWidth().readmePadding(),
                    pageUrl = preview.value.htmlUrl?.takeIf { "/blob/" in it } ?: repositoryRootUrl,
                    repositoryRootUrl = repositoryRootUrl,
                    ref = ref,
                    html = html,
                    readme = true,
                    scrollToAnchor = scrollToAnchor,
                )
                if (!overview)
                    ReadingFooter {
                        DisableSelection {
                            Column {
                                if (path != null)
                                    GlasmioListRow(
                                        title = strings.ViewSource,
                                        systemName = null,
                                        onClick = { onOpenSource(path) },
                                    )
                                RepositoryWebRow(preview.value.htmlUrl)
                            }
                        }
                    }
            }
        }
    }
}

@Composable
internal fun RepositoryRetryRow(message: String, onRetry: () -> Unit) {
    GlasmioListRow(
        title = message,
        detail = LocalAppStrings.current.Common.Retry,
        systemName = null,
        onClick = onRetry,
    )
}

private fun RepositoryDto.contentRoot(ref: String?): String? {
    val root = htmlUrl?.trimEnd('/') ?: return null
    val branch = ref?.takeIf(String::isNotBlank) ?: return null
    val prefix = if (branch.startsWith("refs/heads/")) "refs/heads/" else "refs/tags/"
    val name = branch.removePrefix(prefix)
    return "$root/blob/${Uri.encode(name, "/")}/"
}

private fun Modifier.readmePadding(): Modifier =
    padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp)

@Composable
private fun RepositoryReadmePlaceholder() {
    MarkdownPlaceholder(Modifier.readmePadding(), compact = true)
}

@Composable
private fun RepositoryWebRow(url: String?) {
    val strings = LocalAppStrings.current.Repository
    val uri = url?.let(Uri::parse) ?: return
    if (
        uri.scheme != "https" ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
    )
        return
    val openExternal = rememberExternalUriHandler()
    GlasmioListRow(
        title = strings.ViewOnGitHub,
        systemName = null,
        onClick = { openExternal(uri.toString()) },
    )
}
