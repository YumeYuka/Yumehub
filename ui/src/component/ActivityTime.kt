package moe.yumeyuka.yumehub.ui.component

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 将服务端时间显示为设备所在时区的简洁日期；异常值保留原文。 */
fun formatActivityTime(value: String): String = runCatching {
    ActivityTimeFormat.format(Instant.parse(value))
}
    .getOrDefault(value)

private val ActivityTimeFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
