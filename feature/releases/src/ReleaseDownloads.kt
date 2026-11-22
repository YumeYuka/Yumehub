package moe.yumeyuka.yumehub.feature.releases

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.yumeyucca.lucide.BadgeCheck
import io.github.yumeyucca.lucide.Copy
import io.github.yumeyucca.lucide.Download
import io.github.yumeyucca.lucide.Ellipsis
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.Share
import moe.yumeyuka.yumehub.core.model.githubWebUri
import moe.yumeyuka.yumehub.data.repository.GithubReleaseAsset
import moe.yumeyuka.yumehub.data.repository.GithubReleaseDetail
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubPagingSlots
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.component.githubDownloadFileIcon
import moe.yumeyuka.yumehub.ui.component.githubPagingItemsIndexed
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.ReleaseAssetPlaceholder
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.platform.rememberCopyText

internal fun LazyListScope.releaseDownloads(
    detail: GithubReleaseDetail,
    assets: LazyPagingItems<GithubReleaseAsset>,
    slots: GithubPagingSlots<GithubReleaseAsset>,
    onDownload:
        (name: String, url: String, size: Long?, digest: String?, sourceArchive: Boolean) -> Unit,
    onViewAttestation: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val archives = detail.sourceArchives()
    val failure =
        assets.loadState.refresh is LoadState.Error || assets.loadState.append is LoadState.Error
    val assetRows = slots.size + if (failure) 1 else 0
    val showAttestation = detail.immutable && !detail.version.draft
    val sourceRows = archives.size + if (showAttestation) 1 else 0

    if (assetRows > 0)
        item(key = "release-downloads-heading") {
            GithubSectionHeader(
                LocalGithubStrings.current.Release.Artifacts,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    githubPagingItemsIndexed(slots, contentType = "release-download") { asset, index, _ ->
        ReleaseListGroupRow(index, assetRows) {
            GithubLoadingContent(
                loading = asset == null,
                placeholder = { ReleaseAssetPlaceholder(showTopSeparator = index > 0) },
            ) {
                if (asset != null) {
                    ReleaseDownloadRow(
                        showTopSeparator = index > 0,
                        icon = githubDownloadFileIcon(sourceArchive = false),
                        name = asset.name,
                        subtitle =
                            LocalGithubStrings.current.Release.AssetStats(
                                Formatter.formatShortFileSize(LocalContext.current, asset.size),
                                asset.downloadCount,
                            ),
                        url = asset.url,
                        asset = asset,
                        onClick = {
                            onDownload(asset.name, asset.url, asset.size, asset.digest, false)
                        },
                    )
                }
            }
        }
    }
    if (failure) {
        item(key = "release-downloads-footer") {
            ReleaseListGroupRow(slots.size, assetRows) {
                GlasmioListRow(
                    title = LocalGithubStrings.current.Markdown.Retry,
                    showTopSeparator = slots.size > 0,
                    showDisclosure = false,
                    onClick = onRetry,
                )
            }
        }
    }
    if (sourceRows > 0)
        item(key = "release-sources-heading") {
            GithubSectionHeader(
                LocalGithubStrings.current.Release.SourceAndAttestation,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    archives.forEachIndexed { index, archive ->
        item(key = "release-archive:${archive.extension}") {
            ReleaseListGroupRow(index, sourceRows) {
                ReleaseDownloadRow(
                    showTopSeparator = index > 0,
                    icon = githubDownloadFileIcon(sourceArchive = true),
                    name = LocalGithubStrings.current.Release.SourceArchive(archive.extension),
                    url = archive.url,
                    onClick = { onDownload(archive.fileName, archive.url, null, null, true) },
                )
            }
        }
    }
    if (showAttestation) {
        item(key = "release-attestation:${detail.version.id}") {
            ReleaseListGroupRow(sourceRows - 1, sourceRows) {
                ReleaseDownloadRow(
                    showTopSeparator = sourceRows > 1,
                    icon = Lucide.BadgeCheck,
                    name = LocalGithubStrings.current.Release.ViewAttestation,
                    url = detail.url,
                    viewAttestation = true,
                    onClick = { onViewAttestation(detail.url) },
                )
            }
        }
    }
}

@Composable
private fun ReleaseDownloadRow(
    showTopSeparator: Boolean,
    icon: ImageVector,
    name: String,
    url: String,
    subtitle: String? = null,
    asset: GithubReleaseAsset? = null,
    viewAttestation: Boolean = false,
    onClick: () -> Unit,
) {
    val strings = LocalGithubStrings.current
    val context = LocalContext.current
    val copy = rememberCopyText()
    val host = requireNotNull(LocalGithubPageHost.current)
    var expanded by remember(url) { mutableStateOf(false) }
    val tint = GlasmioTheme.colors.secondaryContent
    GlasmioListRow(
        title = name,
        subtitle = subtitle,
        showTopSeparator = showTopSeparator,
        leading = { GithubIcon(icon, contentDescription = null, tint = tint) },
        enabled = host.navigationEnabled,
        showDisclosure = false,
        trailing = {
            GlasmioMenu(
                expanded = expanded && host.navigationEnabled,
                onExpandedChange = { expanded = it },
                itemCount = if (asset != null) 3 else 2,
                anchor = {
                    Box(
                        modifier =
                            Modifier.size(48.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                    enabled = host.navigationEnabled,
                                    role = Role.Button,
                                    onClick = { expanded = true },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        GithubIcon(
                            Lucide.Ellipsis,
                            contentDescription = strings.EditorMenu.More,
                            tint = tint,
                        )
                    }
                },
            ) { backdrop, close ->
                GlasmioMenuItem(
                    title =
                        if (viewAttestation) strings.Release.ViewAttestation
                        else strings.Release.Download,
                    backdrop = backdrop,
                    icon = {
                        GithubIcon(
                            if (viewAttestation) Lucide.BadgeCheck else Lucide.Download,
                            contentDescription = null,
                            size = 20.dp,
                            tint = tint,
                        )
                    },
                    onClick = {
                        close()
                        onClick()
                    },
                )
                GlasmioMenuItem(
                    title = strings.Repository.ShareLink,
                    backdrop = backdrop,
                    icon = {
                        GithubIcon(
                            Lucide.Share,
                            contentDescription = null,
                            size = 20.dp,
                            tint = tint,
                        )
                    },
                    onClick = {
                        close()
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, url)
                        try {
                            context.startActivity(
                                Intent.createChooser(send, strings.Repository.ShareLink),
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                    context,
                                    strings.Common.ExternalLinkOpenFailed,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    },
                )
                if (asset != null)
                    GlasmioMenuItem(
                        title = strings.Release.CopyDigest,
                        backdrop = backdrop,
                        enabled = asset.digest != null,
                        icon = {
                            GithubIcon(
                                Lucide.Copy,
                                contentDescription = null,
                                size = 20.dp,
                                tint = tint,
                            )
                        },
                        onClick = {
                            close()
                            asset.digest?.let { copy(strings.Release.CopyDigest, it) }
                        },
                    )
            }
        },
        onClick = onClick,
    )
}

@Composable
internal fun ReleaseListGroupRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val top = if (index == 0) 26.dp else 0.dp
    val bottom = if (index == count - 1) 26.dp else 0.dp
    GlasmioGroupedList(
        modifier =
            modifier.glasmioGroupedListBackground(
                ContinuousRoundedRectangle(
                    topStart = top,
                    topEnd = top,
                    bottomStart = bottom,
                    bottomEnd = bottom,
                ),
            ),
        framed = false,
        content = content,
    )
}

private data class SourceArchive(val extension: String, val fileName: String, val url: String)

private fun GithubReleaseDetail.sourceArchives(): List<SourceArchive> {
    if (!hasTag || version.tag.isBlank()) return emptyList()
    val root =
        githubWebUri(repositoryUrl)?.takeIf { it.rawQuery == null && it.rawFragment == null }
            ?: return emptyList()
    val tag = version.tag.split('/')
    if (tag.any { it.isEmpty() || it == "." || it == ".." }) return emptyList()
    return listOf("zip", "tar.gz").map { extension ->
        val builder =
            Uri.parse(root.toASCIIString())
                .buildUpon()
                .appendPath("archive")
                .appendPath("refs")
                .appendPath("tags")
        tag.dropLast(1).forEach { builder.appendPath(it) }
        val fileName = "${tag.last()}.$extension"
        SourceArchive(extension, fileName, builder.appendPath(fileName).build().toString())
    }
}
