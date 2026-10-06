package com.wing.folderplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.playlist.PlaylistManager
import com.wing.folderplayer.data.prefs.PlaybackPreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceCapability
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceInput
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Only the last play request counts. Request A reads a slow network source (I/O that ignores cancellation and returns
 * late); the user picks B meanwhile; when A's I/O finally returns, it must not replace B's queue, title, error,
 * saved state or "Default" playlist.
 */
@RunWith(AndroidJUnit4::class)
class PlayRequestRaceTest : ServiceTestBase() {
    private val slowCfg = SourceConfig(id = "slow-nas", name = "slow", type = SourceType.SMB, host = "slow.invalid", share = "s", username = "u")
    private val slow = slowCfg.id
    private val gate = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    @Volatile private var failAfterGate = false

    /** NAS stand-in: every read waits at the gate (ignoring interrupts, like a stuck socket), then answers. */
    private inner class SlowFs : SourceFileSystem {
        override val config = slowCfg
        override val capabilities = emptySet<SourceCapability>()
        private fun hold() {
            entered.countDown()
            while (true) {
                try { if (gate.await(60, TimeUnit.SECONDS)) break } catch (e: InterruptedException) { /* not cancellable */ }
            }
            if (failAfterGate) throw SourceException.Unreachable("slow.invalid")
        }
        private val album = listOf(
            MusicFile("a1.flac", "/A/a1.flac", false, 1000, 0, slow),
            MusicFile("a2.flac", "/A/a2.flac", false, 1000, 0, slow),
            MusicFile("a.cue", "/A/a.cue", false, 100, 0, slow),
        )
        override fun list(path: String): List<MusicFile> { hold(); return if (path == "/A") album else emptyList() }
        override fun stat(path: String): MusicFile? { hold(); return album.firstOrNull { it.path == path } }
        override fun openRead(path: String, offset: Long, length: Long): SourceInput {
            hold()
            if (path != "/A/a.cue") throw SourceException.NotFound(path)
            val bytes = "FILE \"a1.flac\" WAVE\n  TRACK 01 AUDIO\n    TITLE \"A one\"\n    INDEX 01 00:00:00\n".toByteArray()
            return object : SourceInput() {
                var pos = 0
                override val length = bytes.size.toLong()
                override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= bytes.size) return -1
                    val n = minOf(len, bytes.size - pos); System.arraycopy(bytes, pos, b, off, n); pos += n; return n
                }
            }
        }
    }

    @Before fun addSlowSource() {
        SourceRegistry.fileSystemFactory = { c -> if (c.id == slow) SlowFs() else null }
        SourceRegistry.upsert(slowCfg, "p")
        // Next-folder playback (switched on by other suites' data) would move on by itself.
        main { if (vm.uiState.value.autoNextFolder) vm.toggleAutoNextFolder() }
    }

    @After fun removeSlowSource() {
        gate.countDown()
        SourceRegistry.fileSystemFactory = null
        SourceRegistry.remove(slow)
    }

    private val albumA = SourceRef(local, "/Music/fixture/Album-A")
    private val albumB = SourceRef(local, "/Music/fixture/Album-B")

    private fun awaitSlowRead() = assertTrue("request A reached the slow source", entered.await(15, TimeUnit.SECONDS))

    /** B plays; A's late answer (given at the end) changes nothing. */
    private fun assertOnly(b: SourceRef, what: String) {
        waitFor(15_000, "$what: B playing") { state.isPlaying && state.currentMediaId?.contains(b.path.substringAfterLast('/')) == true }
        val before = state.playlist.map { it.mediaId }
        gate.countDown() // A's I/O returns now, long after B started
        Thread.sleep(3_000) // A's follow-up work (queue, metadata refresh after 1 s, saved state)
        val s = state
        val queue = s.playlist.map { it.mediaId }
        assertEquals("$what: queue is still B's", before, queue)
        assertTrue("$what: no entry of A in the queue: $queue", queue.isNotEmpty() && queue.none { it.contains(slow) })
        assertTrue("$what: current track is B's: ${s.currentMediaId}", s.currentMediaId?.contains(b.path.substringAfterLast('/')) == true)
        assertTrue("$what: still playing", s.isPlaying)
        assertNull("$what: no error from A", s.playbackError)
        assertTrue("$what: folder name is B's: ${s.currentFolderName}", s.currentFolderName.contains(b.path.substringAfterLast('/')))
        assertEquals("$what: saved folder", b, PlaybackPreferences(Fx.ctx).getLastFolder())
        val saved = PlaybackPreferences(Fx.ctx).getLastMediaId()
        assertTrue("$what: saved track is B's: $saved", saved?.contains(slow) == false)
        val default = PlaylistManager(Fx.ctx).getPlaylist("default")!!.items
        assertTrue("$what: Default playlist is B's: ${default.map { it.path }}", default.isNotEmpty() && default.none { it.sourceId == slow })
        assertTrue("$what: shown Default playlist is B's", s.activePlaylistItems.none { it.sourceId == slow })
    }

    @Test fun customListOnSlowSourceThenFolder() {
        main { vm.playCustomList(listOf(MusicFile("a1.flac", "/A/a1.flac", false, 1000, 0, slow)), 0) }
        awaitSlowRead()
        main { vm.playFolder(albumA, null) }
        assertOnly(albumA, "custom list")
    }

    @Test fun slowFolderThenFolder() {
        main { vm.playFolder(SourceRef(slow, "/A"), null) }
        awaitSlowRead()
        main { vm.playFolder(albumB, null) }
        assertOnly(albumB, "folder")
    }

    @Test fun slowFolderFailingLateDoesNotShowItsError() {
        failAfterGate = true
        main { vm.playFolder(SourceRef(slow, "/A"), null) }
        awaitSlowRead()
        main { vm.playFolder(albumB, null) }
        assertOnly(albumB, "failing folder")
    }

    @Test fun slowCueSheetThenFolder() {
        main { vm.playCueSheet(SourceRef(slow, "/A/a.cue")) }
        awaitSlowRead()
        main { vm.playFolder(albumA, null) }
        assertOnly(albumA, "CUE")
    }

    @Test fun slowFolderThenCustomList() {
        main { vm.playFolder(SourceRef(slow, "/A"), null) }
        awaitSlowRead()
        val b = listOf(MusicFile("track.flac", "/Music/fixture/Album-B/track.flac", false, 0, 0, local))
        main { vm.playCustomList(b, 0) }
        assertOnly(albumB, "custom list B")
    }

    /** The restore of the last session reads the slow source when the app starts; the user plays something else. */
    @Test fun slowRestoreThenFolder() {
        main { vm.dismissSession() }
        PlaybackPreferences(Fx.ctx).savePlaybackState(SourceRef(slow, "/A"), SourceRef(slow, "/A/a2.flac").toUriString(), 5_000)
        // A new UI (cold start) connects to an empty player and starts the restore.
        main { androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm) }
        main { vm = PlayerViewModel(); vm.initializeController(Fx.ctx) }
        awaitSlowRead()
        main { vm.playFolder(albumB, null) }
        assertOnly(albumB, "restore")
    }
}
