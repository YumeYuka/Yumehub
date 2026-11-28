package moe.yumeyuka.yumehub.ui.component

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** 从首行基线定位文字主体；英文使用大写字高，避免下伸字母或第二行拉低图标。 */
@Composable
fun titleOpticalCenter(title: String, style: TextStyle): (Measured) -> Int {
    val face =
        LocalFontFamilyResolver.current
            .resolve(
                style.fontFamily,
                style.fontWeight ?: FontWeight.Normal,
            )
            .value as Typeface
    val size = with(LocalDensity.current) { style.fontSize.toPx() }
    val first = title.firstOrNull(Char::isLetter)
    val sample =
        when (first?.let { Character.UnicodeScript.of(it.code) }) {
            null,
            Character.UnicodeScript.LATIN -> "H"
            Character.UnicodeScript.HAN -> "国"
            else -> first.toString()
        }
    return remember(face, size, sample) {
        val bounds = Rect()
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            getTextBounds(sample, 0, sample.length, bounds)
        }
        val offset = bounds.centerY()
        val center: (Measured) -> Int = { it[FirstBaseline] + offset }
        center
    }
}
