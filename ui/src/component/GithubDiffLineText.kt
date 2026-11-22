package moe.yumeyuka.yumehub.ui.component

/** 对齐官方 f1a.L/M：只移除一个协议标记，保留开头空格和剩余运算符。 */
internal fun githubStructuredDiffCodeText(text: String): String =
    when (text.firstOrNull()) {
        '+',
        '-',
        '~' -> text.substring(1)
        else -> text
    }
