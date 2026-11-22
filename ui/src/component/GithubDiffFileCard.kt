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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import moe.yumeyuka.yumehub.data.api.dto.PullRequestFileDto
import moe.yumeyuka.yumehub.data.api.dto.StructuredDiffUnavailableReason
import moe.yumeyuka.yumehub.locale.LocalGithubStrings
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubContentVisibility
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubDiffFileHeaderPlaceholder
import moe.yumeyuka.yumehub.ui.component.placeholders.GithubLoadingContent

/** 整卡点击切换展开；正文随可达高度渐进展开与收起，文件头与正文之间不额外绘制分隔线。 正文自带不透明背景，卡片按阅读分组同一圆角裁剪，避免展开后底部出现直角。 */
@Composable
fun GithubDiffFileCard(
    file: PullRequestFileDto?,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    val strings = LocalGithubStrings.current.Repository
    val expansionStrings = LocalGithubStrings.current.EditorMenu
    val diff = file?.structuredDiff
    var expanded by
        rememberSaveable(file?.filename, diff?.id, diff?.baseCommitOid, diff?.headCommitOid) {
            mutableStateOf(false)
        }
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
                    onClick = { expanded = !expanded },
                )
    GithubDiffCardFrame(modifier = cardModifier) {
        GithubLoadingContent(
            loading = file == null,
            placeholder = { GithubDiffFileHeaderPlaceholder() },
        ) {
            if (file != null) DisableSelection { GithubDiffFileHeader(file = file) }
        }
        GithubContentVisibility(visible = file != null && expanded) {
            Column(Modifier.fillMaxWidth()) {
                DisableSelection {
                    file?.previousFilename?.let { previous ->
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
                if (file != null) {
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
}

@Composable
fun GithubDiffCardFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GithubReadingGroup(modifier = modifier.clip(GithubReadingGroupShape), content = content)
}
