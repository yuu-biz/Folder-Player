package com.wing.folderplayer

import android.content.ContentUris
import android.provider.MediaStore
import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.playback.ExportSettings
import com.wing.folderplayer.playback.ExportStatus
import com.wing.folderplayer.playback.NetworkRetryController
import com.wing.folderplayer.playback.PlaybackExportManager
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Network retry (loss → recovery, limits, cancellation, auth errors) and verified auto-save on the device. */
@RunWith(AndroidJUnit4::class)
class RetryExportTest : ServiceTestBase() {

    private fun network(on: Boolean) {
        val v = if (on) "enable" else "disable"
        Fx.shell("svc wifi $v"); Fx.shell("svc data $v")
        Fx.log("network $v")
        if (on) waitFor(90_000, "network back") {
            runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("webdav", 80), 2_000) } }.isSuccess
        }
    }

    /**
     * Next-folder playback off, whatever an earlier suite left (MigrationTest migrates data with it on): these tests
     * wait for the export of one track, and playback must not move on to other folders meanwhile.
     */
    @Before fun nextFolderOff() {
        main { if (vm.uiState.value.autoNextFolder) vm.toggleAutoNextFolder() }
    }

    @After fun networkBack() { network(true); ExportSettings(Fx.ctx).enabled = false }

    @Test fun networkLossIsRetriedAndRecovers() {
        Fx.require("webdav_url")
        val dav = ids["dav"]!!
        main { vm.playFolder(SourceRef(dav, "/fixture/Noise"), null) }
        waitFor(20_000, "noise track playing") { state.isPlaying && state.currentPosition > 2_000 }
        network(false)
        // The 60 s buffer drains first; then ExoPlayer's own load retries fail and our controller takes over.
        waitFor(180_000, "first retry scheduled") { NetworkRetryController.status.value.attempt >= 1 }
        val s1 = NetworkRetryController.status.value
        Fx.log("retry status after cut: $s1")
        assertTrue(s1.lastErrorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || s1.lastErrorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            s1.lastErrorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        waitFor(60_000, "several attempts with growing delay") { NetworkRetryController.status.value.attempt >= 3 }
        val posBefore = state.currentPosition
        network(true)
        waitFor(90_000, "playback resumed") { state.isPlaying && state.currentPosition > posBefore + 2_000 }
        Fx.log("recovered at ${state.currentPosition} after ${NetworkRetryController.status.value}")

        // A pending retry is cancelled by a track change (restart the track so at most 60 s are buffered).
        main { vm.playFolder(SourceRef(dav, "/fixture/Noise"), null) }
        waitFor(20_000, "noise track restarted") { state.isPlaying && state.currentPosition in 1_000..30_000 }
        network(false)
        waitFor(180_000, "retry scheduled again") { NetworkRetryController.status.value.attempt >= 1 && NetworkRetryController.status.value.pendingDelayMs > 0 }
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Album-B"), null) }
        waitFor(10_000, "pending retry cancelled") { NetworkRetryController.status.value.pendingDelayMs == 0L }
        val frozen = NetworkRetryController.status.value.attempt
        Thread.sleep(15_000)
        assertTrue("no retries after the track change", NetworkRetryController.status.value.attempt <= frozen)
        assertTrue("local playback unaffected", state.isPlaying)
    }

    @Test fun authenticationFailureIsNotRetried() {
        Fx.require("webdav_url")
        val bad = SourceConfig(name = "dav-bad", type = SourceType.WEBDAV, url = Fx.arg("webdav_url")!!, username = "alice")
        SourceRegistry.upsert(bad, "wrong-password")
        main { vm.playCustomList(listOf(MusicFile("track.flac", "/fixture/Album-B/track.flac", false, 0, 0, bad.id)), 0) }
        waitFor(20_000, "auth error") { state.playbackError != null }
        Thread.sleep(5_000)
        val st = NetworkRetryController.status.value
        Fx.log("auth failure: ${state.playbackError} status=$st")
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, st.lastErrorCode)
        assertEquals(0L, st.pendingDelayMs)
    }

    /**
     * The server ends each RETR early (SIZE promises more). The early EOF is a load error for the player, which
     * resumes with REST at the missing offset and plays the track to its real end; the cover on the same server is
     * truncated too and must not stop playback from starting.
     */
    @Test fun truncatedTransferResumesAtOffsetAndPlaysToTheEnd() {
        Fx.require("ftp_host")
        main { vm.playCustomList(listOf(MusicFile("track 047.flac", "/fixture/Many/Folder 047/track 047.flac", false, 0, 0, ids["ftp_trunc"]!!)), 0) }
        waitFor(30_000, "track played to its end") { !state.isPlaying && state.duration == 2_000L && state.currentPosition >= 1_950 }
        Fx.log("truncated transfer: error=${state.playbackError} pos=${state.currentPosition}/${state.duration} retry=${NetworkRetryController.status.value}")
        assertEquals(null, state.playbackError)
    }

    // ---------------- auto-save ----------------

    private fun exportedRows(): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        Fx.ctx.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Music/FolderPlayer/%"), null
        )?.use { c -> while (c.moveToNext()) out[c.getString(1) + c.getString(2)] = c.getLong(0) to c.getLong(3) }
        return out
    }

    private fun clearExports() {
        for ((_, v) in exportedRows()) Fx.ctx.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v.first), null, null)
        java.io.File(Fx.ctx.filesDir, "export-index.json").delete()
    }

    private fun awaitExport(name: String, st: ExportStatus.State, timeoutMs: Long = 40_000) =
        waitFor(timeoutMs, "export $st for $name") {
            val s = PlaybackExportManager.status.value
            s != null && s.ref.contains(name.replace(" ", "%20")) && s.state == st
        }

    @Test fun completedNetworkTracksAreSavedVerifiedAndOnlyOnce() {
        org.junit.Assume.assumeTrue(Fx.sdk >= 29)
        Fx.require("smb_host", "ftp_host")
        val smb = ids["smb"]!!
        clearExports()
        ExportSettings(Fx.ctx).enabled = false
        // OFF: nothing is exported.
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 040"), null) }
        waitFor(20_000, "040 ended") { !state.isPlaying && state.currentPosition >= 1_900 }
        Thread.sleep(3_000)
        assertTrue("export while disabled", exportedRows().isEmpty())

        ExportSettings(Fx.ctx).enabled = true
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 041"), null) }
        awaitExport("track 041.flac", ExportStatus.State.SAVED)
        val rows = exportedRows()
        Fx.log("exported: $rows")
        val key = "Music/FolderPlayer/Folder 041/track 041.flac"
        val (id, size) = rows[key] ?: error("missing $key in $rows")
        val source = SourceRegistry.fileSystem(smb).readBytes("/fixture/Many/Folder 041/track 041.flac", 1 shl 24)
        assertEquals(source.size.toLong(), size)
        val saved = Fx.ctx.contentResolver.openInputStream(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id))!!.use { it.readBytes() }
        assertArrayEquals("byte-exact copy", source, saved)

        // Same track again: duplicate detected, no second file.
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 041"), null) }
        awaitExport("track 041.flac", ExportStatus.State.SKIPPED_DUPLICATE)
        assertEquals(1, exportedRows().keys.count { it.endsWith("track 041.flac") })

        // Skipped before the end (user next, not an automatic transition): not exported.
        val two = listOf(MusicFile("track 042.flac", "/fixture/Many/Folder 042/track 042.flac", false, 0, 0, smb),
            MusicFile("track 043.flac", "/fixture/Many/Folder 043/track 043.flac", false, 0, 0, smb))
        main { vm.playCustomList(two, 0) }
        waitFor(10_000, "042 playing") { state.isPlaying && state.currentMediaId?.contains("042") == true }
        main { vm.next() }
        awaitExport("track 043.flac", ExportStatus.State.SAVED)
        assertTrue("skipped track must not be saved", exportedRows().keys.none { it.endsWith("track 042.flac") })

        // Seeking during playback does not matter: the export is a separate complete read after the natural end.
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 046"), null) }
        waitFor(10_000, "046 playing") { state.isPlaying && state.currentMediaId?.contains("046") == true && state.duration > 0 }
        main { vm.seekTo(0.6f) }
        awaitExport("track 046.flac", ExportStatus.State.SAVED)
        val key46 = "Music/FolderPlayer/Folder 046/track 046.flac"
        val id46 = exportedRows()[key46]?.first ?: error("missing $key46")
        val src46 = SourceRegistry.fileSystem(smb).readBytes("/fixture/Many/Folder 046/track 046.flac", 1 shl 24)
        val out46 = Fx.ctx.contentResolver.openInputStream(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id46))!!.use { it.readBytes() }
        assertArrayEquals("seeked track exported complete", src46, out46)

        // Truncated transfer: playback recovers (see above), but the export's single complete read ends early, so it
        // fails and nothing is published.
        main { vm.playCustomList(listOf(MusicFile("track 044.flac", "/fixture/Many/Folder 044/track 044.flac", false, 0, 0, ids["ftp_trunc"]!!)), 0) }
        awaitExport("track 044.flac", ExportStatus.State.FAILED)
        Fx.log("truncated: export=${PlaybackExportManager.status.value}")
        assertTrue(exportedRows().keys.none { it.endsWith("track 044.flac") })

        // Local tracks are never exported.
        main { vm.playFolder(SourceRef(local, "/Music/fixture/Many/Folder 045"), null) }
        waitFor(20_000, "local ended") { !state.isPlaying && state.currentMediaId?.contains("045") == true }
        Thread.sleep(3_000)
        assertTrue(exportedRows().keys.none { it.contains("045") })
        clearExports()
    }

    /** A user file with the same name and size is not "our" export, is never overwritten, and two sources never collide. */
    @Test fun sameNameFilesAreNeitherDuplicatesNorOverwritten() {
        org.junit.Assume.assumeTrue(Fx.sdk >= 29)
        Fx.require("smb_host", "webdav_url")
        val smb = ids["smb"]!!; val dav = ids["dav"]!!
        clearExports()
        val srcPath = "/fixture/Many/Folder 048/track 048.flac"
        val size = SourceRegistry.fileSystem(smb).stat(srcPath)!!.size
        // The user already has an unrelated file at the destination: same name, same size, other content.
        val r = Fx.ctx.contentResolver
        val userUri = r.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "track 048.flac")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/flac")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/FolderPlayer/Folder 048/")
        })!!
        val userBytes = ByteArray(size.toInt()) { 0x55 }
        r.openOutputStream(userUri)!!.use { it.write(userBytes) }
        Fx.log("pre-existing user file: ${exportedRows()}")

        ExportSettings(Fx.ctx).enabled = true
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 048"), null) }
        awaitExport("track 048.flac", ExportStatus.State.SAVED)
        // The same path on another source is another track: saved separately, not a duplicate.
        main { vm.playFolder(SourceRef(dav, "/fixture/Many/Folder 048"), null) }
        waitFor(40_000, "WebDAV export") {
            PlaybackExportManager.status.value?.let { it.ref.startsWith("fpsrc://$dav/") && it.state == ExportStatus.State.SAVED } == true
        }
        val rows = exportedRows().filterKeys { it.startsWith("Music/FolderPlayer/Folder 048/") }
        Fx.log("rows after export: $rows")
        assertEquals("user file + two exports", 3, rows.size)
        val userNow = r.openInputStream(userUri)!!.use { it.readBytes() }
        assertArrayEquals("user file untouched", userBytes, userNow)
        val source = SourceRegistry.fileSystem(smb).readBytes(srcPath, 1 shl 24)
        val ours = rows.filterKeys { !it.endsWith("/track 048.flac") }.values
        assertEquals(2, ours.size)
        for ((id, _) in ours) {
            val bytes = r.openInputStream(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id))!!.use { it.readBytes() }
            assertArrayEquals("byte-exact export", source, bytes)
        }
        // Played again: now a real duplicate of the first export.
        main { vm.playFolder(SourceRef(smb, "/fixture/Many/Folder 048"), null) }
        awaitExport("track 048.flac", ExportStatus.State.SKIPPED_DUPLICATE)
        clearExports()
    }
}
