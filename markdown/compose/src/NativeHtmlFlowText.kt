package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil

/** 用真实行盒决定窄列结束的位置；段落剩余部分在同一次测量中恢复全宽。 */
@Composable
internal fun NativeHtmlFlowText(
    text: AnnotatedString,
    style: TextStyle,
    exclusion: NativeHtmlTextExclusion,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val narrowWidth = width - exclusion.width
        // 浮图占满行宽时，正文直接落到图片下方，不能挤成逐字的 1px 列。
        if (narrowWidth <= 0) {
            val body =
                subcompose("below") { BasicText(text, style = style) }
                    .single()
                    .measure(Constraints.fixedWidth(width))
            layout(width, exclusion.height + body.height) { body.place(0, exclusion.height) }
        } else {
            val measured =
                measurer.measure(
                    text = text,
                    style = style,
                    constraints = Constraints.fixedWidth(narrowWidth),
                )
            val lineCount =
                (0 until measured.lineCount).count { measured.getLineTop(it) < exclusion.height }
            val split =
                if (lineCount < measured.lineCount) measured.getLineStart(lineCount)
                else text.length
            // 换行符已由上下两个文本块的边界实现，不能在前块再生成一个空行。
            val prefixEnd =
                if (split < text.length && split > 0 && text[split - 1] == '\n') split - 1
                else split
            val prefix =
                subcompose("beside") { BasicText(text.subSequence(0, prefixEnd), style = style) }
                    .single()
                    .measure(Constraints.fixedWidth(narrowWidth))
            val suffix =
                if (split < text.length)
                    subcompose("after") {
                            BasicText(text.subSequence(split, text.length), style = style)
                        }
                        .single()
                        .measure(Constraints.fixedWidth(width))
                else null
            val suffixTop =
                maxOf(
                    prefix.height,
                    if (suffix != null) ceil(measured.getLineTop(lineCount)).toInt() else 0,
                )
            layout(width, suffixTop + (suffix?.height ?: 0)) {
                prefix.place(if (exclusion.right) 0 else exclusion.width, 0)
                suffix?.place(0, suffixTop)
            }
        }
    }
}
