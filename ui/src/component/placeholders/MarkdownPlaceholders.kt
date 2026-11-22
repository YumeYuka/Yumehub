package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme

/**
 * Markdown 正文和编辑器预览共用的正文骨架。
 *
 * 骨架保留标题、段落、引用和列表的大致排版关系，避免官方 HTML 或原生文档完成首帧前 只显示一块空白。它不承载文档语义，也不使用闪烁动画；真实内容出现时由调用方交叉淡入。
 */
@Composable
fun GithubMarkdownPlaceholder(modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = PlaceholderTokens.ReadmeMinHeight),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        MarkdownPlaceholderHeading()
        MarkdownPlaceholderParagraph(
            lineFractions = listOf(0.94f, 0.86f, 0.58f),
        )
        if (!compact) {
            MarkdownPlaceholderQuote()
            MarkdownPlaceholderList()
            MarkdownPlaceholderParagraph(
                lineFractions = listOf(0.88f, 0.96f, 0.74f, 0.42f),
            )
            MarkdownPlaceholderCodeBlock()
        }
    }
}

@Composable
private fun MarkdownPlaceholderHeading() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarkdownPlaceholderBlock(Modifier.weight(0.7f), height = 20.dp)
        MarkdownPlaceholderBlock(Modifier.weight(0.18f), height = 20.dp)
        MarkdownPlaceholderBlock(Modifier.weight(0.1f), height = 20.dp)
    }
}

@Composable
private fun MarkdownPlaceholderParagraph(lineFractions: List<Float>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lineFractions.forEach { fraction ->
            MarkdownPlaceholderBlock(
                modifier = Modifier.fillMaxWidth(fraction),
                height = 14.dp,
            )
        }
    }
}

@Composable
private fun MarkdownPlaceholderQuote() {
    Row(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(4.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(PlaceholderTokens.BlockCorner))
                .background(markdownPlaceholderColor()),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.9f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.78f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.5f), height = 14.dp)
        }
    }
}

@Composable
private fun MarkdownPlaceholderList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) { rowIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MarkdownPlaceholderBlock(
                    modifier = Modifier.width(if (rowIndex % 2 == 0) 20.dp else 12.dp),
                    height = 20.dp,
                )
                Box(Modifier.weight(1f)) {
                    MarkdownPlaceholderBlock(
                        modifier = Modifier.fillMaxWidth(if (rowIndex == 3) 0.52f else 0.82f),
                        height = 14.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownPlaceholderCodeBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().height(128.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(4.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(PlaceholderTokens.BlockCorner))
                .background(markdownPlaceholderColor()),
        )
        Column(
            modifier = Modifier.weight(1f).padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.56f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.9f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.72f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.42f), height = 14.dp)
            MarkdownPlaceholderBlock(Modifier.fillMaxWidth(0.66f), height = 14.dp)
        }
    }
}

@Composable
private fun MarkdownPlaceholderBlock(modifier: Modifier, height: Dp) {
    Spacer(
        modifier
            .height(height)
            .clip(RoundedCornerShape(PlaceholderTokens.BlockCorner))
            .background(markdownPlaceholderColor()),
    )
}

@Composable
private fun markdownPlaceholderColor() =
    GlasmioTheme.colors.content.copy(alpha = PlaceholderTokens.ContentAlpha)
