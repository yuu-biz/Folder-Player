package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.prefs.SourcePreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.ui.player.TimerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback through the real PlayerViewModel → MusicService: cover priority in the
 * player and the media notification (Local and SMB), per-track covers in mixed lists, CUE metadata, LRC and embedded
 * lyrics, playlists, sleep timer, next-folder playback, sort memory.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest : ServiceTestBase() {

    private fun file(id: String, path: String) = MusicFile(SourceRef(id, path).name, path, false, 0, 0, id)

    private fun awaitCover(rgb: Triple<Int, Int, Int>, what: String) {
        waitFor(20_000, "notification cover $what") {
            val b = notificationBitmap() ?: return@waitFor false
            runCatching {
                java.io.File(Fx.ctx.getExternalFilesDir(null), "notif-${what.replace(' ', '_')}.png").outputStream().use {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val c = centreColor(b)
            val nm = Fx.ctx.getSystemService(android.app.NotificationManager::class.java)
            Fx.log("notification $what colour=$c title=${notificationTitle()} count=${nm.activeNotifications.size} ids=${nm.activeNotifications.map { it.id }}")
            close(c, rgb)
        }
    }

    @Test fun coverFolderImageBeatsEmbeddedAndFollowsAlbumSwitch() {
        // Pause one album, then start another: the notification must follow (title and cover).
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Many/Folder 031"), null) }
        waitFor(15_000, "Folder 031") { state.isPlaying && state.currentMediaId?.contains("Folder%20031") == true }
        waitFor(10_000, "notification 031") { notificationTitle()?.contains("031") == true }
        main { vm.playPause() }
        Thread.sleep(2_000)
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-A"), "/Music/fixture/Album-A/02 track.mp3") }
        waitFor(15_000, "Album-A playing") { state.isPlaying && state.currentMediaId?.contains("02%20track") == true }
        // Folder image (cover.jpg, red) wins over the mp3's embedded picture (yellow).
        waitFor(10_000, "player cover") { state.coverUri?.toString()?.contains("Album-A/cover.jpg") == true }
        assertTrue("embedded art kept as fallback", state.coverFallback != null)
        awaitCover(RED, "Album-A local")
        assertTrue("notification title follows the track: ${notificationTitle()}", notificationTitle()?.contains("02 track") == true)
    }

    @Test fun coverEmbeddedWhenNoFolderImage() {
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-C"), null) }
        waitFor(15_000, "Album-C playing") { state.isPlaying && state.currentMediaId?.contains("Album-C") == true }
        waitFor(10_000, "embedded cover") { state.coverUri is ByteArray }
        awaitCover(MAGENTA, "Album-C embedded")
    }

    @Test fun coverFallsBackFromBrokenImage() {
        main { vm.playFolder(SourceRef(local, "/Music/fixture/CorruptCover"), null) }
        waitFor(15_000, "CorruptCover playing") { state.isPlaying && state.currentMediaId?.contains("CorruptCover") == true }
        waitFor(10_000, "fallback cover") { state.coverUri?.toString()?.contains("CorruptCover/folder.png") == true }
        awaitCover(GREEN, "CorruptCover fallback")
    }

    @Test fun coverFromSmbInPlayerAndNotification() {
        Fx.require("smb_host")
        main { vm.playFolder(SourceRef(ids["smb"]!!, "/fixture/Album-A"), null) }
        waitFor(20_000, "SMB playing") { state.isPlaying && state.currentMediaId?.startsWith("fpsrc://${ids["smb"]}") == true }
        waitFor(10_000, "SMB player cover") { state.coverUri?.toString()?.contains("/fixture/Album-A/cover.jpg") == true }
        awaitCover(RED, "Album-A SMB")
    }

    @Test fun mixedListUsesEachTracksFolder() {
        val mixed = listOf(
            file(local, "/Music/fixture/Album-B/track.flac"),
            file(local, "/Music/fixture/Parent/CD1/track.flac"),
        )
        main { vm.playCustomList(mixed, 0) }
        waitFor(15_000, "mixed 1") { state.isPlaying && state.currentMediaId?.contains("Album-B") == true }
        awaitCover(ORANGE, "mixed Album-B")
        main { vm.next() }
        waitFor(15_000, "mixed 2") { state.currentMediaId?.contains("Parent/CD1") == true }
        waitFor(10_000, "parent cover") { state.coverUri?.toString()?.contains("Parent/cover.jpg") == true }
        awaitCover(CYAN, "mixed Parent fallback")
    }

    @Test fun cueTracksAndLyrics() {
        // On Android 11+ shared-storage .cue/.lrc of other apps are unreadable through the File API: the app says so.
        if (Fx.sdk >= 30) {
            main { vm.playCueSheet(SourceRef(local, "/Music/fixture/Cue/image.cue")) }
            waitFor(10_000, "SAF hint") { state.playbackError?.contains("SAF") == true }
        }
        Fx.require("webdav_url")
        val dav = ids["dav"]!!
        main { vm.playCueSheet(SourceRef(dav, "/fixture/Cue/image.cue")) }
        waitFor(15_000, "CUE playing") { state.isPlaying && SourceUris.isCueTrackId(state.currentMediaId) }
        assertEquals(3, state.playlist.size)
        assertEquals(listOf("Cue One", "Cue Two", "Cue Three"), state.playlist.map { it.mediaMetadata.title.toString() })
        assertEquals("Guest", state.playlist[2].mediaMetadata.artist.toString())
        assertEquals(40_000L, state.playlist[2].clippingConfiguration.startPositionMs)
        main { vm.next() }
        waitFor(10_000, "Cue Two") { state.currentTitle == "Cue Two" }
        assertTrue("clip position restarts", state.currentPosition < 5_000)

        main { vm.playFolder(SourceRef(dav, "/fixture/Album-A"), "/fixture/Album-A/01 曲 #1+%.flac") }
        waitFor(15_000, "LRC loaded") { state.lyrics.any { it.text.contains("LRC line one 曲") } }
        assertEquals("LRC", state.lyricsSource)
        assertTrue(state.lyricsSynced)
        main { vm.next() }
        waitFor(15_000, "embedded lyrics") { state.lyrics.any { it.text.contains("embedded line one") } }
        assertEquals("Embedded", state.lyricsSource)
    }

    @Test fun playlistsTimerNextFolderAndSortMemory() {
        // Sort memory decides the queue order.
        val albumA = SourceRef(local, "/Music/fixture/Album-A")
        SourcePreferences(Fx.ctx).saveDirectorySort(albumA, "NAME", false)
        main { vm.playFolder(albumA, null) }
        waitFor(15_000, "sorted queue") { state.playlist.firstOrNull()?.mediaId?.contains("02%20track") == true }
        SourcePreferences(Fx.ctx).saveDirectorySort(albumA, "NAME", true)

        // Playlists.
        main { vm.createPlaylist("Test list") }
        waitFor(5_000, "playlist created") { state.allPlaylists.any { it.name == "Test list" } }
        val listId = state.allPlaylists.first { it.name == "Test list" }.id
        main { vm.addFilesToPlaylist(listId, listOf(file(local, "/Music/fixture/Album-B/track.flac"), MusicFile("Many", "/Music/fixture/Many/Folder 010", true, 0, 0, local))) }
        waitFor(10_000, "items added") { state.allPlaylists.first { it.id == listId }.items.size == 2 }
        main { vm.switchPlaylist(listId); vm.playPlaylistSong(listId, 1) }
        waitFor(15_000, "playing from playlist") { state.isPlaying && state.currentMediaId?.contains("Folder%20010") == true }
        main { vm.deletePlaylist(listId) }

        // Sleep timer by songs: stops after the first track ends.
        val twoShort = listOf(file(local, "/Music/fixture/Many/Folder 020/track 020.flac"), file(local, "/Music/fixture/Many/Folder 021/track 021.flac"))
        main { vm.playCustomList(twoShort, 0); vm.startSleepTimer(TimerType.SONGS, 1) }
        waitFor(15_000, "timer stops playback") { !state.isPlaying && state.currentMediaId?.contains("021") == true && !state.sleepTimerActive }

        // Next folder after the end of the queue.
        main { if (!vm.uiState.value.autoNextFolder) vm.toggleAutoNextFolder() }
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Many/Folder 030"), null) }
        waitFor(20_000, "next folder") { state.currentMediaId?.contains("Folder%20031") == true && state.isPlaying }
        main { vm.toggleAutoNextFolder() }
        assertFalse(state.autoNextFolder)
        assertTrue("media notification shown", notificationTitle() != null)
        Fx.log("notification title: ${notificationTitle()}")

    }

    /** Shuffle at a root / artist folder lands on a folder with no songs of its own: nothing is torn down. */
    @Test fun folderWithoutSongsOfItsOwnKeepsThePlayback() {
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Long"), null) }
        waitFor(15_000, "Long playing") { state.isPlaying && state.currentMediaId?.contains("Long") == true }
        val id = state.currentMediaId
        val queue = state.playlist.size
        val defaultItems = state.activePlaylistItems.size
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Many"), null) } // subfolders only
        Thread.sleep(2_500)
        assertTrue("still playing", state.isPlaying)
        assertEquals("same track", id, state.currentMediaId)
        assertEquals("queue untouched", queue, state.playlist.size)
        assertEquals("Default playlist untouched", defaultItems, state.activePlaylistItems.size)
        assertTrue(state.currentTitle != "Loading...")
        assertFalse("no dead 'loading' request left", state.playRequested)
    }

    /** With "next folder" on the queue ends and another one starts: a clock timer must still be there to stop it. */
    @Test fun timeTimerSurvivesTheEndOfTheQueue() {
        val twoShort = listOf(file(local, "/Music/fixture/Many/Folder 020/track 020.flac"), file(local, "/Music/fixture/Many/Folder 021/track 021.flac"))
        main { vm.startSleepTimer(TimerType.TIME, 30) }
        waitFor(5_000, "timer set") { state.sleepTimerActive }
        main { vm.playCustomList(twoShort, 0) }
        waitFor(15_000, "second track reached") { state.currentMediaId?.contains("021") == true }
        waitFor(20_000, "queue ended") { !state.isPlaying }
        Thread.sleep(1_500)
        assertTrue("TIME timer still running after the queue ended", state.sleepTimerActive)
        main { vm.resetSleepTimer() }
        waitFor(5_000, "timer cancelled") { !state.sleepTimerActive }
    }

    /** Headphones unplugged (ACTION_AUDIO_BECOMING_NOISY) pauses playback. */
    @Test fun headphoneUnplugPauses() {
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Long"), null) }
        waitFor(15_000, "playing") { state.isPlaying && state.currentPosition > 1_000 }
        val out = Fx.shell("am broadcast -a android.media.AUDIO_BECOMING_NOISY -p ${Fx.ctx.packageName}")
        Fx.log("noisy broadcast: ${out.trim()}")
        val deadline = System.currentTimeMillis() + 3_000
        while (state.isPlaying && System.currentTimeMillis() < deadline) Thread.sleep(200)
        if (state.isPlaying) {
            // API 34+: a non-exported receiver only gets this protected broadcast from the system; hand the same intent
            // to the receiver the service registered (the system delivery itself is OS behaviour).
            Fx.log("shell broadcast not delivered on API ${Fx.sdk}; invoking the registered receiver directly")
            val svc = com.wing.folderplayer.service.MusicService.current ?: error("service not running")
            main { svc.becomingNoisyReceiver.onReceive(svc, android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        } else Fx.log("paused by the shell broadcast")
        waitFor(5_000, "paused by AUDIO_BECOMING_NOISY") { !state.isPlaying }
        val pos = state.currentPosition
        Thread.sleep(1_500)
        assertTrue("stays paused", !state.isPlaying && state.currentPosition - pos < 300)
    }
}
