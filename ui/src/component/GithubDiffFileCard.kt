package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffUnavailableReason
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubDiffFileHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent

/**
 * Only expanded identities are saved; neither list position nor the patch text belongs in saved
 * state.
 */
@Stable
class GithubDiffExpansions internal constructor() {
    private val expanded = mutableStateMapOf<String, String>()

    internal fun contains(path: String, revision: String) = expanded[path] == revision

    internal fun toggle(path: String, revision: String) {
        if (contains(path, revision)) expanded.remove(path) else expanded[path] = revision
    }

    internal companion object {
        val Saver =
            listSaver<GithubDiffExpansions, String>(
                save = { state ->
                    buildList {
                        state.expanded.forEach { (path, version) ->
                            add(path)
                            add(version)
                        }
                    }
                },
                restore = { values ->
                    GithubDiffExpansions().apply {
                        for (index in values.indices step 2) expanded[values[index]] =
                            values[index + 1]
                    }
                },
            )
    }
}

@Composable
fun rememberGithubDiffExpansions(revision: String?): GithubDiffExpansions =
    key(revision) {
        rememberSaveable(saver = GithubDiffExpansions.Saver) { GithubDiffExpansions() }
    }

/** Expanding is a user action. A remounted or long patch is laid out at its actual height. */
@Composable
fun GithubDiffFileCard(
    file: PullRequestFileDto?,
    expansions: GithubDiffExpansions,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val strings = LocalGithubStrings.current.Repository
    val expansionStrings = LocalGithubStrings.current.EditorMenu
    val diff = file?.structuredDiff
    val revision =
        remember(diff?.id, diff?.baseCommitOid, diff?.headCommitOid) {
            if (diff == null) "unavailable"
            else "${diff.baseCommitOid}:${diff.headCommitOid}:${diff.id}"
        }
    val expanded = file != null && expansions.contains(file.filename, revision)
    val interactionSource = remember { MutableInteractionSource() }
    val cardModifier =
        if (file == null) modifier.clearAndSetSemantics {}
        else
            modifier
                .semantics {
                    stateDescription =
                        if (expanded) expansionStrings.Expanded else expansionStrings.Collapsed
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (expanded) strings.CollapseDiff else strings.ExpandDiff,
                    onClick = { expansions.toggle(file.filename, revision) },
                )
    GithubDiffCardFrame(modifier = cardModifier) {
        GithubLoadingContent(
            loading = file == null,
            placeholder = { GithubDiffFileHeaderPlaceholder() },
        ) {
            if (file != null) DisableSelection { GithubDiffFileHeader(file = file) }
        }
        if (file != null && expanded) {
            Column(Modifier.fillMaxWidth()) {
                DisableSelection {
                    file.previousFilename?.let { previous ->
                        Text(
                            text = strings.PreviousPath(previous),
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            style = GlasmioTheme.textStyles.footnote,
                            color = GlasmioTheme.colors.secondaryContent,
                        )
                    }
                }
                if (diff == null || diff.unavailableReason != null) {
                    DisableSelection {
                        Text(
                            text =
                                when (diff?.unavailableReason) {
                                    StructuredDiffUnavailableReason.Binary -> strings.DiffBinary
                                    StructuredDiffUnavailableReason.LargeDiff ->
                                        strings.DiffTooLarge
                                    StructuredDiffUnavailableReason.Submodule ->
                                        strings.DiffSubmodule
                                    StructuredDiffUnavailableReason.UnsupportedLineType ->
                                        strings.DiffUnsupported
                                    StructuredDiffUnavailableReason.InvalidLines ->
                                        strings.DiffInvalid
                                    else -> strings.DiffUnavailable
                                },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = GlasmioTheme.textStyles.footnote,
                            color = GlasmioTheme.colors.secondaryContent,
                        )
                    }
                } else {
                    GithubDiffViewer(
                        diff = diff,
                        modifier = Modifier.fillMaxWidth(),
                        language = githubCodeLanguageForFilename(file.filename),
                    )
                }
                footer()
            }
        }
    }
}

@Composable
fun GithubDiffCardFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GithubReadingGroup(modifier = modifier, content = content)
}
