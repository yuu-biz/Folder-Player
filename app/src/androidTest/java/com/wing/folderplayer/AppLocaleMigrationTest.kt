package com.wing.folderplayer

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.utils.AppLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 13+: a language an older version stored in the app's preference (Android 12 and earlier, or before the system
 * language was the source of truth) is handed to the system once, and never overwrites a later system choice.
 */
@RunWith(AndroidJUnit4::class)
class AppLocaleMigrationTest {
    private val prefs get() = Fx.ctx.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
    private val os get() = Fx.ctx.getSystemService(LocaleManager::class.java)
    private val osTags get() = os.applicationLocales.toLanguageTags()

    @Before fun setUp() {
        Assume.assumeTrue("per-app language needs Android 13+", Build.VERSION.SDK_INT >= 33)
        os.applicationLocales = LocaleList.getEmptyLocaleList()
        prefs.edit { clear() } // as on first start after the update: old preference present, nothing migrated yet
    }

    @After fun tearDown() {
        if (Build.VERSION.SDK_INT >= 33) {
            os.applicationLocales = LocaleList.getEmptyLocaleList()
            prefs.edit { clear() }
        }
    }

    private fun storedByOldVersion(tag: String) = prefs.edit(commit = true) { putString("app_language", tag) }

    @Test fun storedLanguageIsHandedToTheSystemOnce() {
        storedByOldVersion("it")
        AppLocale.migrate(Fx.ctx)
        assertEquals("it", osTags)
        assertEquals("it", AppLocale.get(Fx.ctx))
    }

    @Test fun systemDefaultChosenLaterIsNotOverwrittenByTheOldPreference() {
        storedByOldVersion("it")
        AppLocale.migrate(Fx.ctx)
        assertEquals("it", osTags)
        // The user sets "System default" in the system settings; the app starts again (migrate runs at every start).
        os.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.migrate(Fx.ctx)
        assertEquals("", osTags)
        assertEquals("", AppLocale.get(Fx.ctx))
    }

    @Test fun aSystemChoiceAlreadyThereWinsOverTheOldPreference() {
        storedByOldVersion("it")
        os.applicationLocales = LocaleList.forLanguageTags("fr")
        AppLocale.migrate(Fx.ctx)
        assertEquals("fr", osTags)
        assertEquals("fr", AppLocale.get(Fx.ctx))
    }

    @Test fun noStoredLanguageLeavesTheSystemAlone() {
        AppLocale.migrate(Fx.ctx)
        assertEquals("", osTags)
        os.applicationLocales = LocaleList.forLanguageTags("ja")
        AppLocale.migrate(Fx.ctx)
        assertEquals("ja", osTags)
    }

    @Test fun systemTagsWithScriptAndRegionAreShownAsTheMatchingChoice() {
        os.applicationLocales = LocaleList.forLanguageTags("zh-Hant-TW")
        assertEquals("zh-TW", AppLocale.get(Fx.ctx))
        os.applicationLocales = LocaleList.forLanguageTags("fr-FR")
        assertEquals("fr", AppLocale.get(Fx.ctx))
    }
}
