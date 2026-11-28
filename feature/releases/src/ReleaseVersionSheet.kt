package moe.yumeyuka.yumehub.feature.releases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioListRowDefaults
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.yumeyuka.yumehub.data.repository.GithubReleaseVersion
import moe.yumeyuka.yumehub.data.repository.RevalidationState
import moe.yumeyuka.yumehub.data.repository.invalidatesCachedContent
import moe.yumeyuka.yumehub.ui.component.rememberPagingSlots
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast
import moe.yumeyuka.yumehub.ui.component.PagingSlotKeys
import moe.yumeyuka.yumehub.ui.component.SearchSelectionSheet
import moe.yumeyuka.yumehub.ui.component.formatRelativeTime
import moe.yumeyuka.yumehub.ui.component.placeholders.LoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ReleaseVersionPlaceholder
import moe.yumeyuka.yumehub.ui.theme.WorkColors

@Composable
internal fun ReleaseVersionSheet(
    visible: Boolean,
    versions: Flow<PagingData<GithubReleaseVersion>>,
    selectedId: String?,
    revalidation: StateFlow<RevalidationState>,
    onRetryRevalidation: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (GithubReleaseVersion) -> Unit,
) {
    val strings = LocalAppStrings.current
    val revalidationState by revalidation.collectAsStateWithLifecycle()
    var query by remember(visible) { mutableStateOf("") }
    val pages = if (visible) versions.collectAsLazyPagingItems() else null
    val slots = pages?.let { items ->
        rememberPagingSlots(
            items, "release-selector", autoRefresh = false,
            accessDenied = (revalidationState as? RevalidationState.Failed)?.error?.invalidatesCachedContent() == true,
        ) { it.id }
    }
    val snapshot = slots?.snapshot
    val matches =
        remember(snapshot, query) {
            buildList {
                snapshot?.forEachIndexed { index, version ->
                    if (version != null && (query.isEmpty() || version.tag.contains(query, ignoreCase = true) ||
                        version.name?.contains(query, ignoreCase = true) == true)) {
                        add(IndexedValue(index, version))
                    }
                }
            }
        }
    val failure =
        (pages?.loadState?.refresh as? LoadState.Error)
            ?: (pages?.loadState?.append as? LoadState.Error)
    LoadFailureToast(failure.takeIf { visible }, strings.Release.LoadFailed)
    val initialLoading =
        pages == null || (slots?.snapshot.isNullOrEmpty() && slots?.isEmptyResult != true && pages.loadState.refresh is LoadState.Loading)
    val appending = pages?.loadState?.append is LoadState.Loading
    val allocator = remember(versions, query) { PagingSlotKeys("release-versions") }
    val rowKeys =
        remember(allocator, matches, initialLoading, appending) {
            allocator.update(matches.map { it.value.id }, appending, initialLoading)
        }

    SearchSelectionSheet(
        visible = visible,
        title = strings.Release.Timeline,
        placeholder = strings.Release.SearchVersions,
        queryKey = query,
        onQueryChange = { query = it.trim() },
        onDismiss = onDismiss,
        onConfirm = onDismiss,
        showConfirm = false,
    ) { finish ->
        if (revalidationState is RevalidationState.Failed) {
            item(key = "release-versions-sync-failed") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = strings.Common.CacheSyncFailed,
                        detail = strings.Common.Retry,
                        showDisclosure = false,
                        onClick = onRetryRevalidation,
                    )
                }
            }
        }
        val canLoadMore =
            pages != null &&
                query.isNotEmpty() &&
                pages.itemCount > 0 &&
                !pages.loadState.append.endOfPaginationReached
        val footer = !initialLoading && (failure != null || canLoadMore || matches.isEmpty() && (slots?.isEmptyResult == true || !snapshot.isNullOrEmpty()))
        val size = rowKeys.size + if (footer && !appending) 1 else 0
        items(size, key = { rowKeys.getOrNull(it) ?: "versions-footer" }) { index ->
            val match = matches.getOrNull(index)
            // 浏览时正常预取；搜索时由「加载更多」继续翻页。
            if (visible && query.isEmpty() && match != null && match.index < (pages?.itemCount ?: 0)) pages?.get(match.index)
            ReleaseListGroupRow(index, size) {
                LoadingContent(
                    loading = match == null && (initialLoading || appending && failure == null),
                    placeholder = { ReleaseVersionPlaceholder(index > 0) },
                ) {
                    when {
                        match != null -> {
                            val version = match.value
                            ReleaseVersionRow(
                                version = version,
                                selected = version.id == selectedId,
                                showTopSeparator = index > 0,
                                onClick = { finish { onSelect(version) } },
                            )
                        }
                        failure != null ->
                            GlasmioListRow(
                                title = strings.Markdown.Retry,
                                showTopSeparator = matches.isNotEmpty(),
                                showDisclosure = false,
                                onClick = { pages?.retry() },
                            )
                        canLoadMore ->
                            GlasmioListRow(
                                title = strings.Activity.LoadMoreItems,
                                showTopSeparator = matches.isNotEmpty(),
                                showDisclosure = false,
                                onClick = {
                                    if (pages.itemCount > 0) pages[pages.itemCount - 1]
                                },
                            )
                        else ->
                            GlasmioListRow(
                                title =
                                    if (query.isEmpty()) strings.Release.Empty
                                    else strings.Release.NoMatchingVersions,
                                showTopSeparator = false,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseVersionRow(
    version: GithubReleaseVersion,
    selected: Boolean,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val colors = GlasmioTheme.colors
    val textStyles = GlasmioTheme.textStyles
    val title = version.title ?: strings.Repository.Releases
    val separateTag = version.tag.isNotBlank() && version.tag != title
    val status =
        when {
            version.draft -> strings.Explore.Draft to colors.secondaryContent
            version.prerelease -> strings.Release.Prerelease to WorkColors.releasePrerelease
            version.latest -> strings.Release.Latest to WorkColors.releaseLatest
            else -> null
        }
    val time = formatRelativeTime(version.date, strings.Notifications)
    val inset = GlasmioListRowDefaults.horizontalPadding
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = inset)
                .glasmioGroupedListSeparator(colors.separator, visible = showTopSeparator)
                .heightIn(min = if (separateTag) 68.dp else 52.dp)
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (separateTag) {
                Text(
                    text = title,
                    style = textStyles.body,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (separateTag) version.tag else title,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    style = if (separateTag) textStyles.subheadline else textStyles.body,
                    color = if (separateTag) colors.secondaryContent else colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (time != null) {
                    Text(
                        text = time,
                        modifier = Modifier.alignByBaseline(),
                        style = textStyles.footnote,
                        color = colors.secondaryContent,
                        maxLines = 1,
                    )
                }
                status?.let { (label, color) ->
                    Text(
                        text = label,
                        modifier = Modifier.alignByBaseline(),
                        style = textStyles.footnote,
                        color = color,
                    )
                }
            }
        }
        if (selected) {
            AppIcon(Lucide.Check, contentDescription = null, tint = colors.content)
        }
    }
}
