package moe.yumeyuka.yumehub.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import moe.yumeyuka.yumehub.ui.R

/** 编辑器共用 JetBrains Mono Regular；未覆盖的中文字形由 Android 系统字体回退。 */
public val EditorFontFamily: FontFamily =
    FontFamily(Font(R.font.jetbrains_mono_regular, weight = FontWeight.Normal))
