package com.wing.folderplayer

import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.ui.browser.BrowserViewModel
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a check: takes the representative screenshots of the UI (phone, landscape, wide, Settings, long texts, large font) into
 * `/sdcard/Android/data/<app>/files/shots`, for a person to look at. Run on its own:
 * `instrument.sh <serial> run ScreenshotTest`, then pull the folder. Needs the fixture (`push-fixture`) and, for the long
 * names, [Fx.FX]/ShotLong (a few copies of a fixture track with long names; made from the host, see the verification notes).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest : UiTestBase() {
    private val local = SourceRegistry.LOCAL_INTERNAL_ID
    private val fx = Fx.FX
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val browser: BrowserViewModel get() = onUi { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private val outDir by lazy { File(Fx.ctx.getExternalFilesDir(null), "shots").apply { mkdirs() } }
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val f = File(outDir, "$name.png")
        device.takeScreenshot(f)
        Fx.log("screenshot $name -> ${f.absolutePath} (${f.length()} bytes)")
    }

    private fun windowDp(w: Int, h: Int) {
        Fx.shell("wm size ${(w * density).toInt()}x${(h * density).toInt()}")
        until(10_000, "window ${w}x$h dp") {
            val c = compose.activity.resources.configuration
            // The height reported is the window without the system bars (50 to 110 dp less).
            c.screenWidthDp in (w - 8)..(w + 8) && c.screenHeightDp in (h - 130)..(h + 8)
        }
        compose.waitForIdle()
    }

    private fun fontScale(scale: Float) {
        Fx.shell("settings put system font_scale $scale")
        until(10_000, "font scale") { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < 0.01f }
        compose.waitForIdle()
    }

    private fun open(folder: String) {
        toBrowser()
        onUi { browser.loadFolder(SourceRef(local, folder)) }
        until(15_000, "folder $folder") { browser.uiState.value.currentFolder?.path == folder && !browser.uiState.value.isLoading }
        if (browser.uiState.value.viewMode != "LIST") click("btn_view_mode")
        until(5_000, "list") { exists("file_list") }
    }

    @After fun cleanup() {
        Fx.shell("wm size reset")
        Fx.shell("settings put system font_scale 1.0")
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    @Test fun shots() {
        // ---- phone portrait: browser with long names, mini player, full player
        toBrowser()
        open("$fx/ShotLong")
        onUi { player.playFolder(SourceRef(local, "$fx/Album-A"), null) }
        until(15_000, "playing") { player.uiState.value.isPlaying }
        until(5_000, "mini") { exists("mini_player") }
        shot("01-phone-portrait-browser-long-names")
        click("mini_player")
        until(5_000, "full") { playerOpen() }
        shot("02-phone-portrait-full-player")
        click("btn_collapse_player")
        until(5_000, "mini") { exists("mini_player") }

        // ---- phone portrait, Settings
        toSettings()
        shot("03-settings-phone-root")
        click("settings_cat_display")
        shot("04-settings-phone-display")
        click("settings_sub_language")
        shot("05-settings-phone-language")
        toBrowser()

        // ---- narrow 320 dp, large font
        windowDp(320, 640)
        fontScale(2.0f)
        open("$fx/ShotLong")
        shot("06-narrow320-font2-browser")
        click("mini_player")
        until(5_000, "full") { playerOpen() }
        shot("07-narrow320-font2-full-player")
        click("btn_collapse_player")
        toSettings()
        shot("08-narrow320-font2-settings-root")
        click("settings_cat_library")
        shot("09-narrow320-font2-settings-library")
        toBrowser()
        fontScale(1.0f)
        Fx.shell("wm size reset")
        Thread.sleep(1_000)

        // ---- phone landscape
        device.setOrientationLeft()
        until(10_000, "landscape") { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        open("$fx/Album-A")
        shot("10-phone-landscape-browser")
        click("mini_player")
        until(5_000, "full") { playerOpen() }
        shot("11-phone-landscape-full-player")
        click("btn_collapse_player")
        device.setOrientationNatural()
        device.unfreezeRotation()
        Thread.sleep(1_000)

        // ---- wide (unfolded / tablet)
        windowDp(840, 700)
        open("$fx/ShotLong")
        shot("12-wide-840x700-browser-and-player")
        compose.onNodeWithTag("player_docked").performTouchInput { swipe(Offset(centerX, height * 0.85f), Offset(centerX, height * 0.55f), 200) }
        shot("13-wide-840x700-playlist")
        pressBack()
        toSettings()
        shot("14-wide-settings-two-pane")
        click("settings_cat_display")
        click("settings_sub_language")
        shot("15-wide-settings-language")
        toBrowser()
        windowDp(1200, 800)
        shot("16-wide-1200x800-browser-and-player")
        windowDp(800, 1280)
        shot("17-wide-800x1280-portrait-tablet")
        Fx.shell("wm size reset")
    }
}
