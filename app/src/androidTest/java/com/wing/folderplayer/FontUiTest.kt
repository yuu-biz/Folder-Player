package com.wing.folderplayer

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.wing.folderplayer.ui.theme.AppFont
import com.wing.folderplayer.ui.theme.FontManager
import com.wing.folderplayer.ui.theme.FontState
import com.wing.folderplayer.ui.theme.FontValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Pinned font download (size + SHA-256 checked) and selection surviving a restart, download failure without
 * network, TTF import through the system picker, rejection of a bad header and of a > 50 MiB file, and fallback to the
 * system font when the selected file is broken. Methods run in order (instrument.sh run-each).
 */
@RunWith(AndroidJUnit4::class)
class FontUiTest : UiTestBase() {
    private val fonts get() = FontManager.get(Fx.ctx)
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())


    private fun network(on: Boolean) {
        val v = if (on) "enable" else "disable"
        Fx.shell("svc wifi $v"); Fx.shell("svc data $v")
        if (on) Thread.sleep(8_000)
    }

    /** Adds a file to the shared Downloads collection (visible in the system picker). */
    private fun download(name: String, bytes: ByteArray) {
        val r = Fx.ctx.contentResolver
        r.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        })!!
        r.openOutputStream(uri)!!.use { it.write(bytes) }
    }

    /** Clicks the first object matching [sel], re-finding it if the picker re-renders (stale objects). */
    private fun clickRetrying(sel: androidx.test.uiautomator.BySelector, what: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val ok = runCatching { device.wait(Until.findObject(sel), 2_000)?.click() != null }.getOrDefault(false)
            if (ok) { device.waitForIdle(); return }
            Thread.sleep(300)
        }
        error("$what not found")
    }

    private fun pickFromDownloads(name: String) {
        assertTrue("picker", device.wait(Until.hasObject(By.pkg(Pattern.compile("com\\.(google\\.)?android\\.documentsui"))), 15_000))
        Thread.sleep(1_500)
        if (!device.hasObject(By.text(name))) {
            runCatching { device.findObject(By.desc("Show roots"))?.click() }
            clickRetrying(By.text("Downloads"), "Downloads root")
            Thread.sleep(1_000)
        }
        runCatching { UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(name)) }
        clickRetrying(By.text(name), name)
        assertTrue(device.wait(Until.hasObject(By.pkg(Fx.ctx.packageName)), 10_000))
    }

    private fun importViaUi(name: String) {
        toSettings()
        settingsReveal("font_import"); click("font_import")
        pickFromDownloads(name)
    }

    @Test fun a_downloadPinnedFontAndSelect() {
        onUi { fonts.delete(AppFont.LXGW_WENKAI); fonts.select(AppFont.SYSTEM) }
        toSettings()
        settingsReveal("font_dl_lxgw_wenkai"); click("font_dl_lxgw_wenkai")
        until(240_000, "download finished") { fonts.states.value[AppFont.LXGW_WENKAI] == FontState.Ready || fonts.states.value[AppFont.LXGW_WENKAI] is FontState.Failed }
        assertEquals(FontState.Ready, fonts.states.value[AppFont.LXGW_WENKAI])
        val f = fonts.fileFor(AppFont.LXGW_WENKAI)!!
        assertEquals(AppFont.LXGW_WENKAI.sizeBytes, f.length())
        assertEquals(AppFont.LXGW_WENKAI.sha256, FontValidation.sha256(f))
        until(5_000, "selected after download") { fonts.selected.value == AppFont.LXGW_WENKAI }
        assertEquals(null, fonts.fallbackReason.value)
    }

    @Test fun b_selectionSurvivesRestart() {
        assertEquals(AppFont.LXGW_WENKAI, fonts.selected.value)
        assertNotEquals(androidx.compose.ui.text.font.FontFamily.Default, onUi { fonts.fontFamily() })
        assertEquals(null, fonts.fallbackReason.value)
    }

    @Test fun c_downloadFailsCleanlyOffline() {
        onUi { fonts.delete(AppFont.SARASA_UI_SC) }
        network(false)
        try {
            toSettings()
            settingsReveal("font_dl_sarasa_ui_sc"); click("font_dl_sarasa_ui_sc")
            until(90_000, "failure") { fonts.states.value[AppFont.SARASA_UI_SC] is FontState.Failed }
            Fx.log("offline download: ${fonts.states.value[AppFont.SARASA_UI_SC]}")
            assertTrue(!File(Fx.ctx.filesDir, "fonts/${AppFont.SARASA_UI_SC.fileName}.part").exists())
            assertEquals("selection unchanged", AppFont.LXGW_WENKAI, fonts.selected.value)
            settingsReveal("font_status_sarasa_ui_sc")
            assertTrue(text("font_status_sarasa_ui_sc").isNotBlank())
        } finally {
            network(true)
        }
    }

    @Test fun d_importValidRejectBadHeaderAndOversize() {
        val sys = File("/system/fonts").listFiles { f -> f.name.endsWith(".ttf") && f.length() in 10_000..5_000_000 }?.firstOrNull() ?: error("no system ttf")
        download("fp-valid.ttf", sys.readBytes())
        download("fp-bad-header.ttf", ByteArray(4096) { 0x41 })
        download("fp-oversize.ttf", ByteArray((FontValidation.MAX_IMPORT_BYTES + 1024).toInt()).also { it[0] = 0; it[1] = 1; it[2] = 0; it[3] = 0 })

        importViaUi("fp-bad-header.ttf")
        until(10_000, "bad header rejected") { textExists(str(R.string.settings_font_import_failed, "not a TrueType/OpenType font")) }
        importViaUi("fp-oversize.ttf")
        until(20_000, "oversize rejected") { textExists(str(R.string.settings_font_import_failed, "file larger than 50 MiB")) }
        assertEquals(AppFont.LXGW_WENKAI, fonts.selected.value)

        importViaUi("fp-valid.ttf")
        until(15_000, "imported") { fonts.states.value[AppFont.IMPORTED] == FontState.Ready && fonts.selected.value == AppFont.IMPORTED }
        assertEquals("fp-valid.ttf", fonts.importedName)

        // A broken selected file falls back to the system font and says why.
        fonts.fileFor(AppFont.IMPORTED)!!.writeBytes(ByteArray(64))
        compose.activityRule.scenario.recreate()
        toSettings()
        until(10_000, "fallback explained") { fonts.fallbackReason.value != null }
        Fx.log("fallback reason: ${fonts.fallbackReason.value}")
        onUi { fonts.delete(AppFont.IMPORTED); fonts.select(AppFont.SYSTEM) }
        for (n in listOf("fp-valid.ttf", "fp-bad-header.ttf", "fp-oversize.ttf"))
            Fx.ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(n))
    }

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
}
