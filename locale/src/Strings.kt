package moe.yumeyuka.yumehub.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.lyricist.Lyricist
import cafe.adriel.lyricist.ProvideStrings
import cafe.adriel.lyricist.rememberStrings
import java.util.Locale

/** Compose entry point: read the tree from the composition, not through per-string getters. */
public val LocalGithubStrings: ProvidableCompositionLocal<GithubStrings> =
    staticCompositionLocalOf {
        ZhHansStrings
    }

private val stringsByTag: Map<String, GithubStrings> =
    mapOf(
        "zh" to ZhHansStrings,
        "zh-cn" to ZhHansStrings,
        "zh-hans" to ZhHansStrings,
        "zh-sg" to ZhHansStrings,
        "en" to EnStrings,
    )

public fun stringsFor(languageTag: String): GithubStrings {
    val lower = languageTag.lowercase()
    return stringsByTag[lower] ?: stringsByTag[lower.substringBefore('-')] ?: ZhHansStrings
}

public object GithubLocaleManager {
    @Volatile
    public var activeStrings: GithubStrings = stringsFor(Locale.getDefault().toLanguageTag())
        private set

    public fun updateLocale(languageTag: String) {
        activeStrings = stringsFor(languageTag)
    }
}

/** Non-composable entry point for notifications, view models and other background code. */
public object GithubTxt {
    public val current: GithubStrings
        get() = GithubLocaleManager.activeStrings
}

@Composable
public fun rememberGithubStrings(
    defaultLanguageTag: String = "zh-cn",
    currentLanguageTag: String = Locale.getDefault().toLanguageTag().lowercase(),
): Lyricist<GithubStrings> = rememberStrings(stringsByTag, defaultLanguageTag, currentLanguageTag)

@Composable
public fun ProvideGithubStrings(
    lyricist: Lyricist<GithubStrings> = rememberGithubStrings(),
    content: @Composable () -> Unit,
) {
    ProvideStrings(lyricist, LocalGithubStrings, content)
}
