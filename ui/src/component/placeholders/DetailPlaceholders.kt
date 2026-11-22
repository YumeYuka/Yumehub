package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.component.GithubSectionHeader
import moe.yumeyuka.yumehub.ui.navigation.GithubDetailKind

@Composable
fun DetailTitlePlaceholder(compact: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextPlaceholder(height = if (compact) 16.dp else 22.dp, fraction = 0.86f)
        if (!compact) TextPlaceholder(height = 22.dp, fraction = 0.56f)
    }
}

@Composable
fun WorkDetailHeaderPlaceholder() {
    GlasmioGroupedList {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTilePlaceholder(32.dp, corner = 16.dp)
            AvatarPlaceholder(20.dp)
            TextPlaceholder(Modifier.width(84.dp), height = 14.dp, fraction = 1f)
            TextPlaceholder(Modifier.width(60.dp), height = 14.dp, fraction = 1f)
        }
    }
}

@Composable
fun CommitAuthorPlaceholder() {
    GlasmioGroupedList {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarPlaceholder(32.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextPlaceholder(height = 14.dp, fraction = 0.45f)
                    TextPlaceholder(height = 10.dp, fraction = 0.3f)
                }
            }
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.92f)
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.58f)
        }
    }
}

@Composable
fun DetailBodyPlaceholder(kind: GithubDetailKind) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(if (kind == GithubDetailKind.CommitFiles) 8.dp else 12.dp),
    ) {
        when (kind) {
            GithubDetailKind.Release -> {
                GithubReadingGroup {
                    GithubMarkdownPlaceholder(Modifier.padding(16.dp), compact = true)
                }
                GithubSectionHeader(LocalGithubStrings.current.Release.Artifacts)
                GlasmioGroupedList { ReleaseAssetPlaceholder(showTopSeparator = false) }
            }
            GithubDetailKind.Issue,
            GithubDetailKind.PullRequest -> {
                GithubReadingGroup {
                    GithubMarkdownPlaceholder(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        compact = true,
                    )
                }
                if (kind == GithubDetailKind.PullRequest) {
                    val strings = LocalGithubStrings.current.WorkDetail
                    Column {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GithubSectionHeader(strings.Changes)
                            GlasmioGroupedList { repeat(2) { DetailInformationRowPlaceholder() } }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GithubSectionHeader(strings.Status)
                            GlasmioGroupedList { repeat(3) { DetailInformationRowPlaceholder() } }
                        }
                    }
                }
                GithubSectionHeader(LocalGithubStrings.current.WorkDetail.Activity)
                repeat(2) {
                    Row(
                        Modifier.padding(start = 24.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AvatarPlaceholder(28.dp)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextPlaceholder(height = 14.dp, fraction = 0.55f)
                            TextPlaceholder(height = 12.dp, fraction = 0.78f)
                        }
                    }
                }
            }
            GithubDetailKind.Discussion -> {
                GithubReadingGroup {
                    GithubMarkdownPlaceholder(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        compact = true,
                    )
                }
                repeat(2) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AvatarPlaceholder(28.dp)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextPlaceholder(height = 14.dp, fraction = 0.55f)
                            TextPlaceholder(height = 12.dp, fraction = 0.78f)
                        }
                    }
                }
            }
            GithubDetailKind.CommitFiles ->
                repeat(4) { GithubReadingGroup { GithubDiffFileHeaderPlaceholder() } }
            GithubDetailKind.CommitInformation -> {
                val strings = LocalGithubStrings.current.Activity
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GithubSectionHeader(strings.Details)
                    GlasmioGroupedList { repeat(2) { DetailInformationRowPlaceholder() } }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GithubSectionHeader(strings.Parents)
                    GlasmioGroupedList { WorkspaceCommitPlaceholder() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GithubSectionHeader(strings.Related)
                    GlasmioGroupedList { DetailInformationRowPlaceholder() }
                }
            }
        }
    }
}

@Composable
private fun DetailInformationRowPlaceholder() {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTilePlaceholder(20.dp, corner = 4.dp)
        TextPlaceholder(Modifier.weight(1f), height = 14.dp, fraction = 0.65f)
        TextPlaceholder(Modifier.width(40.dp), height = 12.dp, fraction = 1f)
    }
}

@Composable
fun DetailToolbarPlaceholder(showTrailingAction: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTilePlaceholder(48.dp, corner = 24.dp)
        TextPlaceholder(
            modifier = Modifier.weight(1f),
            height = 44.dp,
            fraction = 1f,
            corner = 22.dp,
        )
        if (showTrailingAction) IconTilePlaceholder(48.dp, corner = 24.dp)
    }
}
