package com.palazik.vpn.ui.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App UI language. The app always forces one of these (no system auto-detect). */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    RUSSIAN("ru");

    companion object {
        /** English is the default language. */
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: ENGLISH
    }
}

/**
 * Applies the user-chosen UI language by wrapping a base [Context] with the matching
 * locale. Persistence lives in the same "palazik_theme" prefs the theme uses, so it can
 * be read from `attachBaseContext` before Hilt/the ViewModel are available.
 */
object LocaleHelper {

    private const val THEME_PREFS = "palazik_theme"
    private const val KEY_LANGUAGE = "app_language"

    fun savedLanguage(context: Context): AppLanguage {
        val raw = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.ENGLISH.name)
        return AppLanguage.fromName(raw)
    }

    fun persistLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    /** Wrap [base] so resources resolve in the saved language (defaults to English). */
    fun wrap(base: Context): Context {
        val language = savedLanguage(base)
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
