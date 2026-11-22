package moe.yumeyuka.markdown.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/** 对齐 WebView textZoom：字号与行高线性放大，CSS 盒子的 em 长度保持原值。 */
internal class HtmlCssTypography(
    private val styles: HtmlStyles,
    private val density: Density,
) {
    val textZoom: Float = density.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f

    fun bodyStyle(): TextStyle = resolve(styles.bodyStyle())

    fun headingStyle(level: Int): TextStyle = resolve(styles.headingStyle(level))

    fun codeStyle(): TextStyle = resolve(styles.codeStyle())

    fun kbdStyle(): TextStyle = resolve(styles.kbdStyle())

    fun suggestionStyle(): TextStyle = resolve(styles.suggestionStyle())

    fun resolve(source: TextStyle): TextStyle {
        val cssFontSize = source.fontSize.cssFontValue()
        val fontSize = fontSize(source.fontSize)
        val lineHeight =
            when {
                source.lineHeight.isEm -> source.lineHeight
                source.lineHeight.isSp && cssFontSize > 0f ->
                    (source.lineHeight.value / cssFontSize).em
                else -> TextUnit.Unspecified
            }
        return source.copy(fontSize = fontSize, lineHeight = lineHeight)
    }

    fun fontSize(cssSize: TextUnit): TextUnit =
        with(density) { (cssSize.cssFontValue() * this.density * textZoom).toSp() }

    fun scaleFontSize(resolvedSize: TextUnit, factor: Float): TextUnit =
        with(density) { (resolvedSize.toPx() * factor).toSp() }

    fun emSpacing(resolvedFontSize: TextUnit, factor: Float): Dp =
        with(density) { (resolvedFontSize.toPx() / textZoom * factor).toDp() }

    private fun TextUnit.cssFontValue(): Float =
        when {
            isSp -> value
            isEm -> styles.typography.bodyFontSize.value * value
            else -> styles.typography.bodyFontSize.value
        }
}
