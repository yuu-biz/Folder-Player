package com.wing.folderplayer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the language handling: system tag → app language, and when an old stored choice is handed over. */
class AppLocaleTest {
    @Test fun systemTagsMapToTheAppsLanguages() {
        assertEquals("", AppLocale.matchTag(""))
        for ((tag, expected) in listOf(
            "en" to "en", "en-US" to "en", "en-GB" to "en",
            "fr" to "fr", "fr-FR" to "fr", "fr-CA" to "fr", "it-IT" to "it", "ja" to "ja", "ja-JP" to "ja",
            "zh-CN" to "zh-CN", "zh-Hans" to "zh-CN", "zh-Hans-CN" to "zh-CN", "zh-SG" to "zh-CN", "zh" to "zh-CN",
            "zh-TW" to "zh-TW", "zh-Hant" to "zh-TW", "zh-Hant-TW" to "zh-TW", "zh-HK" to "zh-TW", "zh-Hant-HK" to "zh-TW",
        )) assertEquals(tag, expected, AppLocale.matchTag(tag))
    }

    @Test fun aLanguageWithoutTranslationIsSystemDefault() {
        assertEquals("", AppLocale.matchTag("de-DE"))
        assertEquals("", AppLocale.matchTag("ko"))
    }

    @Test fun everyOfferedLanguageRoundTrips() {
        for (tag in AppLocale.TAGS) assertEquals("'$tag'", tag, AppLocale.matchTag(tag))
    }

    @Test fun anOldChoiceIsHandedToTheSystemOnlyWhenTheSystemHasNone() {
        assertTrue(AppLocale.shouldHandOver("ja", systemLanguageEmpty = true))
        assertTrue(AppLocale.shouldHandOver("zh-TW", systemLanguageEmpty = true))
        assertFalse("the system already has a choice", AppLocale.shouldHandOver("ja", systemLanguageEmpty = false))
        assertFalse("nothing stored (system default)", AppLocale.shouldHandOver("", systemLanguageEmpty = true))
        assertFalse("not a language the app offers", AppLocale.shouldHandOver("xx", systemLanguageEmpty = true))
    }
}
