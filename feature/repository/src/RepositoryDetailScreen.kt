package moe.yumeyuka.yumehub.feature.repository

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.BookOpen
import io.github.yumeyucca.lucide.Lucide
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.locale.GithubStrings_Repository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubEntryIcon
import moe.yumeyuka.yumehub.ui.component.GithubMarkdown
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.GithubSection
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubContentVisibility
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubMarkdownPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.ReadmePlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.RepositoryEntryRowsPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.RepositoryHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.readingItem
import moe.yumeyuka.yumehub.ui.platform.rememberExternalUriHandler

internal fun LazyListScope.RepositoryDetailScreen(
    strings: GithubStrings_Repository,
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
    onRetryOverviewReadme: () -> Unit,
    onRepositoryExternal: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val loaded = state.repository
    if (loaded is RepositoryLoad.Error) {
        return
    }
    // 占位与正文共用同一批 item key：数据到达时由卡片内部交接，列表不重建。
    val repository = (loaded as? RepositoryLoad.Ready)?.value
    val currentRef = state.selectedRef ?: repository?.defaultBranch?.let { "refs/heads/$it" }
    if (state.page is RepositoryDetailPage.Readme) {
        item(key = "repository-ref") {
            GithubSection(strings.Refs) {
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
    when (val page = state.page) {
        RepositoryDetailPage.Overview ->
            repositoryOverview(
                strings,
                repository,
                currentRef,
                state.overviewReadme,
                overviewState,
                enabled,
                onToggleStar,
                onBrowseFiles,
                onOpenIssues,
                onOpenPullRequests,
                onRetryOverviewReadme,
                onRepositoryExternal,
                onOpenLicense,
                onOpenSource,
            )
        is RepositoryDetailPage.Readme -> {
            repository?.let { repositoryLocation(strings, it, ref = currentRef) }
            repositoryReadme(
                strings,
                page.preview,
                ref = currentRef,
                repositoryRootUrl = repository?.contentRoot(currentRef),
                onOpenSource = onOpenSource,
                scrollToAnchor = readmeAnchor,
            )
        }
        is RepositoryDetailPage.License ->
            readingItem(key = "repository-license") {
                GithubReadingGroup {
                    GithubLoadingContent(
                        loading = page.contents is RepositoryLoad.Loading,
                        placeholder = { ReadmePlaceholder() },
                    ) {
                        (page.contents as? RepositoryLoad.Ready)?.let {
                            Text(
                                it.value,
                                Modifier.fillMaxWidth().padding(16.dp),
                                style = GlasmioTheme.textStyles.body,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
    }
}

private fun LazyListScope.repositoryOverview(
    strings: GithubStrings_Repository,
    repository: RepositoryDto?,
    ref: String?,
    readme: RepositoryLoad<RepositoryTextPreview>?,
    overview: RepositoryOverviewState,
    enabled: Boolean,
    onToggleStar: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenIssues: (() -> Unit)?,
    onOpenPullRequests: (() -> Unit)?,
    onRetryReadme: () -> Unit,
    onExternal: (String) -> Unit,
    onOpenLicense: () -> Unit,
    onOpenSource: (String?) -> Unit,
) {
    item(key = "repository-header") {
        GithubLoadingContent(
            loading = repository == null,
            placeholder = { RepositoryHeaderPlaceholder() },
        ) {
            if (repository != null)
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
    readingItem(key = "repository-overview-body") {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GithubLoadingContent(
                loading = repository == null,
                placeholder = { RepositoryEntryRowsPlaceholder() },
            ) {
                if (repository != null)
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
            val readmeText = (readme as? RepositoryLoad.Ready)?.value
            GithubContentVisibility(
                animateEnter = false,
                visible =
                    readme == null ||
                        readme is RepositoryLoad.Loading ||
                        readme is RepositoryLoad.Error ||
                        readmeText?.html != null,
            ) {
                when (readme) {
                    null,
                    RepositoryLoad.Loading -> GithubReadingGroup { RepositoryReadmePlaceholder() }
                    is RepositoryLoad.Error -> Unit
                    is RepositoryLoad.Ready ->
                        readmeText?.html?.let { html ->
                            RepositoryOverviewReadme(
                                html = html,
                                pageUrl =
                                    readmeText.htmlUrl?.takeIf { "/blob/" in it }
                                        ?: repository?.contentRoot(ref),
                                repositoryRootUrl = repository?.contentRoot(ref),
                                ref = ref,
                                onOpenFile =
                                    readmeText.path
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { path -> { onOpenSource(path) } },
                            )
                        }
                }
            }
        }
    }
}

/** 概览 README：首帧占位由共享渲染器管理。 */
@Composable
private fun RepositoryOverviewReadme(
    html: String,
    pageUrl: String?,
    repositoryRootUrl: String?,
    ref: String?,
    onOpenFile: (() -> Unit)?,
) {
    GithubReadingGroup(
        onHeaderClick = onOpenFile,
        header = {
            DisableSelection {
                val labelColor = GlasmioTheme.colors.secondaryContent
                GlasmioListRow(
                    title = LocalGithubStrings.current.Repository.ReadmeFileTitle,
                    titleColor = labelColor,
                    systemName = null,
                    leading = { GithubEntryIcon(Lucide.BookOpen, tint = labelColor) },
                    showTopSeparator = false,
                    showDisclosure = true,
                )
            }
        },
    ) {
        GithubMarkdown(
            "",
            Modifier.fillMaxWidth().readmePadding(),
            pageUrl = pageUrl,
            repositoryRootUrl = repositoryRootUrl,
            html = html,
            readme = true,
            ref = ref,
        )
    }
}

private fun LazyListScope.repositoryLocation(
    strings: GithubStrings_Repository,
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
    strings: GithubStrings_Repository,
    preview: RepositoryLoad<RepositoryTextPreview>,
    ref: String?,
    repositoryRootUrl: String?,
    onOpenSource: (path: String?) -> Unit,
    scrollToAnchor: String? = null,
) {
    if (preview is RepositoryLoad.Error) return
    readingItem(key = "repository-text") {
        GithubReadingGroup {
            GithubLoadingContent(
                loading = preview is RepositoryLoad.Loading,
                placeholder = { RepositoryReadmePlaceholder() },
            ) {
                if (preview is RepositoryLoad.Ready)
                    Column {
                        val html = preview.value.html
                        if (html.isNullOrEmpty()) {
                            DisableSelection {
                                GlasmioListRow(
                                    title =
                                        if (html == null) strings.ReadNotFound
                                        else strings.ContentEmpty,
                                    systemName = null,
                                )
                            }
                        } else {
                            GithubMarkdown(
                                "",
                                Modifier.fillMaxWidth().readmePadding(),
                                pageUrl =
                                    preview.value.htmlUrl?.takeIf { "/blob/" in it }
                                        ?: repositoryRootUrl,
                                repositoryRootUrl = repositoryRootUrl,
                                html = html,
                                readme = true,
                                ref = ref,
                                scrollToAnchor = scrollToAnchor,
                            )
                        }
                        DisableSelection {
                            if (preview.value.path != null) {
                                GlasmioListRow(
                                    title = strings.ViewSource,
                                    systemName = null,
                                    onClick = { onOpenSource(preview.value.path) },
                                )
                            }
                            RepositoryWebRow(preview.value.htmlUrl)
                        }
                    }
            }
        }
    }
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
    GithubMarkdownPlaceholder(Modifier.readmePadding(), compact = true)
}

@Composable
private fun RepositoryWebRow(url: String?) {
    val strings = LocalGithubStrings.current.Repository
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
