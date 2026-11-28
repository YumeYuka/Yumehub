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
import moe.yumeyuka.yumehub.ui.component.AppBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.data.api.dto.RepositoryDto
import moe.yumeyuka.yumehub.data.repository.RepositoryOverview
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.UserAvatar
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.ListSection

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
    val strings = LocalAppStrings.current
    val info = strings.Repository
    val colors = GlasmioTheme.colors
    val listState = rememberLazyListState()
    AppBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        title = info.RepositoryInfo,
        skipPartiallyExpanded = false,
        topBar = {
            GlasmioTopBar(
                title = info.RepositoryInfo,
                navigation = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        AppIcon(Lucide.X, contentDescription = strings.Common.Close)
                    }
                },
                actions = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        AppIcon(Lucide.Check, contentDescription = strings.Common.Confirm)
                    }
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repository?.description?.takeIf(String::isNotBlank)?.let { description ->
                item("description") {
                    ListSection(info.Description) {
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
                    ListSection(info.Ownership) {
                        if (login != null) {
                            GlasmioListRow(
                                title = login,
                                detail = info.Owner,
                                leading = {
                                    UserAvatar(owner.avatarUrl, login, owner.type, 24.dp)
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
                                    UserAvatar(
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
                ListSection(info.RepositoryLink) {
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
