package com.wing.folderplayer

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.SourceRef
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The media notification follows rapid source switches (Local → SMB → Local, with repeat-one) and a new controller
 * after the previous one was released. Regression for dropped notification updates (system rate limit) and for the
 * restore seek that hit a newer queue.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSwitchTest : ServiceTestBase() {
    private fun expect(label: String, title: String, rgb: Triple<Int, Int, Int>) {
        waitFor(10_000, "notification $label") {
            val b = notificationBitmap() ?: return@waitFor false
            notificationTitle() == title && close(centreColor(b), rgb)
        }
        Fx.log("notification $label ok: ${notificationTitle()}")
    }

    @Test fun followsLocalSmbLocalWithRepeatOne() {
        Fx.require("smb_host")
        main { while (vm.uiState.value.repeatMode != Player.REPEAT_MODE_ONE) vm.toggleRepeatMode() }
        try {
            main { vm.playFolder(SourceRef(local, "/Music/fixture/CorruptCover"), null) }
            expect("local", "track", GREEN)
            main { vm.playFolder(SourceRef(ids["smb"]!!, "/fixture/Album-A"), null) }
            expect("smb", "01 曲 #1+%", RED)
            main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-B"), null) }
            expect("local2", "track", ORANGE)
        } finally {
            main { while (vm.uiState.value.repeatMode != Player.REPEAT_MODE_OFF) vm.toggleRepeatMode() }
        }
    }

    @Test fun followsNewControllerAfterRelease() {
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-B"), null) }
        expect("first controller", "track", ORANGE)
        main { vm.playPause() }
        main { androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm) }
        Thread.sleep(1_000)
        main { vm = com.wing.folderplayer.ui.player.PlayerViewModel(); vm.initializeController(Fx.ctx) }
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-C"), null) }
        expect("second controller", "track-with-embedded-art", MAGENTA)
    }
}
