package moe.yumeyuka.yumehub.ui.component

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import moe.yumeyuka.yumehub.locale.GithubStrings_Notifications

fun githubRelativeTime(value: String?, strings: GithubStrings_Notifications): String? {
    if (value == null) return null
    val instant =
        try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            return null
        }
    val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> strings.JustNow
        minutes < 60 -> strings.MinutesAgo(minutes)
        minutes < 1440 -> strings.HoursAgo(minutes / 60)
        else -> strings.DaysAgo(minutes / 1440)
    }
}
