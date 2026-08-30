package com.palazik.vpn.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleHelperTest {
    @Test
    fun russianSystemLocaleSelectsRussian() {
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("ru"))
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("RU"))
    }

    @Test
    fun englishAndUnsupportedLocalesSelectEnglish() {
        assertEquals(AppLanguage.ENGLISH, LocaleHelper.languageForSystemTag("en"))
        assertEquals(AppLanguage.ENGLISH, LocaleHelper.languageForSystemTag("fr"))
        assertEquals(AppLanguage.ENGLISH, LocaleHelper.languageForSystemTag(null))
    }
}
