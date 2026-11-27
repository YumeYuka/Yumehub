package moe.yumeyuka.yumehub.feature.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.yumeyuka.yumehub.ui.component.GithubBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.ui.GlasmioTopBar
import io.github.yumeyucca.lucide.GitBranch
import io.github.yumeyucca.lucide.Lucide
import io.github.yumeyucca.lucide.X
import moe.yumeyuka.yumehub.locale.GithubStrings_Repository
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.GithubIcon
import moe.yumeyuka.yumehub.ui.component.GithubSelectionList
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent
import moe.yumeyuka.yumehub.ui.component.placeholders.TitleRowPlaceholder

internal fun repositoryRefLabel(ref: String, strings: GithubStrings_Repository): String =
    when {
        ref.startsWith("refs/heads/") -> strings.BranchLabel(ref.removePrefix("refs/heads/"))
        ref.startsWith("refs/tags/") -> strings.TagLabel(ref.removePrefix("refs/tags/"))
        else -> ref
    }

@Composable
internal fun RepositoryRefSelector(
    refs: RepositoryLoad<List<String>>,
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    onRetry: () -> Unit,
) {
    RepositoryRefField(
        label = LocalGithubStrings.current.Repository.Refs,
        value = selected,
        refs = refs,
        enabled = enabled,
        onUpdate = onSelected,
        onLoadRefs = onRetry,
    )
}

@Composable
private fun RepositoryRefField(
    label: String,
    value: String,
    refs: RepositoryLoad<List<String>>,
    enabled: Boolean,
    onUpdate: (String) -> Unit,
    onLoadRefs: () -> Unit,
) {
    val strings = LocalGithubStrings.current.Repository
    val common = LocalGithubStrings.current.Common
    var choosing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val failure =
        when (refs) {
            is RepositoryLoad.Error -> refs.message
            is RepositoryLoad.Ready -> refs.refreshError
            RepositoryLoad.Loading -> null
        }
    GlasmioListRow(
        title = label,
        subtitle =
            value.takeIf(String::isNotBlank)?.let { repositoryRefLabel(it, strings) }
                ?: strings.ChooseRef,
        systemName = null,
        leading = { GithubIcon(Lucide.GitBranch, contentDescription = null, size = 20.dp) },
        onClick =
            if (enabled)
                ({
                    query = ""
                    onLoadRefs()
                    choosing = true
                })
            else null,
    )
    GithubBottomSheet(
        visible = choosing && enabled,
        onDismissRequest = { choosing = false },
        title = label,
        topBar = {
            GlasmioTopBar(
                title = label,
                actions = {
                    GlasmioIconButton(onClick = { choosing = false }) {
                        GithubIcon(Lucide.X, contentDescription = common.Close)
                    }
                },
            )
        },
        fitContent = true,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlasmioTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = strings.RefFilterPlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
            GithubLoadingContent(
                loading = refs is RepositoryLoad.Loading,
                placeholder = { GlasmioGroupedList { repeat(3) { TitleRowPlaceholder(it > 0) } } },
            ) {
                if (refs is RepositoryLoad.Ready) {
                    val choices = refs.value.filter { it.contains(query.trim(), ignoreCase = true) }
                    if (choices.isEmpty())
                        GlasmioGroupedList {
                            GlasmioListRow(
                                title =
                                    if (query.isBlank()) strings.RefsEmpty
                                    else strings.NoMatchingRefs,
                                systemName = null,
                            )
                        }
                    else
                        GithubSelectionList(
                            values = choices,
                            selectedValue = value,
                            label = { repositoryRefLabel(it, strings) },
                            onSelected = {
                                onUpdate(it)
                                choosing = false
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        )
                }
            }
            failure?.let { GlasmioGroupedList { RepositoryRetryRow(it, onLoadRefs) } }
        }
    }
}
