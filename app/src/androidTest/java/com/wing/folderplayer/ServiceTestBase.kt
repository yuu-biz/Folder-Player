package com.wing.folderplayer

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.player.PlayerUiState
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before

/** Drives the production PlayerViewModel → MediaController → MusicService stack. */
abstract class ServiceTestBase {
    protected lateinit var vm: PlayerViewModel
    protected val ids = mutableMapOf<String, String>()
    protected val local = SourceRegistry.LOCAL_INTERNAL_ID

    /** SAF tests keep their picked folders between methods (the grant is what is being tested). */
    protected open val keepSafSources = false

    @Before fun startVm() {
        SourceRegistry.init(Fx.ctx)
        SourceRegistry.savedSources().filter { !(keepSafSources && it.type == SourceType.SAF) }.forEach { SourceRegistry.remove(it.id) }
        Fx.arg("smb_host")?.let { h ->
            val c = SourceConfig(name = "smb", type = SourceType.SMB, host = h, share = "music", username = "alice")
            SourceRegistry.upsert(c, "alicepass"); ids["smb"] = c.id
        }
        Fx.arg("webdav_url")?.let { u ->
            val c = SourceConfig(name = "dav", type = SourceType.WEBDAV, url = u, username = "alice")
            SourceRegistry.upsert(c, "pa:ss/1"); ids["dav"] = c.id
        }
        Fx.arg("ftp_host")?.let { h ->
            val c = SourceConfig(name = "ftp-trunc", type = SourceType.FTP, host = h, port = 2123, username = "alice")
            SourceRegistry.upsert(c, "alicepass"); ids["ftp_trunc"] = c.id
        }
        com.wing.folderplayer.data.prefs.SourcePreferences(Fx.ctx).saveDefaultSort("NAME", true)
        main { vm = PlayerViewModel(); vm.initializeController(Fx.ctx) }
        waitFor(15_000, "media controller") { main { vm.uiState.value.allPlaylists.isNotEmpty() }; controllerReady() }
    }

    @After fun stopVm() {
        main { if (vm.uiState.value.isPlaying) vm.playPause() }
        Thread.sleep(300)
        // A ViewModel outside an Activity is never cleared; release its MediaController like onCleared() would.
        main { androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm) }
        Thread.sleep(300)
    }

    private fun controllerReady(): Boolean {
        val f = PlayerViewModel::class.java.getDeclaredField("player").apply { isAccessible = true }
        return main { f.get(vm) } != null
    }

    protected fun <T> main(block: () -> T): T {
        var r: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    protected val state: PlayerUiState get() = main { vm.uiState.value }

    protected fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(200)
        }
        fail("timed out waiting for $what; state=${state.copy(lyrics = state.lyrics.take(2), playlist = emptyList(), activePlaylistItems = emptyList())}")
    }

    /** Large icon of the app's media notification, if present. */
    protected fun notificationBitmap(): Bitmap? {
        val nm = Fx.ctx.getSystemService(NotificationManager::class.java)
        val n = nm.activeNotifications.firstOrNull { it.notification.extras.containsKey("android.mediaSession") } ?: return null
        val icon = n.notification.getLargeIcon() ?: return null
        return (icon.loadDrawable(Fx.ctx) as? BitmapDrawable)?.bitmap
    }

    protected fun notificationTitle(): String? {
        val nm = Fx.ctx.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.firstOrNull { it.notification.extras.containsKey("android.mediaSession") }
            ?.notification?.extras?.getCharSequence("android.title")?.toString()
    }

    /** Average colour of the bitmap centre (fixture covers are solid colours with a marker strip at the top). */
    protected fun centreColor(b: Bitmap): Triple<Int, Int, Int> {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (x in b.width / 3 until b.width * 2 / 3 step 4) for (y in b.height / 2 until b.height * 5 / 6 step 4) {
            val c = b.getPixel(x, y); r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; bl += c and 0xff; n++
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    protected fun close(c: Triple<Int, Int, Int>, rgb: Triple<Int, Int, Int>, tol: Int = 40) =
        kotlin.math.abs(c.first - rgb.first) <= tol && kotlin.math.abs(c.second - rgb.second) <= tol && kotlin.math.abs(c.third - rgb.third) <= tol

    companion object {
        val RED = Triple(230, 20, 20)
        val MAGENTA = Triple(220, 0, 200)
        val GREEN = Triple(20, 190, 40)
        val ORANGE = Triple(250, 140, 0)
        val CYAN = Triple(0, 210, 220)
    }
}
