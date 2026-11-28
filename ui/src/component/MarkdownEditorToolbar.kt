package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.yumeyucca.lucide.*

val LocalEditorFocusReady = staticCompositionLocalOf { true }

@Composable
fun MarkdownEditorToolbar(
    hasSelection: Boolean,
    enabled: Boolean,
    onAction: (MarkdownEditAction) -> Unit,
    onCode: () -> Unit,
    modifier: Modifier = Modifier,
    onEmoji: ((String) -> Unit)? = null,
    actionSize: Dp = EditorDockActionSize,
) {
    val actions =
        if (hasSelection) MarkdownEditAction.entries
        else
            listOf(
                MarkdownEditAction.CodeBlock,
                MarkdownEditAction.Quote,
                MarkdownEditAction.BulletedList,
                MarkdownEditAction.NumberedList,
                MarkdownEditAction.TaskList,
                MarkdownEditAction.Heading,
                MarkdownEditAction.Bold,
                MarkdownEditAction.Italic,
                MarkdownEditAction.Strike,
                MarkdownEditAction.InlineCode,
                MarkdownEditAction.Link,
            )
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            val (icon, label) =
                when (action) {
                    MarkdownEditAction.Bold -> Lucide.Bold to "加粗"
                    MarkdownEditAction.Italic -> Lucide.Italic to "斜体"
                    MarkdownEditAction.Strike -> Lucide.Strikethrough to "删除线"
                    MarkdownEditAction.InlineCode -> Lucide.Code to "行内代码"
                    MarkdownEditAction.Link -> Lucide.Link to "链接"
                    MarkdownEditAction.Quote -> Lucide.Quote to "引用"
                    MarkdownEditAction.BulletedList -> Lucide.List to "无序列表"
                    MarkdownEditAction.NumberedList -> Lucide.ListOrdered to "有序列表"
                    MarkdownEditAction.TaskList -> Lucide.ListChecks to "任务列表"
                    MarkdownEditAction.Heading -> Lucide.Heading to "标题"
                    MarkdownEditAction.CodeBlock -> Lucide.SquareCode to "代码块"
                }
            GlasmioIconButton(
                onClick = {
                    if (action == MarkdownEditAction.CodeBlock) onCode() else onAction(action)
                },
                enabled = enabled,
                // 与外层胶囊使用同一不透明兜底，避免透明黑被强制转成纯黑。
                navigationSurfaceColor =
                    if (GlasmioTheme.appearance.reduceTransparency) GlasmioTheme.colors.container
                    else Color.Transparent,
                sampleBackdrop = false,
                size = actionSize,
            ) {
                AppIcon(
                    icon,
                    contentDescription = label,
                    size = 20.dp,
                    tint = GlasmioTheme.colors.content,
                )
            }
        }
    }
}

@Composable
internal fun MarkdownCodeToolbar(
    enabled: Boolean,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlasmioIconButton(
            onClick = onOutdent,
            enabled = enabled,
            sampleBackdrop = false,
            size = EditorDockActionSize,
        ) {
            AppIcon(
                Lucide.ListIndentDecrease,
                contentDescription = "减少缩进",
                size = 20.dp,
                tint = GlasmioTheme.colors.content,
            )
        }
        GlasmioIconButton(
            onClick = onIndent,
            enabled = enabled,
            sampleBackdrop = false,
            size = EditorDockActionSize,
        ) {
            AppIcon(
                Lucide.ListIndentIncrease,
                contentDescription = "增加缩进",
                size = 20.dp,
                tint = GlasmioTheme.colors.content,
            )
        }
        GlasmioIconButton(
            onClick = onFinish,
            enabled = enabled,
            sampleBackdrop = false,
            size = EditorDockActionSize,
        ) {
            BasicText(
                "完成代码",
                style = GlasmioTheme.textStyles.body.copy(color = GlasmioTheme.colors.content),
            )
        }
    }
}
