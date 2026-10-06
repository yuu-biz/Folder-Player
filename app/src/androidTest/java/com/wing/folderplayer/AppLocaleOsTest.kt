package com.wing.folderplayer

import android.app.LocaleManager
import android.os.Build
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.utils.AppLocale
import com.wing.folderplayer.utils.Strings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 13+: the language of the system settings (Settings > Apps > Folder Player Fork > Language) and the one
 * chosen inside the app are the same setting. Each direction is checked on what the user sees: the activity, texts
 * built outside it (`Strings.get`: notifications, errors) and the highlighted choice in Settings > Language.
 * The language is put back to "system default" afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AppLocaleOsTest : UiTestBase() {
    private val pkg get() = Fx.ctx.packageName
    private val osTags get() = Fx.ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()

    @Before fun requireApi33() = Assume.assumeTrue("per-app language needs Android 13+", Build.VERSION.SDK_INT >= 33)

    @After fun restoreSystemDefault() {
        if (Build.VERSION.SDK_INT >= 33) {
            Fx.shell("cmd locale set-app-locales $pkg")
            AppLocale.set(Fx.ctx, "")
        }
    }

    private fun assertSelected(tag: String) {
        toSettings()
        scrollTo("lang_$tag")
        node("lang_$tag").assertIsSelected()
    }


    @Test fun inAppChangeIsStoredAsTheSystemLanguageAndShownEverywhere() {
        toSettings()
        scrollTo("lang_fr"); click("lang_fr")
        until(15_000, "French from the app") { AppLocale.get(Fx.ctx) == "fr" && textExists("Réglages") }
        assertEquals("the system's per-app language follows", "fr", osTags)
        until(5_000, "texts outside the activity in French") { Strings.get(R.string.settings_permissions) == "Stockage et autorisations" }
        assertSelected("fr")
    }

    @Test fun systemSettingsChangeIsShownEverywhere() {
        Fx.shell("cmd locale set-app-locales $pkg --locales ja")
        until(20_000, "Japanese from the system settings") { osTags == "ja" && AppLocale.get(Fx.ctx) == "ja" }
        val t0 = System.currentTimeMillis()
        until(5_000, "texts outside the activity in Japanese") { Strings.get(R.string.settings_permissions) == "ストレージと権限" }
        Fx.log("AppLocaleOsTest: Strings.get followed the system language after ${System.currentTimeMillis() - t0} ms")
        toSettings()
        until(15_000, "activity in Japanese") { textExists("言語") }
        assertEquals("ストレージと権限", str(R.string.settings_permissions))
        assertSelected("ja")

        // The other direction: the system settings back to "System default" (the app must not bring Japanese back).
        Fx.shell("cmd locale set-app-locales $pkg")
        until(20_000, "system default from the system settings") { osTags.isEmpty() && AppLocale.get(Fx.ctx) == "" }
        until(5_000, "texts outside the activity back in the emulator language") { Strings.get(R.string.settings_permissions) == "Storage & permissions" }
        assertSelected("")
    }

    private fun scrollTo(tag: String) { runCatching { compose.onNodeWithTag(tag).performScrollTo() } }
}
