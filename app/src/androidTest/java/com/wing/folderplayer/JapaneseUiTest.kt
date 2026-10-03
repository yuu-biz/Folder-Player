package com.wing.folderplayer

import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.utils.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Japanese UI: chosen from values-ja for a Japanese system locale, selectable in Settings (applied at once, kept after
 * a restart), without changing what the other languages show. Methods run in order, one process each
 * (instrument.sh run-each); `b1` checks what `a2` stored.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseUiTest : UiTestBase() {
    private fun titleFor(vararg tags: String): String {
        val config = Configuration(Fx.ctx.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags.joinToString(","))) }
        return Fx.ctx.createConfigurationContext(config).getString(R.string.settings_permissions)
    }

    @Test fun a1_systemLocaleSelectsValuesJa() {
        assertEquals("", AppLocale.get(Fx.ctx))
        // What Android resolves for the app when the system language is Japanese and no app language is set.
        assertEquals("ストレージと権限", titleFor("ja-JP"))
        assertEquals("ストレージと権限", titleFor("ja"))
        assertEquals("ストレージと権限", titleFor("ja-JP", "en-US"))
        // The other languages are unchanged.
        assertEquals("Storage & permissions", titleFor("en-US"))
        assertEquals("存储与权限", titleFor("zh-CN"))
        assertEquals("儲存空間與權限", titleFor("zh-TW"))
        assertEquals("Stockage et autorisations", titleFor("fr-FR"))
        assertEquals("Archiviazione e permessi", titleFor("it-IT"))
        // A language without resources falls back to English.
        assertEquals("Storage & permissions", titleFor("ko-KR"))
        val system = Resources.getSystem().configuration.locales[0]
        Fx.log("system locale $system: settings shows ${str(R.string.settings_permissions)}")
        if (system.language == "ja") {
            toSettings()
            until(10_000, "Japanese UI from the system locale") { textExists("言語") }
        }
    }

    @Test fun a2_selectingJapaneseAppliesImmediately() {
        toSettings()
        click("lang_ja")
        // The activity is recreated in Japanese.
        until(15_000, "Japanese UI") { AppLocale.get(Fx.ctx) == "ja" && textExists("言語") }
        assertEquals("ストレージと権限", str(R.string.settings_permissions))
        assertEquals(Locale.JAPANESE.language, compose.activity.resources.configuration.locales[0].language)
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("ja", Fx.ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        }
    }

    @Test fun b1_japaneseKeptAfterRestartThenBackToSystem() {
        assertEquals("ja", AppLocale.get(Fx.ctx))
        toSettings()
        until(10_000, "Japanese UI after restart") { textExists("言語") }
        assertEquals("ストレージと権限", str(R.string.settings_permissions))
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("ja", Fx.ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        }
        // Back to the system language for the other suites.
        click("lang_")
        until(15_000, "system language") { AppLocale.get(Fx.ctx) == "" }
        Thread.sleep(1_500)
        Fx.log("after reset: settings shows ${str(R.string.settings_permissions)}")
    }
}
