package moe.yumeyuka.yumehub.ui.platform

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import moe.yumeyuka.yumehub.locale.AppLanguage
import moe.yumeyuka.yumehub.locale.GithubLocaleManager

object AppLanguageManager {
    fun current(context: Context): AppLanguage {
        val language =
            localeManager(context).applicationLocales[0]?.language ?: return AppLanguage.System
        return AppLanguage.entries.firstOrNull { it.languageTag.substringBefore('-') == language }
            ?: AppLanguage.System
    }

    fun apply(context: Context, language: AppLanguage) {
        localeManager(context).applicationLocales =
            if (language == AppLanguage.System) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(language.languageTag)
        syncLocale(language)
    }

    fun sync(context: Context) {
        syncLocale(current(context))
    }

    private fun syncLocale(language: AppLanguage) {
        val locale = resolveLocale(language)
        Locale.setDefault(locale)
        GithubLocaleManager.updateLocale(locale.toLanguageTag().lowercase())
    }

    private fun resolveLocale(language: AppLanguage): Locale =
        when (language) {
            AppLanguage.System -> systemLocale()
            AppLanguage.Chinese -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.English -> Locale.ENGLISH
        }

    private fun localeManager(context: Context): LocaleManager =
        requireNotNull(context.getSystemService(LocaleManager::class.java))
}

private fun systemLocale(): Locale =
    Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
