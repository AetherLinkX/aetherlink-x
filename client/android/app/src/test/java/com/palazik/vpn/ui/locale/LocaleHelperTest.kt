package com.palazik.vpn.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleHelperTest {
    @Test
    fun interfaceIsRussianForEverySystemLocale() {
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("ru"))
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("RU"))
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("en"))
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag("fr"))
        assertEquals(AppLanguage.RUSSIAN, LocaleHelper.languageForSystemTag(null))
    }
}
