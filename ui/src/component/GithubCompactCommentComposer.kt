package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.yumeyucca.lucide.*
import moe.yumeyuka.yumehub.ui.component.text.GlasmioEditableText
import moe.yumeyuka.yumehub.ui.theme.GithubEditorFontFamily

/** 紧凑评论输入；调用方负责展开编辑器、提交及账号切换时的状态隔离。 */
@Composable
fun GithubCompactCommentComposer(
    source: String,
    onSourceChange: (String) -> Unit,
    onExpand: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitEnabled: Boolean = true,
    submitting: Boolean = false,
    error: String? = null,
    placeholder: String = "撰写评论",
    onExpandState: ((MarkdownEditorState) -> Unit)? = null,
    editorState: MarkdownEditorState? = null,
) {
    val state = editorState ?: remember { MarkdownEditorState(source) }
    val editable = enabled && !submitting
    val canSubmit = editable && submitEnabled
    val selectionMenu = rememberGithubMarkdownSelectionModifier(state, editable, onSourceChange)
    val colors = GlasmioTheme.colors
    val editorStyle =
        GlasmioTheme.textStyles.body.copy(
            color = colors.content,
            fontFamily = GithubEditorFontFamily,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Start,
        )

    // 只跟随外部正文变化；回传的相同正文不能覆盖选区或 IME 组合态。
    LaunchedEffect(source) { state.syncExternal(source) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // GlasmioGroupedList 的圆角是 26dp；40dp 圆按钮内缩 6dp 后与外框圆角同心。
        val actionSize = 40.dp
        val actionInset = 26.dp - actionSize / 2
        val actionRailWidth = actionSize + actionInset * 2
        GlasmioGroupedList {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                top = 14.dp,
                                end = actionRailWidth + 8.dp,
                                bottom = 14.dp,
                            ),
                ) {
                    if (state.value.text.isEmpty()) {
                        BasicText(
                            text = placeholder,
                            style = editorStyle.copy(color = colors.secondaryContent),
                        )
                    }
                    GlasmioEditableText(
                        value = state.value,
                        onValueChange = { next ->
                            if (editable) {
                                val previous = state.value.text
                                state.edit(next)
                                if (previous != state.value.text) {
                                    onSourceChange(state.value.text)
                                }
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 72.dp, max = 144.dp)
                                .then(selectionMenu),
                        enabled = editable,
                        textStyle = editorStyle,
                        singleLine = false,
                        maxLines = 8,
                    )
                }
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .padding(actionInset)
                            .size(actionSize)
                            .clickable(
                                enabled = editable,
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                                onClickLabel = "展开编辑器",
                                onClick = {
                                    if (editable) {
                                        val expandState = onExpandState
                                        if (expandState != null) expandState(state) else onExpand()
                                    }
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    GithubIcon(
                        Lucide.Maximize2,
                        contentDescription = "展开编辑器",
                        size = 16.dp,
                        tint = colors.secondaryContent,
                    )
                }
                GithubSendButton(
                    onClick = { if (canSubmit) onSubmit() },
                    enabled = canSubmit,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(actionInset),
                    size = actionSize,
                    contentDescription = if (submitting) "正在发送评论" else "发送评论",
                )
            }
        }
        if (!error.isNullOrBlank()) {
            BasicText(
                text = error,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = GlasmioTheme.textStyles.footnote.copy(color = colors.secondaryContent),
            )
        }
    }
}
