package moe.yumeyuka.yumehub.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.lyricist.Lyricist
import cafe.adriel.lyricist.ProvideStrings
import cafe.adriel.lyricist.rememberStrings
import java.util.Locale

/** Compose entry point: read the tree from the composition, not through per-string getters. */
public val LocalAppStrings: ProvidableCompositionLocal<AppStrings> = staticCompositionLocalOf {
    ZhHansStrings
}

private val stringsByTag: Map<String, AppStrings> =
    mapOf(
        "zh" to ZhHansStrings,
        "zh-cn" to ZhHansStrings,
        "zh-hans" to ZhHansStrings,
        "zh-sg" to ZhHansStrings,
        "en" to EnStrings,
    )

public fun stringsFor(languageTag: String): AppStrings {
    val lower = languageTag.lowercase()
    return stringsByTag[lower] ?: stringsByTag[lower.substringBefore('-')] ?: ZhHansStrings
}

public object AppLocaleManager {
    @Volatile
    public var activeStrings: AppStrings = stringsFor(Locale.getDefault().toLanguageTag())
        private set

    private val languageTagState = mutableStateOf(Locale.getDefault().toLanguageTag().lowercase())

    public val currentLanguageTag: String
        get() = languageTagState.value

    public fun updateLocale(languageTag: String) {
        val normalized = languageTag.lowercase()
        activeStrings = stringsFor(normalized)
        if (languageTagState.value != normalized) languageTagState.value = normalized
    }
}

/** Non-composable entry point for notifications, view models and other background code. */
public object CurrentAppStrings {
    public val current: AppStrings
        get() = AppLocaleManager.activeStrings
}

@Composable
public fun rememberAppStrings(
    defaultLanguageTag: String = "zh-cn",
    currentLanguageTag: String = AppLocaleManager.currentLanguageTag,
): Lyricist<AppStrings> = rememberStrings(stringsByTag, defaultLanguageTag, currentLanguageTag)

@Composable
public fun ProvideAppStrings(
    lyricist: Lyricist<AppStrings> = rememberAppStrings(),
    content: @Composable () -> Unit,
) {
    ProvideStrings(lyricist, LocalAppStrings, content)
}
