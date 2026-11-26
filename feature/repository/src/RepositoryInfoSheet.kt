package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.repository.RepositoryOverview
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubAvatar
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSection

/** 点击仓库头部后展示的仓库信息；数据缺失的行直接省略，不显示占位。 */
@Composable
internal fun RepositoryInfoSheet(
    visible: Boolean,
    repository: RepositoryDto?,
    overview: RepositoryOverview?,
    repositoryUrl: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val strings = LocalGithubStrings.current
    val info = strings.Repository
    val colors = GlasmioTheme.colors
    val listState = rememberLazyListState()
    val edgeProgress = rememberGlasmioHeaderScrollEdge(listState)
    GlasmioBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        title = info.RepositoryInfo,
        skipPartiallyExpanded = false,
        topBarBlurProgress = edgeProgress,
        topBar = {
            GlasmioSheetTopToolbar(
                title = info.RepositoryInfo,
                navigation = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        GithubIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        GithubIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
                    }
                },
            )
        },
    ) {
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    start = 12.dp,
                    top = sheetPadding.calculateTopPadding() + 12.dp,
                    end = 12.dp,
                    bottom = sheetPadding.calculateBottomPadding() + 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repository?.description?.takeIf(String::isNotBlank)?.let { description ->
                item("description") {
                    GithubSection(info.Description) {
                        Text(
                            text = description,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = GlasmioTheme.textStyles.body,
                            color = colors.content,
                        )
                    }
                }
            }
            val owner = repository?.owner
            val login = owner?.login?.takeIf(String::isNotBlank)
            val parent = overview?.forkParent
            if (login != null || parent != null) {
                item("ownership") {
                    GithubSection(info.Ownership) {
                        if (login != null) {
                            GlasmioListRow(
                                title = login,
                                detail = info.Owner,
                                leading = {
                                    GithubAvatar(owner.avatarUrl, login, owner.type, 24.dp)
                                },
                                showTopSeparator = false,
                                onClick = {
                                    val path =
                                        if (owner.type == "Organization") "orgs/$login" else login
                                    onOpenLink("https://github.com/$path")
                                },
                            )
                        }
                        if (parent != null) {
                            GlasmioListRow(
                                title = parent.login,
                                detail = info.ForkedFrom,
                                leading = {
                                    GithubAvatar(
                                        parent.avatarUrl,
                                        parent.login,
                                        parent.ownerType,
                                        24.dp,
                                    )
                                },
                                showTopSeparator = login != null,
                                onClick = { onOpenLink(parent.url) },
                            )
                        }
                    }
                }
            }
            item("repository-link") {
                GithubSection(info.RepositoryLink) {
                    GlasmioListRow(
                        title = repositoryUrl,
                        showTopSeparator = false,
                        onClick = onShare,
                    )
                }
            }
        }
    }
}
